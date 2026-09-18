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

        private LocalSubscriptionCacheFallback fallbackMode = LocalSubscriptionCacheFallback.SHARED_CACHE_MONGO;

        private String snapshotCollection = "subscriptions.subscriber.horizon.telekom.de.v1-snapshots";

        private String headCollection = "subscriptions.subscriber.horizon.telekom.de.v1-head";

        private HeadPollingProperties headPolling = new HeadPollingProperties();
    }

    @Getter
    @Setter
    public static class HeadPollingProperties {

        private boolean enabled = false;

        private Duration interval = Duration.ofSeconds(30);
    }

    public enum LocalSubscriptionCacheFallback {
        SHARED_CACHE_MONGO,
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
