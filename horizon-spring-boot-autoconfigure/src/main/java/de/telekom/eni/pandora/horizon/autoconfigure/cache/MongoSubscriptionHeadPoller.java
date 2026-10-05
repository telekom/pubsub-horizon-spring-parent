// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the local cache in sync with the MongoDB head when ZooKeeper is disabled.
 */
@Slf4j
public class MongoSubscriptionHeadPoller implements AutoCloseable {

    private final MongoSubscriptionHeadReconciler reconciler;
    private final long pollIntervalMillis;
    private final long pollJitterMillis;
    private final ScheduledExecutorService executor;

    /**
     * Creates a poller with its own daemon scheduler.
     *
     * @param reconciler reconciler that activates the MongoDB head
     * @param pollInterval interval between polls; zero disables periodic polling
     * @param pollJitter maximum random initial offset of the periodic polls
     */
    public MongoSubscriptionHeadPoller(MongoSubscriptionHeadReconciler reconciler, Duration pollInterval,
                                       Duration pollJitter) {
        this(reconciler, pollInterval, pollJitter, Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "subscription-head-poller");
            thread.setDaemon(true);
            return thread;
        }));
    }

    MongoSubscriptionHeadPoller(MongoSubscriptionHeadReconciler reconciler, Duration pollInterval,
                                Duration pollJitter, ScheduledExecutorService executor) {
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler must not be null");
        this.pollIntervalMillis = nonNegativeMillis(pollInterval, "reconcile interval");
        this.pollJitterMillis = nonNegativeMillis(pollJitter, "MongoDB head poll jitter");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    /**
     * Polls the MongoDB head once immediately and then periodically.
     */
    public void start() {
        executor.execute(this::poll);
        if (pollIntervalMillis > 0) {
            // The random first delay spreads periodic head reads of all pods; later runs keep the fixed interval.
            var jitter = pollJitterMillis == 0 ? 0 : ThreadLocalRandom.current().nextLong(pollJitterMillis + 1);
            executor.scheduleWithFixedDelay(this::poll, pollIntervalMillis + jitter, pollIntervalMillis,
                TimeUnit.MILLISECONDS);
        }
    }

    private void poll() {
        try {
            reconciler.reconcile();
        } catch (RuntimeException exception) {
            log.warn("MongoDB subscription head poll failed", exception);
        }
    }

    private static long nonNegativeMillis(Duration duration, String name) {
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative or null");
        }
        return duration.toMillis();
    }

    /** Stops polling. */
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
