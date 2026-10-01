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

        private LocalSubscriptionCacheFallback fallbackMode = LocalSubscriptionCacheFallback.HAZELCAST_WITH_MONGO_FALLBACK;

        private String snapshotCollection = "subscriptions.subscriber.horizon.telekom.de.v1-snapshots";

        private String headCollection = "subscriptions.subscriber.horizon.telekom.de.v1-head";

        private ZooKeeperProperties zooKeeper = new ZooKeeperProperties();
    }

    @Getter
    @Setter
    public static class ZooKeeperProperties {

        private boolean enabled = false;

        private boolean ensembleTrackerEnabled = true;

        private String connectString;

        private String preparedPath;

        private String activatePath;

        private Duration connectionTimeout = Duration.ofSeconds(15);

        private Duration sessionTimeout = Duration.ofSeconds(60);

        private Duration reconcileInterval = Duration.ofSeconds(300);
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
