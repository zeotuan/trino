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

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;
import io.airlift.configuration.ConfigSecuritySensitive;
import io.airlift.units.Duration;
import io.airlift.units.MinDuration;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.net.URI;
import java.util.concurrent.TimeUnit;

public class EntraGroupProviderConfig
{
    private String tenantId;
    private String clientId;
    private String clientSecret;
    private URI authorityBaseUrl = URI.create("https://login.microsoftonline.com");
    private URI graphBaseUrl = URI.create("https://graph.microsoft.com/v1.0");
    private String graphScope = "https://graph.microsoft.com/.default";
    private Duration cacheTtl = new Duration(5, TimeUnit.MINUTES);
    private Duration failureCacheTtl = new Duration(10, TimeUnit.SECONDS);
    private EntraGroupNameAttribute groupNameAttribute = EntraGroupNameAttribute.ID;
    private boolean securityGroupsOnly = true;
    private int maxPages = 100;

    @NotNull
    @NotEmpty
    public String getTenantId()
    {
        return tenantId;
    }

    @Config("entra.tenant-id")
    @ConfigDescription("Microsoft Entra ID tenant ID or tenant domain")
    public EntraGroupProviderConfig setTenantId(String tenantId)
    {
        this.tenantId = tenantId;
        return this;
    }

    @NotNull
    @NotEmpty
    public String getClientId()
    {
        return clientId;
    }

    @Config("entra.client-id")
    @ConfigDescription("Client ID for the Entra application used to query Microsoft Graph")
    public EntraGroupProviderConfig setClientId(String clientId)
    {
        this.clientId = clientId;
        return this;
    }

    @NotNull
    @NotEmpty
    public String getClientSecret()
    {
        return clientSecret;
    }

    @Config("entra.client-secret")
    @ConfigSecuritySensitive
    @ConfigDescription("Client secret for the Entra application used to query Microsoft Graph")
    public EntraGroupProviderConfig setClientSecret(String clientSecret)
    {
        this.clientSecret = clientSecret;
        return this;
    }

    @NotNull
    public URI getAuthorityBaseUrl()
    {
        return authorityBaseUrl;
    }

    @Config("entra.authority-base-url")
    @ConfigDescription("Base URL for the Microsoft identity platform token endpoint")
    public EntraGroupProviderConfig setAuthorityBaseUrl(URI authorityBaseUrl)
    {
        this.authorityBaseUrl = authorityBaseUrl;
        return this;
    }

    @NotNull
    public URI getGraphBaseUrl()
    {
        return graphBaseUrl;
    }

    @Config("entra.graph-base-url")
    @ConfigDescription("Microsoft Graph API base URL")
    public EntraGroupProviderConfig setGraphBaseUrl(URI graphBaseUrl)
    {
        this.graphBaseUrl = graphBaseUrl;
        return this;
    }

    @NotNull
    public String getGraphScope()
    {
        return graphScope;
    }

    @Config("entra.graph-scope")
    @ConfigDescription("OAuth2 client credentials scope for Microsoft Graph")
    public EntraGroupProviderConfig setGraphScope(String graphScope)
    {
        this.graphScope = graphScope;
        return this;
    }

    @MinDuration("1ms")
    @NotNull
    public Duration getCacheTtl()
    {
        return cacheTtl;
    }

    @Config("entra.cache-ttl")
    @ConfigDescription("How long to cache Entra group membership lookups")
    public EntraGroupProviderConfig setCacheTtl(Duration cacheTtl)
    {
        this.cacheTtl = cacheTtl;
        return this;
    }

    @MinDuration("1ms")
    @NotNull
    public Duration getFailureCacheTtl()
    {
        return failureCacheTtl;
    }

    @Config("entra.failure-cache-ttl")
    @ConfigDescription("How long to cache failed Entra group membership lookups as empty groups")
    public EntraGroupProviderConfig setFailureCacheTtl(Duration failureCacheTtl)
    {
        this.failureCacheTtl = failureCacheTtl;
        return this;
    }

    @NotNull
    public EntraGroupNameAttribute getGroupNameAttribute()
    {
        return groupNameAttribute;
    }

    @Config("entra.group-name-attribute")
    @ConfigDescription("Group attribute to return when no explicit group mapping is configured. Valid values are ID and DISPLAY_NAME")
    public EntraGroupProviderConfig setGroupNameAttribute(EntraGroupNameAttribute groupNameAttribute)
    {
        this.groupNameAttribute = groupNameAttribute;
        return this;
    }

    public boolean isSecurityGroupsOnly()
    {
        return securityGroupsOnly;
    }

    @Config("entra.security-groups-only")
    @ConfigDescription("Ignore non-security-enabled Entra groups")
    public EntraGroupProviderConfig setSecurityGroupsOnly(boolean securityGroupsOnly)
    {
        this.securityGroupsOnly = securityGroupsOnly;
        return this;
    }

    @Min(1)
    public int getMaxPages()
    {
        return maxPages;
    }

    @Config("entra.max-pages")
    @ConfigDescription("Maximum number of Microsoft Graph membership pages to follow per lookup")
    public EntraGroupProviderConfig setMaxPages(int maxPages)
    {
        this.maxPages = maxPages;
        return this;
    }
}
