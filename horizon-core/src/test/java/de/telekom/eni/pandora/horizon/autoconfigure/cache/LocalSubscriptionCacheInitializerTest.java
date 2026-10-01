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
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Status;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @BeforeEach
    void setDefaultCacheDiagnostics() {
        when(cache.localSnapshotId()).thenReturn(Optional.empty());
        when(cache.status()).thenReturn(LocalSubscriptionCache.Status.UNINITIALIZED);
    }

    @Test
    void shouldLeaveInitializationToZooKeeperWhenEnabled() {
        cacheProperties.getLocalSubscriptionCache().getZooKeeper().setEnabled(true);

        initializer.run(null);

        verifyNoInteractions(cache);
    }

    @Test
    void zooKeeperHealthRequiresEffectiveReaderEvenWhenOldSnapshotExists() {
        cacheProperties.getLocalSubscriptionCache().getZooKeeper().setEnabled(true);
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
    void shouldPrepareAndActivateCacheBeforeReportingUp() {
        var snapshotHead = snapshotHead("snapshot-1");
        when(cache.readSnapshotHead()).thenReturn(snapshotHead);
        when(cache.isInitialized()).thenReturn(false, true);
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

    private SubscriptionSnapshotHead snapshotHead(String snapshotId) {
        var snapshotHead = new SubscriptionSnapshotHead();
        snapshotHead.setSnapshotId(snapshotId);
        return snapshotHead;
    }
}