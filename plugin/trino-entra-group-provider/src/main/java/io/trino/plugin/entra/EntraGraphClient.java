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

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import io.airlift.http.client.FullJsonResponseHandler.JsonResponse;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.HttpStatus;
import io.airlift.http.client.Request;
import io.airlift.json.JsonCodec;

import java.net.URI;
import java.net.URLEncoder;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static com.google.common.net.HttpHeaders.ACCEPT;
import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static com.google.common.net.MediaType.FORM_DATA;
import static com.google.common.net.MediaType.JSON_UTF_8;
import static io.airlift.http.client.FullJsonResponseHandler.createFullJsonResponseHandler;
import static io.airlift.http.client.HeaderNames.CONTENT_TYPE;
import static io.airlift.http.client.Request.Builder.prepareGet;
import static io.airlift.http.client.Request.Builder.preparePost;
import static io.airlift.http.client.StaticBodyGenerator.createStaticBodyGenerator;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

public class EntraGraphClient
{
    private final HttpClient httpClient;
    private final JsonCodec<EntraTokenResponse> tokenResponseCodec;
    private final JsonCodec<EntraMembershipPage> membershipPageCodec;
    private final EntraGroupProviderConfig config;
    private final Clock clock;
    private final String graphBaseUrl;
    private final Object tokenRefreshLock = new Object();
    private volatile CachedAccessToken cachedAccessToken;
    private CompletableFuture<CachedAccessToken> tokenRefresh;

    @Inject
    public EntraGraphClient(
            @ForEntra HttpClient httpClient,
            JsonCodec<EntraTokenResponse> tokenResponseCodec,
            JsonCodec<EntraMembershipPage> membershipPageCodec,
            EntraGroupProviderConfig config,
            Clock clock)
    {
        this.httpClient = requireNonNull(httpClient, "httpClient is null");
        this.tokenResponseCodec = requireNonNull(tokenResponseCodec, "tokenResponseCodec is null");
        this.membershipPageCodec = requireNonNull(membershipPageCodec, "membershipPageCodec is null");
        this.config = requireNonNull(config, "config is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.graphBaseUrl = trimTrailingSlash(config.getGraphBaseUrl().toString());
    }

    public Set<EntraGroup> listTransitiveGroups(String user)
    {
        requireNonNull(user, "user is null");

        ImmutableSet.Builder<EntraGroup> groups = ImmutableSet.builder();
        URI pageUri = firstMembershipPageUri(user);
        for (int page = 1; page <= config.getMaxPages(); page++) {
            EntraMembershipPage membershipPage = fetchMembershipPage(pageUri);
            groups.addAll(membershipPage.value());
            if (Strings.isNullOrEmpty(membershipPage.nextLink())) {
                return groups.build();
            }
            pageUri = validateNextLink(membershipPage.nextLink());
        }
        throw new EntraGroupProviderException(format("Microsoft Graph returned more than %s pages for user [%s]", config.getMaxPages(), user));
    }

    private EntraMembershipPage fetchMembershipPage(URI uri)
    {
        Request request = prepareGet()
                .setUri(uri)
                .setHeader(AUTHORIZATION, "Bearer " + accessToken())
                .setHeader(ACCEPT, JSON_UTF_8.toString())
                .build();
        JsonResponse<EntraMembershipPage> response = httpClient.execute(request, createFullJsonResponseHandler(membershipPageCodec));
        return parseJsonResponse(response, "Microsoft Graph group membership", uri);
    }

    private String accessToken()
    {
        CachedAccessToken token = cachedAccessToken;
        if (isTokenValid(token)) {
            return token.value();
        }

        CompletableFuture<CachedAccessToken> refresh;
        boolean refreshOwner = false;
        synchronized (tokenRefreshLock) {
            token = cachedAccessToken;
            if (isTokenValid(token)) {
                return token.value();
            }
            if (tokenRefresh == null) {
                tokenRefresh = new CompletableFuture<>();
                refreshOwner = true;
            }
            refresh = tokenRefresh;
        }

        if (refreshOwner) {
            refreshAccessToken(refresh);
        }

        try {
            return refresh.get().value();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EntraGroupProviderException("Interrupted while waiting for Entra access token refresh", e);
        }
        catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new EntraGroupProviderException("Failed to refresh Entra access token", cause);
        }
    }

