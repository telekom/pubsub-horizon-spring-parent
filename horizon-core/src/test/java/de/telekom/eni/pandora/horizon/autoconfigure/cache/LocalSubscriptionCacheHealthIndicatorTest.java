// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.cache.service.SubscriptionCacheReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalSubscriptionCacheHealthIndicatorTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    private final LocalSubscriptionCache cache = mock(LocalSubscriptionCache.class);
    private final SubscriptionCacheReader subscriptionCacheReader = mock(SubscriptionCacheReader.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SubscriptionCacheReader> subscriptionCacheReaderProvider = mock(ObjectProvider.class);
    private final CacheProperties.LocalSubscriptionCacheProperties properties =
            new CacheProperties.LocalSubscriptionCacheProperties();
    private final LocalSubscriptionCacheHealthIndicator indicator = new LocalSubscriptionCacheHealthIndicator(
            cache, subscriptionCacheReaderProvider, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void healthRequiresEffectiveReaderEvenWhenOldSnapshotExists() {
        when(cache.diagnostics()).thenReturn(new LocalSubscriptionCache.Diagnostics(
                LocalSubscriptionCache.Status.STALE, false, "snapshot-local", 3,
                NOW.minusSeconds(600), "snapshot-new", "snapshot-new", NOW.minusSeconds(150)));
        when(subscriptionCacheReaderProvider.getIfAvailable()).thenReturn(subscriptionCacheReader);
        when(subscriptionCacheReader.isReady()).thenReturn(false, true);

        assertEquals(Status.DOWN, indicator.health().getStatus());
        var health = indicator.health();
        assertEquals(Status.UP, health.getStatus());
        assertEquals("fallback", health.getDetails().get("source"));
        assertEquals("hazelcast-with-mongo-fallback", health.getDetails().get("fallbackMode"));
        assertEquals("zookeeper", health.getDetails().get("headSource"));
        assertEquals("STALE", health.getDetails().get("cacheStatus"));
        assertEquals("snapshot-local", health.getDetails().get("localSnapshotId"));
        assertEquals(3, health.getDetails().get("subscriptionCount"));
        assertEquals("2026-10-06T09:50:00Z", health.getDetails().get("activatedAt"));
        assertEquals("snapshot-new", health.getDetails().get("expectedSnapshotId"));
        assertEquals("snapshot-new", health.getDetails().get("pendingSnapshotId"));
        assertEquals("2026-10-06T09:57:30Z", health.getDetails().get("staleSince"));
        assertEquals("PT0S", health.getDetails().get("staleLocalReadsRemaining"));
        assertEquals(List.of("source", "fallbackMode", "headSource", "cacheStatus", "localSnapshotId",
                        "subscriptionCount", "activatedAt", "expectedSnapshotId", "pendingSnapshotId", "staleSince",
                        "staleLocalReadsRemaining"),
                List.copyOf(health.getDetails().keySet()));
    }

    @Test
    void healthReportsLocalSourceWhenLocalReadsAreAllowed() {
        when(cache.diagnostics()).thenReturn(new LocalSubscriptionCache.Diagnostics(
                LocalSubscriptionCache.Status.FRESH, true, "snapshot-local", 3, NOW, "snapshot-local", null, null));
        when(subscriptionCacheReaderProvider.getIfAvailable()).thenReturn(subscriptionCacheReader);
        when(subscriptionCacheReader.isReady()).thenReturn(true);

        var health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("local", health.getDetails().get("source"));
        assertEquals("none", health.getDetails().get("pendingSnapshotId"));
        assertFalse(health.getDetails().containsKey("staleSince"));
    }

    @Test
    void staleReadGraceIsOmittedWithoutHazelcastFallbackAndHeadSourceReflectsMongoMode() {
        properties.setFallbackMode(CacheProperties.LocalSubscriptionCacheFallback.NONE);
        properties.getZooKeeper().setEnabled(false);
        when(cache.diagnostics()).thenReturn(new LocalSubscriptionCache.Diagnostics(
                LocalSubscriptionCache.Status.STALE, true, "snapshot-local", 3, NOW, null, null, NOW));
        when(subscriptionCacheReaderProvider.getIfAvailable()).thenReturn(subscriptionCacheReader);
        when(subscriptionCacheReader.isReady()).thenReturn(true);

        var health = indicator.health();

        assertEquals("none", health.getDetails().get("fallbackMode"));
        assertEquals("mongodb", health.getDetails().get("headSource"));
        assertEquals("none", health.getDetails().get("expectedSnapshotId"));
        assertFalse(health.getDetails().containsKey("staleLocalReadsRemaining"));
    }

    @Test
    void healthIsDownWithoutReader() {
        when(cache.diagnostics()).thenReturn(new LocalSubscriptionCache.Diagnostics(
                LocalSubscriptionCache.Status.UNINITIALIZED, false, null, 0, null, null, null, null));
        when(subscriptionCacheReaderProvider.getIfAvailable()).thenReturn(null);

        var health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("unavailable", health.getDetails().get("source"));
        assertEquals("none", health.getDetails().get("localSnapshotId"));
    }
}