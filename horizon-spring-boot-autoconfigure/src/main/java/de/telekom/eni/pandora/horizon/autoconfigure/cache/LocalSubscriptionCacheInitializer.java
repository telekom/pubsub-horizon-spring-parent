// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.cache.service.SubscriptionCacheReader;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.dao.DataAccessException;

/**
 * Initializes the local subscription cache.
 *
 * <p>The initializer prepares and activates the published snapshot during startup.
 * If a fallback is configured, snapshot initialization failures are logged and the
 * application can continue using the fallback reader. Without a fallback, a snapshot
 * initialization failure is propagated and startup fails.</p>
 */
@Slf4j
public class LocalSubscriptionCacheInitializer implements ApplicationRunner, HealthIndicator {

    private final LocalSubscriptionCache localSubscriptionCache;
    private final ObjectProvider<SubscriptionCacheReader> subscriptionCacheReaderProvider;
    private final CacheProperties cacheProperties;
    /**
        * Creates an initializer for the local cache.
     *
     * @param localSubscriptionCache cache to initialize and refresh
     * @param subscriptionCacheReaderProvider provider for the effective reader including fallback
     * @param cacheProperties cache and fallback configuration
     */
    public LocalSubscriptionCacheInitializer(LocalSubscriptionCache localSubscriptionCache,
                                             ObjectProvider<SubscriptionCacheReader> subscriptionCacheReaderProvider,
                                             CacheProperties cacheProperties) {
        this.localSubscriptionCache = localSubscriptionCache;
        this.subscriptionCacheReaderProvider = subscriptionCacheReaderProvider;
        this.cacheProperties = cacheProperties;
    }

    /**
    * Loads and activates the published subscription snapshot.
     *
     * @param args application startup arguments
    * @throws RuntimeException if snapshot initialization fails without a configured fallback
     */
    @Override
    public void run(ApplicationArguments args) {
        var localCache = cacheProperties.getLocalSubscriptionCache();
        if (localCache.getZooKeeper().isEnabled()) {
            log.info("ZooKeeper subscription head watcher owns local cache initialization");
            return;
        }
        try {
            var snapshotHead = localSubscriptionCache.readSnapshotHead();
            localSubscriptionCache.prepare(snapshotHead);
            localSubscriptionCache.activate(snapshotHead);
            log.info("Local subscription cache initialized successfully: snapshotId={}, fallbackMode={}",
                snapshotHead.getSnapshotId(), localCache.getFallbackMode());
        } catch (SubscriptionCacheSnapshotException | DataAccessException exception) {
            if (cacheProperties.getLocalSubscriptionCache().getFallbackMode()
                    == CacheProperties.LocalSubscriptionCacheFallback.NONE) {
                throw exception;
            }
            log.warn("Local subscription cache initialization failed; using fallback cache", exception);
        }
    }

    /**
    * Reports whether the local cache or its configured fallback reader can serve requests.
     *
     * @return {@code UP} when the local cache or fallback is ready, otherwise {@code DOWN}
     */
    @Override
    public Health health() {
        if (cacheProperties.getLocalSubscriptionCache().getZooKeeper().isEnabled()) {
            var reader = subscriptionCacheReaderProvider.getIfAvailable();
            if (reader != null && reader.isReady()) {
                var source = localSubscriptionCache.canServeLocalReads() ? "local" : "fallback";
                return withCacheDetails(Health.up(), source).build();
            }
            return withCacheDetails(Health.down(), "unavailable")
                    .withDetail("reason", "No subscription cache reader is ready")
                    .build();
        }
        if (localSubscriptionCache.isInitialized()) {
            return withCacheDetails(Health.up(), "local").build();
        }
        if (cacheProperties.getLocalSubscriptionCache().getFallbackMode()
                != CacheProperties.LocalSubscriptionCacheFallback.NONE) {
            var subscriptionCacheReader = subscriptionCacheReaderProvider.getIfAvailable();
            if (subscriptionCacheReader != null && subscriptionCacheReader.isReady()) {
                return withCacheDetails(Health.up(), "fallback")
                    .withDetail("localCache", "not initialized")
                    .build();
            }
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