// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalSubscriptionCacheMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    private final LocalSubscriptionCache cache = mock(LocalSubscriptionCache.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void exposesStaleLaggingCacheState() {
        when(cache.diagnostics()).thenReturn(new LocalSubscriptionCache.Diagnostics(
            LocalSubscriptionCache.Status.STALE, false, "snapshot-local", 3,
            NOW.minusSeconds(600), "snapshot-new", "snapshot-new", NOW.minusSeconds(90)));
        new LocalSubscriptionCacheMetrics(cache, Clock.fixed(NOW, ZoneOffset.UTC)).bindTo(registry);

        assertEquals(1, gauge("status", "STALE"));
        assertEquals(0, gauge("status", "FRESH"));
        assertEquals(0, gauge("status", "UNINITIALIZED"));
        assertEquals(0, gauge("local.reads"));
        assertEquals(3, gauge("subscriptions"));
        assertEquals(90, gauge("stale.seconds"));
        assertEquals(NOW.minusSeconds(600).getEpochSecond(), gauge("last.activation.timestamp.seconds"));
        assertEquals(1, gauge("snapshot.behind"));
    }

    @Test
    void exposesFreshCurrentCacheState() {
        when(cache.diagnostics()).thenReturn(new LocalSubscriptionCache.Diagnostics(
            LocalSubscriptionCache.Status.FRESH, true, "snapshot-local", 3, NOW, "snapshot-local", null, null));
        new LocalSubscriptionCacheMetrics(cache, Clock.fixed(NOW, ZoneOffset.UTC)).bindTo(registry);

        assertEquals(1, gauge("status", "FRESH"));
        assertEquals(1, gauge("local.reads"));
        assertEquals(0, gauge("stale.seconds"));
        assertEquals(0, gauge("snapshot.behind"));
    }

    private double gauge(String name) {
        return registry.get(LocalSubscriptionCacheMetrics.PREFIX + "." + name).gauge().value();
    }

    private double gauge(String name, String status) {
        return registry.get(LocalSubscriptionCacheMetrics.PREFIX + "." + name).tag("status", status).gauge().value();
    }
}
