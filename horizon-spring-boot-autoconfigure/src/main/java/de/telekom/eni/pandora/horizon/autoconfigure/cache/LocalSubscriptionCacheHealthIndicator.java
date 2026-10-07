// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.cache.service.SubscriptionCacheReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

/**
 * Reports the effective subscription cache source and the state of the local cache. Always {@code UP} so that the
 * aggregate health and therefore the pod probes behave exactly as without the local cache.
 */
public class LocalSubscriptionCacheHealthIndicator implements HealthIndicator {

    private static final String NONE = "none";

    private final LocalSubscriptionCache localSubscriptionCache;
    private final ObjectProvider<SubscriptionCacheReader> subscriptionCacheReaderProvider;
    private final CacheProperties.LocalSubscriptionCacheProperties properties;
    private final Clock clock;

    /**
     * Creates a health indicator for the local cache.
     *
     * @param localSubscriptionCache local cache whose state is reported
     * @param subscriptionCacheReaderProvider provider for the effective reader including fallback
     * @param properties local cache configuration shown in the health details
     */
    public LocalSubscriptionCacheHealthIndicator(LocalSubscriptionCache localSubscriptionCache,
                                                 ObjectProvider<SubscriptionCacheReader> subscriptionCacheReaderProvider,
                                                 CacheProperties.LocalSubscriptionCacheProperties properties) {
        this(localSubscriptionCache, subscriptionCacheReaderProvider, properties, Clock.systemUTC());
    }

    LocalSubscriptionCacheHealthIndicator(LocalSubscriptionCache localSubscriptionCache,
                                          ObjectProvider<SubscriptionCacheReader> subscriptionCacheReaderProvider,
                                          CacheProperties.LocalSubscriptionCacheProperties properties, Clock clock) {
        this.localSubscriptionCache = localSubscriptionCache;
        this.subscriptionCacheReaderProvider = subscriptionCacheReaderProvider;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Reports whether the local cache or its configured fallback reader can serve requests.
     *
     * @return always {@code UP}; the effective source is reported as detail
     */
    @Override
    public Health health() {
        var diagnostics = localSubscriptionCache.diagnostics();
        var reader = subscriptionCacheReaderProvider.getIfAvailable();
        if (reader != null && reader.isReady()) {
            var source = diagnostics.localReadsAllowed() ? "local" : "fallback";
            return withCacheDetails(Health.up(), source, diagnostics).build();
        }
        return withCacheDetails(Health.up(), "unavailable", diagnostics)
            .withDetail("reason", "No subscription cache reader is ready")
            .build();
    }

    private Health.Builder withCacheDetails(Health.Builder builder, String source,
                                            LocalSubscriptionCache.Diagnostics diagnostics) {
        var fallbackMode = properties.getFallbackMode();
        builder
            .withDetail("source", source)
            .withDetail("fallbackMode", fallbackMode.name().toLowerCase(Locale.ROOT).replace('_', '-'))
            .withDetail("headSource", properties.getZooKeeper().isEnabled() ? "zookeeper" : "mongodb")
            .withDetail("cacheStatus", diagnostics.status().name())
            .withDetail("localSnapshotId", orNone(diagnostics.activeSnapshotId()))
            .withDetail("subscriptionCount", diagnostics.subscriptionCount())
            .withDetail("activatedAt", orNone(diagnostics.activatedAt()))
            .withDetail("expectedSnapshotId", orNone(diagnostics.expectedSnapshotId()))
            .withDetail("pendingSnapshotId", orNone(diagnostics.pendingSnapshotId()));
        var staleSince = diagnostics.staleSince();
        if (staleSince != null) {
            builder.withDetail("staleSince", staleSince.toString());
            if (fallbackMode == CacheProperties.LocalSubscriptionCacheFallback.HAZELCAST_WITH_MONGO_FALLBACK) {
                var remaining = properties.getStaleLocalCacheReadGracePeriod()
                    .minus(Duration.between(staleSince, clock.instant()));
                builder.withDetail("staleLocalReadsRemaining",
                    (remaining.isNegative() ? Duration.ZERO : remaining).toString());
            }
        }
        return builder;
    }

    private static String orNone(Object value) {
        return value == null ? NONE : value.toString();
    }
}