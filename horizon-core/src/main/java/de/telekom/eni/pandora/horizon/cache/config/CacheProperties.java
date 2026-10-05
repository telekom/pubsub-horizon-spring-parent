// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

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

    @Getter
    @Setter
    public static class LocalSubscriptionCacheProperties {

        private boolean enabled = false;

        // NONE never reads Hazelcast and serves stale local entries indefinitely if necessary.
        private LocalSubscriptionCacheFallback fallbackMode = LocalSubscriptionCacheFallback.HAZELCAST_WITH_MONGO_FALLBACK;

        // Use the MongoDB head when the ZooKeeper activated head cannot be determined.
        private boolean mongoHeadFallbackEnabled = true;

        private String snapshotCollection = "subscriptions.subscriber.horizon.telekom.de.v1-snapshots";

        private String headCollection = "subscriptions.subscriber.horizon.telekom.de.v1-head";

        // Only applies to HAZELCAST_WITH_MONGO_FALLBACK.
        private Duration staleLocalCacheReadGracePeriod = Duration.ofSeconds(120);

        // Only applies to HAZELCAST_WITH_MONGO_FALLBACK; NONE always requires a local cache at startup.
        private boolean requireLocalCacheAtStartup = true;

        // Only applies when startup waits; expiry fails startup, zero waits indefinitely.
        private Duration initialSnapshotTimeout = Duration.ofSeconds(120);

        // Re-check of the active head: ZooKeeper, or MongoDB when ZooKeeper is disabled or disconnected. Zero disables it.
        private Duration reconcileInterval = Duration.ofSeconds(60);

        // Random initial offset of the periodic head reconciliation.
        private Duration mongoHeadPollJitter = Duration.ofSeconds(10);

        // Maximum random delay before loading a snapshot for prepared preloads and reconnects (ZooKeeper mode only).
        private Duration mongoSnapshotSyncJitter = Duration.ofSeconds(10);

        private ZooKeeperProperties zooKeeper = new ZooKeeperProperties();
    }

    @Getter
    @Setter
    public static class ZooKeeperProperties {

        // When disabled, the MongoDB head is the only head source and is polled periodically.
        private boolean enabled = true;

        // Can be disabled locally when the ZooKeeper-published addresses are not reachable.
        private boolean ensembleTrackerEnabled = true;

        private String connectString;

        private String preparedPath;

        private String activatePath;

        private Duration connectionTimeout = Duration.ofSeconds(15);

        private Duration sessionTimeout = Duration.ofSeconds(60);
    }

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
