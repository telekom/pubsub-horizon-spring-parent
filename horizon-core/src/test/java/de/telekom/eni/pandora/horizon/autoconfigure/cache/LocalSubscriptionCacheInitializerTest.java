// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalSubscriptionCacheInitializerTest {

    private final LocalSubscriptionCache cache = mock(LocalSubscriptionCache.class);
    private final CacheProperties cacheProperties = new CacheProperties();
    private final LocalSubscriptionCacheInitializer initializer =
            new LocalSubscriptionCacheInitializer(cache, cacheProperties);

    @Test
    void shouldPrepareAndActivateCacheBeforeReportingUp() {
        assertEquals(Status.DOWN, initializer.health().getStatus());

        initializer.run(null);

        var ordered = inOrder(cache);
        ordered.verify(cache).prepare();
        ordered.verify(cache).activate();
        assertEquals(Status.UP, initializer.health().getStatus());
    }

    @Test
    void shouldReportDownWhenCacheInitializationFails() {
        doThrow(new IllegalStateException("Mongo unavailable")).when(cache).prepare();

        initializer.run(null);

        assertEquals(Status.DOWN, initializer.health().getStatus());
        verify(cache, never()).activate();
    }

    @Test
    void shouldActivateChangedSnapshotDuringPolling() {
        var snapshotHead = new SubscriptionSnapshotHead();
        when(cache.readSnapshotHead()).thenReturn(snapshotHead);
        when(cache.prepare(snapshotHead)).thenReturn(true);

        initializer.pollHead();

        verify(cache).activate();
        assertEquals(Status.UP, initializer.health().getStatus());
    }

    @Test
    void shouldNotActivateUnchangedSnapshotDuringPolling() {
        var snapshotHead = new SubscriptionSnapshotHead();
        when(cache.readSnapshotHead()).thenReturn(snapshotHead);
        when(cache.prepare(snapshotHead)).thenReturn(false);

        initializer.pollHead();

        verify(cache, never()).activate();
    }
}