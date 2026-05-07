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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Scopes;
import io.airlift.bootstrap.Bootstrap;
import io.airlift.http.client.HttpClient;
import io.airlift.json.JsonModule;
import io.trino.spi.security.GroupProvider;
import io.trino.spi.security.GroupProviderFactory;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import static com.google.common.base.Strings.isNullOrEmpty;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static io.airlift.http.client.HttpClientBinder.httpClientBinder;
import static io.airlift.json.JsonCodecBinder.jsonCodecBinder;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class EntraGroupProviderFactory
        implements GroupProviderFactory
{
    private static final String GROUP_MAPPING_PROPERTY_PREFIX = "entra.group-mapping.";

    @Override
    public String getName()
    {
        return "entra-id";
    }

    @Override
    public GroupProvider create(Map<String, String> config)
    {
        return create(config, Optional.empty(), Clock.systemUTC());
    }

    @VisibleForTesting
    static GroupProvider create(Map<String, String> config, Optional<HttpClient> httpClient, Clock clock)
    {
        requireNonNull(config, "config is null");
        requireNonNull(httpClient, "httpClient is null");
        requireNonNull(clock, "clock is null");

        Map<String, String> requiredConfig = new HashMap<>(config);
        EntraGroupMapping groupMapping = new EntraGroupMapping(extractGroupMappings(requiredConfig));

        Bootstrap app = new Bootstrap(
                "io.trino.bootstrap.groups.entra-id",
                new JsonModule(),
                binder -> {
                    configBinder(binder).bindConfig(EntraGroupProviderConfig.class);
                    jsonCodecBinder(binder).bindJsonCodec(EntraTokenResponse.class);
                    jsonCodecBinder(binder).bindJsonCodec(EntraMembershipPage.class);
                    httpClient.ifPresentOrElse(
                            client -> binder.bind(Key.get(HttpClient.class, ForEntra.class)).toInstance(client),
                            () -> httpClientBinder(binder).bindHttpClient("entra-id", ForEntra.class));
                    binder.bind(Clock.class).toInstance(clock);
                    binder.bind(EntraGroupMapping.class).toInstance(groupMapping);
                    binder.bind(EntraGraphClient.class).in(Scopes.SINGLETON);
                    binder.bind(GroupProvider.class).to(EntraGroupProvider.class).in(Scopes.SINGLETON);
                });

        Injector injector = app
                .doNotInitializeLogging()
                .disableSystemProperties()
                .setRequiredConfigurationProperties(requiredConfig)
                .initialize();

        return injector.getInstance(GroupProvider.class);
    }

    private static Map<String, String> extractGroupMappings(Map<String, String> config)
    {
        ImmutableMap.Builder<String, String> groupMappings = ImmutableMap.builder();
        for (Entry<String, String> entry : ImmutableMap.copyOf(config).entrySet()) {
            if (entry.getKey().startsWith(GROUP_MAPPING_PROPERTY_PREFIX)) {
                config.remove(entry.getKey());
                String groupId = entry.getKey().substring(GROUP_MAPPING_PROPERTY_PREFIX.length());
                if (isNullOrEmpty(groupId)) {
                    throw new IllegalArgumentException(format("Group mapping property [%s] must include an Entra group ID suffix", entry.getKey()));
                }
                if (isNullOrEmpty(entry.getValue())) {
                    throw new IllegalArgumentException(format("Group mapping property [%s] must have a non-empty Trino group name", entry.getKey()));
                }
                groupMappings.put(groupId, entry.getValue());
            }
        }
        return groupMappings.buildOrThrow();
    }
}
