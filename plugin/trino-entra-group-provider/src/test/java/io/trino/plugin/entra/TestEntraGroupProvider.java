/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.entra;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.HttpStatus;
import io.airlift.http.client.Request;
import io.airlift.http.client.Response;
import io.airlift.http.client.StaticBodyGenerator;
import io.airlift.http.client.testing.TestingHttpClient;
import io.airlift.http.client.testing.TestingResponse;
import io.trino.spi.security.GroupProvider;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static com.google.common.net.MediaType.JSON_UTF_8;
import static io.airlift.http.client.HeaderNames.CONTENT_TYPE;
import static io.airlift.http.client.HttpStatus.INTERNAL_SERVER_ERROR;
import static io.airlift.http.client.HttpStatus.OK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestEntraGroupProvider
{
    private static final URI AUTHORITY_BASE_URL = URI.create("https://login.example");
    private static final URI GRAPH_BASE_URL = URI.create("https://graph.example/v1.0");
    private static final URI TOKEN_URI = URI.create("https://login.example/tenant/oauth2/v2.0/token");
    private static final URI ALICE_MEMBERSHIP_URI = URI.create("https://graph.example/v1.0/users/alice/transitiveMemberOf/microsoft.graph.group?$select=id,displayName,securityEnabled&$top=999");
    private static final URI ALICE_NEXT_MEMBERSHIP_URI = URI.create("https://graph.example/v1.0/users/alice/transitiveMemberOf/microsoft.graph.group?$skiptoken=next");

    @Test
    public void testMappedGroupsAndCaching()
    {
        AtomicInteger tokenRequests = new AtomicInteger();
        AtomicInteger membershipRequests = new AtomicInteger();
        HttpClient httpClient = new TestingHttpClient(request -> {
            if (request.getUri().equals(TOKEN_URI)) {
                tokenRequests.incrementAndGet();
                assertThat(request.getMethod()).isEqualTo("POST");
                assertThat(requestBody(request)).contains("client_id=client", "client_secret=secret", "scope=https%3A%2F%2Fgraph.microsoft.com%2F.default", "grant_type=client_credentials");
                return jsonResponse(OK, """
                        {
                          "access_token": "token",
                          "expires_in": 3600
                        }
                        """);
            }

            assertThat(request.getHeader(AUTHORIZATION)).isEqualTo("Bearer token");
            if (request.getUri().equals(ALICE_MEMBERSHIP_URI)) {
                membershipRequests.incrementAndGet();
                assertThat(request.getMethod()).isEqualTo("GET");
                return jsonResponse(OK, """
                        {
                          "value": [
                            {"id": "group-admin", "displayName": "Admins", "securityEnabled": true},
                            {"id": "group-mail", "displayName": "Mail", "securityEnabled": false}
                          ],
                          "@odata.nextLink": "https://graph.example/v1.0/users/alice/transitiveMemberOf/microsoft.graph.group?$skiptoken=next"
                        }
                        """);
            }
            if (request.getUri().equals(ALICE_NEXT_MEMBERSHIP_URI)) {
                membershipRequests.incrementAndGet();
                assertThat(request.getMethod()).isEqualTo("GET");
                return jsonResponse(OK, """
                        {
                          "value": [
                            {"id": "group-reader", "displayName": "Readers", "securityEnabled": true},
                            {"id": "group-unmapped", "displayName": "Unmapped", "securityEnabled": true}
                          ]
                        }
                        """);
            }
            throw new AssertionError("Unexpected request: " + request.getUri());
        });

        GroupProvider groupProvider = EntraGroupProviderFactory.create(ImmutableMap.<String, String>builder()
                .putAll(baseConfig())
                .put("entra.group-mapping.group-admin", "trino-admin")
                .put("entra.group-mapping.group-reader", "trino-reader")
                .buildOrThrow(), Optional.of(httpClient), Clock.systemUTC());

        assertThat(groupProvider.getGroups("alice")).containsExactlyInAnyOrder("trino-admin", "trino-reader");
        assertThat(groupProvider.getGroups("alice")).containsExactlyInAnyOrder("trino-admin", "trino-reader");
        assertThat(tokenRequests).hasValue(1);
        assertThat(membershipRequests).hasValue(2);
    }

    @Test
    public void testReturnsDisplayNamesWhenNoMappingIsConfigured()
    {
        HttpClient httpClient = new TestingHttpClient(request -> {
            if (request.getUri().equals(TOKEN_URI)) {
                return jsonResponse(OK, """
                        {
                          "access_token": "token",
                          "expires_in": 3600
                        }
                        """);
            }
            if (request.getUri().equals(ALICE_MEMBERSHIP_URI)) {
                return jsonResponse(OK, """
                        {
                          "value": [
                            {"id": "group-admin", "displayName": "Admins", "securityEnabled": true},
                            {"id": "group-reader", "displayName": "Readers", "securityEnabled": true}
                          ]
                        }
                        """);
            }
            throw new AssertionError("Unexpected request: " + request.getUri());
        });

        GroupProvider groupProvider = EntraGroupProviderFactory.create(ImmutableMap.<String, String>builder()
                .putAll(baseConfig())
                .put("entra.group-name-attribute", "DISPLAY_NAME")
                .buildOrThrow(), Optional.of(httpClient), Clock.systemUTC());

        assertThat(groupProvider.getGroups("alice")).containsExactlyInAnyOrder("Admins", "Readers");
    }

    @Test
    public void testGraphLookupFailureReturnsNoGroups()
    {
        AtomicInteger membershipRequests = new AtomicInteger();
        HttpClient httpClient = new TestingHttpClient(request -> {
            if (request.getUri().equals(TOKEN_URI)) {
                return jsonResponse(OK, """
                        {
                          "access_token": "token",
                          "expires_in": 3600
                        }
                        """);
            }
            membershipRequests.incrementAndGet();
            return jsonResponse(INTERNAL_SERVER_ERROR, """
                    {
                      "error": "failure"
                    }
                    """);
        });

        GroupProvider groupProvider = EntraGroupProviderFactory.create(baseConfig(), Optional.of(httpClient), Clock.systemUTC());

        assertThat(groupProvider.getGroups("alice")).isEmpty();
        assertThat(groupProvider.getGroups("alice")).isEmpty();
        assertThat(membershipRequests).hasValue(1);
    }

    @Test
    public void testRejectsGraphNextLinkOutsideGraphBaseUrl()
    {
        AtomicInteger membershipRequests = new AtomicInteger();
        HttpClient httpClient = new TestingHttpClient(request -> {
            if (request.getUri().equals(TOKEN_URI)) {
                return jsonResponse(OK, """
                        {
                          "access_token": "token",
                          "expires_in": 3600
                        }
                        """);
            }
            if (request.getUri().equals(ALICE_MEMBERSHIP_URI)) {
                membershipRequests.incrementAndGet();
                return jsonResponse(OK, """
                        {
                          "value": [
                            {"id": "group-admin", "displayName": "Admins", "securityEnabled": true}
                          ],
                          "@odata.nextLink": "https://evil.example/v1.0/users/alice/transitiveMemberOf/microsoft.graph.group?$skiptoken=next"
                        }
                        """);
            }
            throw new AssertionError("Unexpected request: " + request.getUri());
        });

        GroupProvider groupProvider = EntraGroupProviderFactory.create(baseConfig(), Optional.of(httpClient), Clock.systemUTC());

        assertThat(groupProvider.getGroups("alice")).isEmpty();
        assertThat(membershipRequests).hasValue(1);
    }

    @Test
    public void testInvalidGroupMappingProperty()
    {
        Map<String, String> config = ImmutableMap.<String, String>builder()
                .putAll(baseConfig())
                .put("entra.group-mapping.", "trino-admin")
                .buildOrThrow();

        assertThatThrownBy(() -> EntraGroupProviderFactory.create(config, Optional.empty(), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must include an Entra group ID suffix");
    }

    private static Map<String, String> baseConfig()
    {
        return ImmutableMap.<String, String>builder()
                .put("entra.tenant-id", "tenant")
                .put("entra.client-id", "client")
                .put("entra.client-secret", "secret")
                .put("entra.authority-base-url", AUTHORITY_BASE_URL.toString())
                .put("entra.graph-base-url", GRAPH_BASE_URL.toString())
                .put("entra.cache-ttl", "1h")
                .buildOrThrow();
    }

    private static String requestBody(Request request)
    {
        StaticBodyGenerator bodyGenerator = (StaticBodyGenerator) requireNonNull(request.getBodyGenerator(), "body generator is null");
        return new String(bodyGenerator.getBody(), UTF_8);
    }

    private static Response jsonResponse(HttpStatus status, String body)
    {
        return TestingResponse.mockResponse(status, JSON_UTF_8, body);
    }
}
