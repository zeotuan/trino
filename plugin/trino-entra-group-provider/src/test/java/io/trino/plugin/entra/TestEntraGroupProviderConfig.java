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

import io.airlift.units.Duration;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

public class TestEntraGroupProviderConfig
{
    @Test
    public void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(EntraGroupProviderConfig.class)
                .setTenantId(null)
                .setClientId(null)
                .setClientSecret(null)
                .setAuthorityBaseUrl(URI.create("https://login.microsoftonline.com"))
                .setGraphBaseUrl(URI.create("https://graph.microsoft.com/v1.0"))
                .setGraphScope("https://graph.microsoft.com/.default")
                .setCacheTtl(new Duration(5, MINUTES))
                .setFailureCacheTtl(new Duration(10, SECONDS))
                .setGroupNameAttribute(EntraGroupNameAttribute.ID)
                .setSecurityGroupsOnly(true)
                .setMaxPages(100));
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = Map.of(
                "entra.tenant-id", "contoso.onmicrosoft.com",
                "entra.client-id", "client",
                "entra.client-secret", "secret",
                "entra.authority-base-url", "https://login.microsoftonline.us",
                "entra.graph-base-url", "https://graph.microsoft.us/v1.0",
                "entra.graph-scope", "https://graph.microsoft.us/.default",
                "entra.cache-ttl", "1h",
                "entra.failure-cache-ttl", "2s",
                "entra.group-name-attribute", "DISPLAY_NAME",
                "entra.security-groups-only", "false",
                "entra.max-pages", "7");

        EntraGroupProviderConfig expected = new EntraGroupProviderConfig()
                .setTenantId("contoso.onmicrosoft.com")
                .setClientId("client")
                .setClientSecret("secret")
                .setAuthorityBaseUrl(URI.create("https://login.microsoftonline.us"))
                .setGraphBaseUrl(URI.create("https://graph.microsoft.us/v1.0"))
                .setGraphScope("https://graph.microsoft.us/.default")
                .setCacheTtl(new Duration(1, HOURS))
                .setFailureCacheTtl(new Duration(2, SECONDS))
                .setGroupNameAttribute(EntraGroupNameAttribute.DISPLAY_NAME)
                .setSecurityGroupsOnly(false)
                .setMaxPages(7);

        assertFullMapping(properties, expected);
    }
}
