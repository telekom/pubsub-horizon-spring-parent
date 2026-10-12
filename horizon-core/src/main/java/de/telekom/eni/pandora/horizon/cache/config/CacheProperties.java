// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.DeprecatedConfigurationProperty;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties("horizon.cache")
public class CacheProperties {

    private String name = "cache";

    private String kubernetesServiceDns;

    private boolean enabled = false;

    private LocalSubscriptionCacheProperties localSubscriptionCache = new LocalSubscriptionCacheProperties();

    private DeDuplicationProperties deDuplication = new DeDuplicationProperties();

    private Map<String, String> attributes = new HashMap<>();

    /** Configuration properties for the pod-local subscription snapshot cache. */
    @Getter
    @Setter
    public static class LocalSubscriptionCacheProperties {

        private boolean enabled = false;

        // NONE never reads Hazelcast and serves stale local entries indefinitely if necessary.
        private LocalSubscriptionCacheFallback fallbackMode = LocalSubscriptionCacheFallback.HAZELCAST_WITH_MONGO_FALLBACK;

        private String snapshotCollection = "subscriptions.subscriber.horizon.telekom.de.v1-snapshots";

        // Server-side limit (maxTimeMS) for loading snapshot entries.
        private Duration mongoLoadTimeout = Duration.ofSeconds(60);

        // Only applies to HAZELCAST_WITH_MONGO_FALLBACK.
        private Duration staleLocalCacheReadGracePeriod = Duration.ofSeconds(120);

        // Only applies to HAZELCAST_WITH_MONGO_FALLBACK; NONE always requires a local cache at startup.
        private boolean requireLocalCacheAtStartup = true;

        // Only applies when startup waits; expiry fails startup, zero waits indefinitely.
        private Duration initialSnapshotTimeout = Duration.ofSeconds(15);

        // Re-check of the ZooKeeper activated head. Zero disables it.
        private Duration reconcileInterval = Duration.ofSeconds(60);

        // Random initial offset of the periodic activated-head check.
        private Duration headCheckJitter = Duration.ofSeconds(10);

        // Maximum random delay before loading a snapshot for prepared preloads and reconnects.
        private Duration snapshotSyncJitter = Duration.ofSeconds(10);

        private ZooKeeperProperties zooKeeper = new ZooKeeperProperties();

        /** @deprecated use {@code head-check-jitter}; set only one of both names */
        @Deprecated
        @DeprecatedConfigurationProperty(replacement = "horizon.cache.local-subscription-cache.head-check-jitter")
        public Duration getMongoHeadPollJitter() {
            return headCheckJitter;
        }

        /** @deprecated use {@code head-check-jitter}; set only one of both names */
        @Deprecated
        public void setMongoHeadPollJitter(Duration mongoHeadPollJitter) {
            this.headCheckJitter = mongoHeadPollJitter;
        }

        /** @deprecated use {@code snapshot-sync-jitter}; set only one of both names */
        @Deprecated
        @DeprecatedConfigurationProperty(replacement = "horizon.cache.local-subscription-cache.snapshot-sync-jitter")
        public Duration getMongoSnapshotSyncJitter() {
            return snapshotSyncJitter;
        }

        /** @deprecated use {@code snapshot-sync-jitter}; set only one of both names */
        @Deprecated
        public void setMongoSnapshotSyncJitter(Duration mongoSnapshotSyncJitter) {
            this.snapshotSyncJitter = mongoSnapshotSyncJitter;
        }
    }

    /** ZooKeeper connection settings and ZNode paths for subscription snapshot heads. */
    @Getter
    @Setter
    public static class ZooKeeperProperties {

        // Can be disabled locally when the ZooKeeper-published addresses are not reachable.
        private boolean ensembleTrackerEnabled = true;

        private String connectString;

        private String preparedPath;

        private String activatedPath;

        private Duration connectionTimeout = Duration.ofSeconds(5);

        private Duration sessionTimeout = Duration.ofSeconds(30);

        /** @deprecated use {@code activated-path}; set only one of both names */
        @Deprecated
        @DeprecatedConfigurationProperty(
            replacement = "horizon.cache.local-subscription-cache.zoo-keeper.activated-path")
        public String getActivatePath() {
            return activatedPath;
        }

        /** @deprecated use {@code activated-path}; set only one of both names */
        @Deprecated
        public void setActivatePath(String activatePath) {
            this.activatedPath = activatePath;
        }
    }

    /** Selects the read source used when the local snapshot cannot serve requests. */
    public enum LocalSubscriptionCacheFallback {
        HAZELCAST_WITH_MONGO_FALLBACK,
        NONE
    }

    @Getter
    @Setter
    public static class DeDuplicationProperties {

        private boolean enabled = false;

        private String defaultCacheName = "deduplication";

        private long ttlInSeconds = 0;

        private long maxIdleInSeconds = 1800; // 30 minutes
    }

}
