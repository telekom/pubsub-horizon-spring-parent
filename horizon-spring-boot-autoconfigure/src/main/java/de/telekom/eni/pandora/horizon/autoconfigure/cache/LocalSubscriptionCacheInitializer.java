// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.cache.service.SubscriptionCacheReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Reports the effective subscription cache source. The head source (ZooKeeper watcher or MongoDB poller)
 * initializes the local cache; the startup barrier in the auto-configuration waits for it.
 */
public class LocalSubscriptionCacheInitializer implements HealthIndicator {

    private final LocalSubscriptionCache localSubscriptionCache;
    private final ObjectProvider<SubscriptionCacheReader> subscriptionCacheReaderProvider;

    /**
     * Creates a health indicator for the local cache.
     *
     * @param localSubscriptionCache local cache whose state is reported
     * @param subscriptionCacheReaderProvider provider for the effective reader including fallback
     */
    public LocalSubscriptionCacheInitializer(LocalSubscriptionCache localSubscriptionCache,
                                             ObjectProvider<SubscriptionCacheReader> subscriptionCacheReaderProvider) {
        this.localSubscriptionCache = localSubscriptionCache;
        this.subscriptionCacheReaderProvider = subscriptionCacheReaderProvider;
    }

    /**
     * Reports whether the local cache or its configured fallback reader can serve requests.
     *
     * @return {@code UP} when the effective reader is ready, otherwise {@code DOWN}
     */
    @Override
    public Health health() {
        var reader = subscriptionCacheReaderProvider.getIfAvailable();
        if (reader != null && reader.isReady()) {
            var source = localSubscriptionCache.canServeLocalReads() ? "local" : "fallback";
            return withCacheDetails(Health.up(), source).build();
        }
        return withCacheDetails(Health.down(), "unavailable")
            .withDetail("reason", "No subscription cache reader is ready")
            .build();
    }

    private Health.Builder withCacheDetails(Health.Builder builder, String source) {
        var cacheStatus = localSubscriptionCache.status();
        return builder
            .withDetail("source", source)
            .withDetail("localSnapshotId", localSubscriptionCache.localSnapshotId().orElse("none"))
            .withDetail("cacheStatus", cacheStatus == null ? "UNKNOWN" : cacheStatus.name())
            .withDetail("localReadsAllowed", localSubscriptionCache.canServeLocalReads());
    }
}