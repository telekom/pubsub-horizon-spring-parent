// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.cache.service.SubscriptionCacheReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Status;

import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalSubscriptionCacheInitializerTest {

    private final LocalSubscriptionCache cache = mock(LocalSubscriptionCache.class);
    private final SubscriptionCacheReader subscriptionCacheReader = mock(SubscriptionCacheReader.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SubscriptionCacheReader> subscriptionCacheReaderProvider = mock(ObjectProvider.class);
    private final LocalSubscriptionCacheInitializer initializer =
            new LocalSubscriptionCacheInitializer(cache, subscriptionCacheReaderProvider);

    @BeforeEach
    void setDefaultCacheDiagnostics() {
        when(cache.localSnapshotId()).thenReturn(Optional.empty());
        when(cache.status()).thenReturn(LocalSubscriptionCache.Status.UNINITIALIZED);
    }

    @Test
    void healthRequiresEffectiveReaderEvenWhenOldSnapshotExists() {
        when(cache.isInitialized()).thenReturn(true);
        when(cache.localSnapshotId()).thenReturn(Optional.of("snapshot-local"));
        when(cache.status()).thenReturn(LocalSubscriptionCache.Status.STALE);
        when(subscriptionCacheReaderProvider.getIfAvailable()).thenReturn(subscriptionCacheReader);
        when(subscriptionCacheReader.isReady()).thenReturn(false, true);

        assertEquals(Status.DOWN, initializer.health().getStatus());
        var health = initializer.health();
        assertEquals(Status.UP, health.getStatus());
        assertEquals("fallback", health.getDetails().get("source"));
        assertEquals("snapshot-local", health.getDetails().get("localSnapshotId"));
        assertEquals("STALE", health.getDetails().get("cacheStatus"));
        assertEquals(false, health.getDetails().get("localReadsAllowed"));
        assertEquals(List.of("source", "localSnapshotId", "cacheStatus", "localReadsAllowed"),
            List.copyOf(health.getDetails().keySet()));
    }

    @Test
    void healthReportsLocalSourceWhenLocalReadsAreAllowed() {
        when(cache.canServeLocalReads()).thenReturn(true);
        when(cache.status()).thenReturn(LocalSubscriptionCache.Status.FRESH);
        when(subscriptionCacheReaderProvider.getIfAvailable()).thenReturn(subscriptionCacheReader);
        when(subscriptionCacheReader.isReady()).thenReturn(true);

        var health = initializer.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("local", health.getDetails().get("source"));
    }

    @Test
    void healthIsDownWithoutReader() {
        when(subscriptionCacheReaderProvider.getIfAvailable()).thenReturn(null);

        var health = initializer.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("unavailable", health.getDetails().get("source"));
    }
}