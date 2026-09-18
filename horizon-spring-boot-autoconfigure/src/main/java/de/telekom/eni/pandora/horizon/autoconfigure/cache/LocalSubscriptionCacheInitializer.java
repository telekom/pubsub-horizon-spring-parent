// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
/**
 * Initializes and optionally refreshes the local subscription cache.
 *
 * <p>The initializer prepares and activates the first snapshot during startup.
 * If a fallback is configured, initialization failures are logged and the
 * application can continue using the fallback cache. Without a fallback, the
 * initialization exception is propagated and startup fails.</p>
 */
public class LocalSubscriptionCacheInitializer implements ApplicationRunner, HealthIndicator {

    private final LocalSubscriptionCache localSubscriptionCache;
    private final CacheProperties cacheProperties;
    private final AtomicBoolean initialized = new AtomicBoolean();
    private ScheduledExecutorService headPollingExecutor;

    /**
     * Creates an initializer for the local cache and its polling configuration.
     *
     * @param localSubscriptionCache cache to initialize and refresh
     * @param cacheProperties cache and fallback configuration
     */
    public LocalSubscriptionCacheInitializer(LocalSubscriptionCache localSubscriptionCache,
                                             CacheProperties cacheProperties) {
        this.localSubscriptionCache = localSubscriptionCache;
        this.cacheProperties = cacheProperties;
    }

    @Override
    /**
     * Loads and activates the initial local subscription snapshot.
     *
     * @param args application startup arguments
     * @throws RuntimeException if initialization fails and no fallback is configured
     */
    public void run(ApplicationArguments args) {
        try {
            localSubscriptionCache.prepare();
            localSubscriptionCache.activate();
            initialized.set(true);
        } catch (RuntimeException exception) {
            initialized.set(false);
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
     * Checks for a newer snapshot head and activates it when loading succeeds.
     * A polling failure leaves the currently active snapshot unchanged.
     */
    void pollHead() {
        try {
            var snapshotHead = localSubscriptionCache.readSnapshotHead();
            if (localSubscriptionCache.prepareFromSubscriptionSnapshot(snapshotHead)) {
                localSubscriptionCache.activate();
                initialized.set(true);
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

    @PreDestroy
    /** Stops the background snapshot-head polling executor. */
    void stopHeadPolling() {
        if (headPollingExecutor != null) {
            headPollingExecutor.shutdownNow();
        }
    }

    @Override
    /**
     * Reports whether the local cache was initialized successfully.
     *
     * @return {@code UP} after successful initialization, otherwise {@code DOWN}
     */
    public Health health() {
        if (initialized.get()) {
            return Health.up().build();
        }
        return Health.down().withDetail("reason", "Local subscription cache is not initialized").build();
    }
}