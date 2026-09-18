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
public class LocalSubscriptionCacheInitializer implements ApplicationRunner, HealthIndicator {

    private final LocalSubscriptionCache localSubscriptionCache;
    private final CacheProperties cacheProperties;
    private final AtomicBoolean initialized = new AtomicBoolean();
    private ScheduledExecutorService headPollingExecutor;

    public LocalSubscriptionCacheInitializer(LocalSubscriptionCache localSubscriptionCache,
                                             CacheProperties cacheProperties) {
        this.localSubscriptionCache = localSubscriptionCache;
        this.cacheProperties = cacheProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            localSubscriptionCache.prepare();
            localSubscriptionCache.activate();
            initialized.set(true);
        } catch (RuntimeException exception) {
            initialized.set(false);
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

    void pollHead() {
        try {
            var snapshotHead = localSubscriptionCache.readSnapshotHead();
            if (localSubscriptionCache.prepare(snapshotHead)) {
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
    void stopHeadPolling() {
        if (headPollingExecutor != null) {
            headPollingExecutor.shutdownNow();
        }
    }

    @Override
    public Health health() {
        if (initialized.get()) {
            return Health.up().build();
        }
        return Health.down().withDetail("reason", "Local subscription cache is not initialized").build();
    }
}