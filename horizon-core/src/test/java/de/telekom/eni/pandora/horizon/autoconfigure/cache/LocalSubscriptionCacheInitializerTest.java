// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.cache.service.SubscriptionCacheReader;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Status;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalSubscriptionCacheInitializerTest {

    private final LocalSubscriptionCache cache = mock(LocalSubscriptionCache.class);
    private final SubscriptionCacheReader subscriptionCacheReader = mock(SubscriptionCacheReader.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SubscriptionCacheReader> subscriptionCacheReaderProvider = mock(ObjectProvider.class);
    private final CacheProperties cacheProperties = new CacheProperties();
    private final LocalSubscriptionCacheInitializer initializer =
            new LocalSubscriptionCacheInitializer(cache, subscriptionCacheReaderProvider, cacheProperties);

    @Test
    void shouldPrepareAndActivateCacheBeforeReportingUp() {
        var snapshotHead = snapshotHead("snapshot-1");
        when(cache.readSnapshotHead()).thenReturn(snapshotHead);
        when(cache.isReady()).thenReturn(false, true);
        assertEquals(Status.DOWN, initializer.health().getStatus());

        initializer.run(null);

        var ordered = inOrder(cache);
        ordered.verify(cache).readSnapshotHead();
        ordered.verify(cache).prepare(snapshotHead);
        ordered.verify(cache).activate(snapshotHead);
        assertEquals(Status.UP, initializer.health().getStatus());
    }

    @Test
    void shouldReportUpWhenFallbackHandlesCacheInitializationFailure() {
        doThrow(new SubscriptionCacheSnapshotException("Snapshot invalid")).when(cache).readSnapshotHead();
        when(subscriptionCacheReaderProvider.getIfAvailable()).thenReturn(subscriptionCacheReader);
        when(subscriptionCacheReader.isReady()).thenReturn(true);

        initializer.run(null);

        assertEquals(Status.UP, initializer.health().getStatus());
        assertEquals("fallback", initializer.health().getDetails().get("source"));
        verify(cache, never()).activate(any(SubscriptionSnapshotHead.class));
    }

    @Test
    void shouldReportDownWhenNeitherLocalCacheNorFallbackIsReady() {
        doThrow(new DataAccessResourceFailureException("Mongo unavailable")).when(cache).readSnapshotHead();
        when(subscriptionCacheReaderProvider.getIfAvailable()).thenReturn(subscriptionCacheReader);
        when(subscriptionCacheReader.isReady()).thenReturn(false);

        initializer.run(null);

        assertEquals(Status.DOWN, initializer.health().getStatus());
    }

    @Test
    void shouldFailStartupWhenCacheInitializationFailsWithoutFallback() {
        cacheProperties.getLocalSubscriptionCache().setFallbackMode(
                CacheProperties.LocalSubscriptionCacheFallback.NONE);
        doThrow(new DataAccessResourceFailureException("Mongo unavailable")).when(cache).readSnapshotHead();

        assertThrows(DataAccessResourceFailureException.class, () -> initializer.run(null));
        assertEquals(Status.DOWN, initializer.health().getStatus());
        verify(cache, never()).activate(any(SubscriptionSnapshotHead.class));
    }

    @Test
    void shouldFailStartupOnUnexpectedRuntimeExceptionDespiteFallback() {
        doThrow(new IllegalStateException("Unexpected cache state")).when(cache).readSnapshotHead();

        assertThrows(IllegalStateException.class, () -> initializer.run(null));
        verify(cache, never()).activate(any(SubscriptionSnapshotHead.class));
    }

    @Test
    void shouldActivateChangedSnapshotDuringPolling() {
        var snapshotHead = new SubscriptionSnapshotHead();
        snapshotHead.setSnapshotId("snapshot-42");
        when(cache.readSnapshotHead()).thenReturn(snapshotHead);
        when(cache.hasPendingSnapshot()).thenReturn(true);
        when(cache.isReady()).thenReturn(true);

        initializer.pollHead();

        verify(cache).prepare(snapshotHead);
        verify(cache).activate(snapshotHead);
        assertEquals(Status.UP, initializer.health().getStatus());
    }

    @Test
    void shouldNotActivateUnchangedSnapshotDuringPolling() {
        var snapshotHead = new SubscriptionSnapshotHead();
        snapshotHead.setSnapshotId("snapshot-42");
        when(cache.readSnapshotHead()).thenReturn(snapshotHead);
        when(cache.hasPendingSnapshot()).thenReturn(false);

        initializer.pollHead();

        verify(cache).prepare(snapshotHead);
        verify(cache, never()).activate(any(SubscriptionSnapshotHead.class));
    }

    @Test
    void shouldRetryPollingAfterFailure() {
        var snapshotHead = snapshotHead("snapshot-42");
        when(cache.readSnapshotHead())
                .thenThrow(new DataAccessResourceFailureException("Mongo unavailable"))
                .thenReturn(snapshotHead);
        when(cache.hasPendingSnapshot()).thenReturn(true);

        initializer.pollHead();
        initializer.pollHead();

        verify(cache).prepare(snapshotHead);
        verify(cache).activate(snapshotHead);
    }

    private SubscriptionSnapshotHead snapshotHead(String snapshotId) {
        var snapshotHead = new SubscriptionSnapshotHead();
        snapshotHead.setSnapshotId(snapshotId);
        return snapshotHead;
    }
}