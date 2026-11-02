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

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.inject.Inject;
import io.airlift.log.Logger;
import io.trino.spi.security.GroupProvider;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class EntraGroupProvider
        implements GroupProvider
{
    private static final Logger log = Logger.get(EntraGroupProvider.class);

    private final EntraGraphClient graphClient;
    private final EntraGroupMapping groupMapping;
    private final EntraGroupProviderConfig config;
    private final LoadingCache<String, Set<String>> userGroups;
    private final Cache<String, Set<String>> failedUserGroups;

    @Inject
    public EntraGroupProvider(EntraGraphClient graphClient, EntraGroupMapping groupMapping, EntraGroupProviderConfig config)
    {
        this.graphClient = requireNonNull(graphClient, "graphClient is null");
        this.groupMapping = requireNonNull(groupMapping, "groupMapping is null");
        this.config = requireNonNull(config, "config is null");
        this.userGroups = CacheBuilder.newBuilder()
                .expireAfterWrite(config.getCacheTtl().toMillis(), MILLISECONDS)
                .build(CacheLoader.from(this::loadGroups));
        this.failedUserGroups = CacheBuilder.newBuilder()
                .expireAfterWrite(config.getFailureCacheTtl().toMillis(), MILLISECONDS)
                .build();
    }

    @Override
    public Set<String> getGroups(String user)
    {
        requireNonNull(user, "user is null");
        Set<String> failedGroups = failedUserGroups.getIfPresent(user);
        if (failedGroups != null) {
            return failedGroups;
        }
        try {
            return userGroups.get(user);
        }
        catch (ExecutionException e) {
            log.error(e.getCause(), "Failed to resolve Entra groups for user [%s]", user);
            return cacheFailure(user);
        }
        catch (UncheckedExecutionException e) {
            log.error(e.getCause(), "Failed to resolve Entra groups for user [%s]", user);
            return cacheFailure(user);
        }
    }

    private Set<String> loadGroups(String user)
    {
        return graphClient.listTransitiveGroups(user).stream()
                .filter(group -> !config.isSecurityGroupsOnly() || group.isSecurityEnabled())
                .map(group -> groupMapping.mapGroup(group, config.getGroupNameAttribute()))
                .flatMap(Optional::stream)
                .collect(toImmutableSet());
    }

    private Set<String> cacheFailure(String user)
    {
        Set<String> groups = ImmutableSet.of();
        failedUserGroups.put(user, groups);
        return groups;
    }
}