    private void refreshAccessToken(CompletableFuture<CachedAccessToken> refresh)
    {
        try {
            CachedAccessToken token = requestAccessToken();
            synchronized (tokenRefreshLock) {
                cachedAccessToken = token;
                tokenRefresh = null;
            }
            refresh.complete(token);
        }
        catch (Throwable e) {
            synchronized (tokenRefreshLock) {
                if (tokenRefresh == refresh) {
                    tokenRefresh = null;
                }
            }
            refresh.completeExceptionally(e);
            if (e instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) e;
        }
    }

    private CachedAccessToken requestAccessToken()
    {
        Instant now = clock.instant();
        EntraTokenResponse response = fetchAccessToken();
        if (Strings.isNullOrEmpty(response.accessToken())) {
            throw new EntraGroupProviderException("Microsoft identity platform token response did not include access_token");
        }
        if (response.expiresIn() <= 0) {
            throw new EntraGroupProviderException("Microsoft identity platform token response did not include a valid expires_in value");
        }

        long usableLifetimeSeconds = response.expiresIn() > 60 ? response.expiresIn() - 60 : response.expiresIn();
        return new CachedAccessToken(response.accessToken(), now.plusSeconds(usableLifetimeSeconds));
    }

    private boolean isTokenValid(CachedAccessToken token)
    {
        return token != null && token.expiresAt().isAfter(clock.instant());
    }

    private EntraTokenResponse fetchAccessToken()
    {
        URI tokenUri = tokenEndpointUri();
        Request request = preparePost()
                .setUri(tokenUri)
                .setHeader(CONTENT_TYPE, FORM_DATA.toString())
                .setHeader(ACCEPT, JSON_UTF_8.toString())
                .setBodyGenerator(createStaticBodyGenerator(tokenRequestBody(), UTF_8))
                .build();
        JsonResponse<EntraTokenResponse> response = httpClient.execute(request, createFullJsonResponseHandler(tokenResponseCodec));
        return parseJsonResponse(response, "Microsoft identity platform token", tokenUri);
    }

    private <T> T parseJsonResponse(JsonResponse<T> response, String responseType, URI uri)
    {
        int statusCode = response.getStatusCode();
        if (HttpStatus.familyForStatusCode(statusCode) != HttpStatus.Family.SUCCESSFUL) {
            throw new EntraGroupProviderException(format("%s request to [%s] failed with status code %s: %s", responseType, uri, statusCode, response));
        }
        if (!response.hasValue()) {
            throw new EntraGroupProviderException(format("%s response from [%s] could not be parsed", responseType, uri), response.getException());
        }
        return response.getValue();
    }

    private URI tokenEndpointUri()
    {
        return URI.create(trimTrailingSlash(config.getAuthorityBaseUrl().toString()) + "/" + encodePathSegment(config.getTenantId()) + "/oauth2/v2.0/token");
    }

    private String tokenRequestBody()
    {
        StringJoiner body = new StringJoiner("&");
        body.add("client_id=" + encodeFormValue(config.getClientId()));
        body.add("client_secret=" + encodeFormValue(config.getClientSecret()));
        body.add("scope=" + encodeFormValue(config.getGraphScope()));
        body.add("grant_type=client_credentials");
        return body.toString();
    }

    private URI firstMembershipPageUri(String user)
    {
        return URI.create(graphBaseUrl + "/users/" + encodePathSegment(user) + "/transitiveMemberOf/microsoft.graph.group?$select=id,displayName,securityEnabled&$top=999");
    }

    private URI validateNextLink(String nextLink)
    {
        URI nextUri = URI.create(nextLink);
        String nextUriString = nextUri.toString();
        if (!nextUriString.equals(graphBaseUrl) && !nextUriString.startsWith(graphBaseUrl + "/")) {
            throw new EntraGroupProviderException(format("Microsoft Graph returned nextLink outside configured graph base URL: %s", nextLink));
        }
        return nextUri;
    }

    private static String encodePathSegment(String value)
    {
        return URLEncoder.encode(value, UTF_8).replace("+", "%20");
    }

    private static String encodeFormValue(String value)
    {
        return URLEncoder.encode(value, UTF_8);
    }

    private static String trimTrailingSlash(String value)
    {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private record CachedAccessToken(String value, Instant expiresAt)
    {
        private CachedAccessToken
        {
            requireNonNull(value, "value is null");
            requireNonNull(expiresAt, "expiresAt is null");
        }
    }
}
