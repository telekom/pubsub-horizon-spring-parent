// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

/**
 * Exposes the state of the local subscription cache as meters. All values are read from memory at scrape time.
 */
public class LocalSubscriptionCacheMetrics implements MeterBinder {

    static final String PREFIX = "horizon.local.subscription.cache";
    static final String FALLBACK_READS = PREFIX + ".fallback.reads";
    static final String FAILURES = PREFIX + ".failures";
    static final String FAILURES_DESCRIPTION =
        "Failed snapshot activations (including failed snapshot loads) and unusable ZooKeeper activated heads";

    private final LocalSubscriptionCache cache;
    private final Clock clock;

    /**
     * Creates the metrics binder.
     *
     * @param cache local cache whose state is exposed
     */
    public LocalSubscriptionCacheMetrics(LocalSubscriptionCache cache) {
        this(cache, Clock.systemUTC());
    }

    LocalSubscriptionCacheMetrics(LocalSubscriptionCache cache, Clock clock) {
        this.cache = cache;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(PREFIX + ".state", "Local cache status: 0 = UNINITIALIZED, 1 = STALE, 2 = FRESH",
            diagnostics -> stateValue(diagnostics.status())).register(registry);
        gauge(PREFIX + ".local.reads", "1 if reads are served locally, 0 if the fallback is used",
            diagnostics -> diagnostics.localReadsAllowed() ? 1 : 0).register(registry);
        gauge(PREFIX + ".subscriptions", "Number of subscriptions in the active local snapshot",
            LocalSubscriptionCache.Diagnostics::subscriptionCount).register(registry);
        gauge(PREFIX + ".stale.seconds", "Seconds since the local cache became stale, 0 if not stale",
            this::staleSeconds).register(registry);
        gauge(PREFIX + ".last.activation.timestamp.seconds",
            "Epoch seconds of the last successful snapshot activation, 0 if none",
            diagnostics -> diagnostics.activatedAt() == null ? 0 : diagnostics.activatedAt().getEpochSecond())
            .register(registry);
        Gauge.builder(PREFIX + ".snapshot.behind", cache, c -> c.isBehindExpectedHead() ? 1 : 0)
            .description("1 if the active snapshot differs from the expected head")
            .strongReference(true)
            .register(registry);
        FunctionCounter.builder(FAILURES, cache, LocalSubscriptionCache::getActivationFailureCount)
            .description(FAILURES_DESCRIPTION)
            .tag("reason", "activation")
            .register(registry);
        FunctionTimer.builder(PREFIX + ".snapshot.load", cache,
                LocalSubscriptionCache::getSnapshotLoadCount, LocalSubscriptionCache::getSnapshotLoadTotalNanos,
                TimeUnit.NANOSECONDS)
            .description("Successful snapshot loads from MongoDB")
            .register(registry);
        Gauge.builder(PREFIX + ".snapshot.load.last.seconds", cache, c -> c.getLastSnapshotLoadNanos() / 1e9)
            .description("Duration of the last successful snapshot load, 0 if none")
            .strongReference(true)
            .register(registry);
    }

    /** Creates a gauge backed by a value from the cache diagnostics. */
    private Gauge.Builder<LocalSubscriptionCache> gauge(String name, String description,
                                                        ToDoubleFunction<LocalSubscriptionCache.Diagnostics> value) {
        return Gauge.builder(name, cache, c -> value.applyAsDouble(c.getDiagnostics()))
            .description(description)
            .strongReference(true);
    }

    /** Maps cache statuses to stable numeric metric values. */
    private static int stateValue(LocalSubscriptionCache.Status status) {
        return switch (status) {
            case UNINITIALIZED -> 0;
            case STALE -> 1;
            case FRESH -> 2;
        };
    }

    /** Returns elapsed stale time in seconds, or zero when the cache is not stale. */
    private double staleSeconds(LocalSubscriptionCache.Diagnostics diagnostics) {
        var staleSince = diagnostics.staleSince();
        return staleSince == null ? 0 : Duration.between(staleSince, clock.instant()).toMillis() / 1000.0;
    }
}
