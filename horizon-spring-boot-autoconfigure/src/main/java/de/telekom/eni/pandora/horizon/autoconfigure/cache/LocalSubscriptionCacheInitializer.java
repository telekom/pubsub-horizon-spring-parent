// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.cache.service.SubscriptionCacheReader;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.dao.DataAccessException;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Initializes and optionally refreshes the local subscription cache.
 *
 * <p>The initializer prepares and activates the published snapshot during startup.
 * If a fallback is configured, snapshot initialization failures are logged and the
 * application can continue using the fallback reader. When polling is enabled, later
 * polling attempts can initialize or refresh the local cache. Without a fallback, a
 * snapshot initialization failure is propagated and startup fails.</p>
 */
@Slf4j
public class LocalSubscriptionCacheInitializer implements ApplicationRunner, HealthIndicator {

    private final LocalSubscriptionCache localSubscriptionCache;
    private final ObjectProvider<SubscriptionCacheReader> subscriptionCacheReaderProvider;
    private final CacheProperties cacheProperties;
    private ScheduledExecutorService headPollingExecutor;

    /**
     * Creates an initializer for the local cache and its polling configuration.
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
    * Loads and activates the published subscription snapshot, then starts optional
    * snapshot-head polling. With a configured fallback, a snapshot initialization
    * failure does not prevent polling from starting.
     *
     * @param args application startup arguments
    * @throws RuntimeException if snapshot initialization fails without a configured fallback,
    *                          or if polling cannot be configured
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            var snapshotHead = localSubscriptionCache.readSnapshotHead();
            localSubscriptionCache.prepare(snapshotHead);
            localSubscriptionCache.activate(snapshotHead.getSnapshotId());
        } catch (SubscriptionCacheSnapshotException | DataAccessException exception) {
            if (cacheProperties.getLocalSubscriptionCache().getFallbackMode()
                    == CacheProperties.LocalSubscriptionCacheFallback.NONE) {
                throw exception;
            }
            log.warn("Local subscription cache initialization failed; using fallback cache", exception);
        }
        startHeadPolling();
    }

    private void startHeadPolling() {
        var polling = cacheProperties.getLocalSubscriptionCache().getHeadPolling();
        if (!polling.isEnabled()) {
            return;
        }
        validatePollingInterval(polling.getInterval());
        headPollingExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "subscription-cache-head-poller");
            thread.setDaemon(true);
            return thread;
        });
        var intervalMillis = polling.getInterval().toMillis();
        headPollingExecutor.scheduleWithFixedDelay(
            this::pollHead, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /**
    * Reads the published snapshot head and activates it when its ID differs from the
    * active snapshot. A polling failure leaves an existing active snapshot unchanged;
    * if no snapshot is active yet, the next polling run retries initialization.
     */
    void pollHead() {
        try {
            var snapshotHead = localSubscriptionCache.readSnapshotHead();
            localSubscriptionCache.prepare(snapshotHead);
            if (localSubscriptionCache.hasPendingSnapshot()) {
                localSubscriptionCache.activate(snapshotHead.getSnapshotId());
            }
        } catch (RuntimeException exception) {
            log.warn("Subscription cache head polling failed; keeping active snapshot", exception);
        }
    }

    private static void validatePollingInterval(Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("Subscription cache head polling interval must be positive");
        }
    }

    /** Stops the background snapshot-head polling executor. */
    @PreDestroy
    void stopHeadPolling() {
        if (headPollingExecutor != null) {
            headPollingExecutor.shutdownNow();
        }
    }

    /**
    * Reports whether the local cache or its configured fallback reader can serve requests.
     *
     * @return {@code UP} when the local cache or fallback is ready, otherwise {@code DOWN}
     */
    @Override
    public Health health() {
        if (localSubscriptionCache.isReady()) {
            return Health.up().withDetail("source", "local").build();
        }
        if (cacheProperties.getLocalSubscriptionCache().getFallbackMode()
                != CacheProperties.LocalSubscriptionCacheFallback.NONE) {
            var subscriptionCacheReader = subscriptionCacheReaderProvider.getIfAvailable();
            if (subscriptionCacheReader != null && subscriptionCacheReader.isReady()) {
                return Health.up()
                        .withDetail("source", "fallback")
                        .withDetail("localCache", "not initialized")
                        .build();
            }
        }
        return Health.down()
                .withDetail("reason", "No subscription cache reader is ready")
                .build();
    }
}