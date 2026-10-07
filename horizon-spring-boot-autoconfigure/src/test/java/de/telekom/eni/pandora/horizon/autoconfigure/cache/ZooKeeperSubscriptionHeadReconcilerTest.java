package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZooKeeperSubscriptionHeadReconcilerTest {

    private final ZooKeeperSubscriptionSnapshotHeadReader reader = mock(ZooKeeperSubscriptionSnapshotHeadReader.class);
    private final LocalSubscriptionCache cache = mock(LocalSubscriptionCache.class);
    private final ZooKeeperSubscriptionHeadReconciler reconciler = new ZooKeeperSubscriptionHeadReconciler(reader, cache);

    @Test
    void interruptedHeadReadIsNeitherCountedNorFallsBackToMongo() {
        when(reader.readActivate()).thenThrow(new IllegalStateException("Interrupted while reading",
            new InterruptedException()));

        reconciler.run();

        assertEquals(0, reconciler.headReadFailureCount());
        verify(cache, never()).readSnapshotHead();
        verify(cache, never()).disconnected();
    }

    @Test
    void prioritizesActivateHeadOnStartupWithoutLoadingPreparedTwice() {
        var prepared = head("prepared");
        var active = head("active");
        when(reader.readPrepared()).thenReturn(Optional.of(prepared));
        when(reader.readActivate()).thenReturn(Optional.of(active));

        reconciler.run();

        var order = inOrder(reader, cache);
        order.verify(reader).readPrepared();
        order.verify(reader).readActivate();
        order.verify(cache).setActivationHead(active);
        order.verify(cache).prepare(active);
        order.verify(cache).activate(active);
        verify(cache, never()).prepare(prepared);
    }

    @Test
    void preloadsNewPreparedHeadWithoutReactivatingConfirmedHead() {
        var active = head("active");
        var prepared = head("next");
        when(reader.readActivate()).thenReturn(Optional.of(active));
        reconciler.run();

        when(cache.isActiveSnapshotUpToDate()).thenReturn(true);
        when(reader.readPrepared()).thenReturn(Optional.of(prepared));
        reconciler.run();

        verify(cache).activate(active);
        verify(cache).prepare(active);
        verify(cache).prepare(prepared);
    }

    @Test
    void missingActivateHeadUsesMongoHead() {
        var prepared = head("next");
        var mongoHead = head("mongo");
        when(reader.readPrepared()).thenReturn(Optional.of(prepared));
        when(cache.readSnapshotHead()).thenReturn(mongoHead);

        reconciler.run();

        var order = inOrder(cache);
        order.verify(cache).readSnapshotHead();
        order.verify(cache).setActivationHead(mongoHead);
        order.verify(cache).prepare(mongoHead);
        order.verify(cache).activate(mongoHead);
        verify(cache, never()).prepare(prepared);
        verify(cache, never()).disconnected();
        assertEquals(1, reconciler.headReadFailureCount());
    }

    @Test
    void invalidPreparedHeadDoesNotBlockValidActivateHead() {
        var active = head("active");
        when(reader.readPrepared()).thenThrow(new SubscriptionCacheSnapshotException("invalid prepared"));
        when(reader.readActivate()).thenReturn(Optional.of(active));

        reconciler.run();

        verify(cache).setActivationHead(active);
        verify(cache).prepare(active);
        verify(cache).activate(active);
    }

    @Test
    void preparedReadFailureDoesNotBlockActivateHead() {
        var active = head("active");
        when(reader.readPrepared()).thenThrow(new IllegalStateException("ZooKeeper read denied"));
        when(reader.readActivate()).thenReturn(Optional.of(active));

        reconciler.run();

        verify(reader).readActivate();
        verify(cache).setActivationHead(active);
        verify(cache).prepare(active);
        verify(cache).activate(active);
    }

    @Test
    void activeOnlyReconciliationDoesNotReadPreparedHead() {
        var active = head("active");
        when(reader.readActivate()).thenReturn(Optional.of(active));

        reconciler.reconcileActiveHead();

        verify(reader).readActivate();
        verify(reader, never()).readPrepared();
        verify(cache).setActivationHead(active);
        verify(cache).prepare(active);
        verify(cache).activate(active);
        assertEquals(0, reconciler.headReadFailureCount());
    }

    @Test
    void invalidActivateHeadUsesMongoHeadWithoutLoadingPrepared() {
        var prepared = head("next");
        var mongoHead = head("mongo");
        when(reader.readPrepared()).thenReturn(Optional.of(prepared));
        when(reader.readActivate()).thenThrow(new SubscriptionCacheSnapshotException("invalid activate"));
        when(cache.readSnapshotHead()).thenReturn(mongoHead);

        reconciler.run();

        verify(cache, never()).disconnected();
        verify(cache, never()).prepare(prepared);
        verify(cache).activate(mongoHead);
        assertEquals(1, reconciler.headReadFailureCount());
    }

    @Test
    void failedActivationRevokesFreshnessAndCanRetryOnNextEvent() {
        var active = head("active");
        when(reader.readActivate()).thenReturn(Optional.of(active));
        doThrow(new SubscriptionCacheSnapshotException("MongoDB unavailable"))
            .doNothing().when(cache).prepare(active);

        reconciler.run();
        verify(cache).activationFailed(active);
        verify(cache).discardPreparedSnapshot();
        verify(cache, never()).readSnapshotHead();

        reconciler.run();
        verify(cache, times(2)).prepare(active);
        verify(cache).activate(active);
    }

    @Test
    void changedMetadataOrRollbackIsNotSkippedAsAnOldHead() {
        var original = head("active");
        original.setRevision(1L);
        var revised = head("active");
        revised.setRevision(2L);
        var rollback = head("previous");
        when(reader.readActivate()).thenReturn(Optional.of(original), Optional.of(revised), Optional.of(rollback));
        when(cache.isActiveSnapshotUpToDate()).thenReturn(true);

        reconciler.run();
        reconciler.run();
        reconciler.run();

        verify(cache).prepare(original);
        verify(cache).prepare(revised);
        verify(cache).prepare(rollback);
        verify(cache).activate(rollback);
    }

    @Test
    void activateReadFailureUsesMongoHeadWithoutRevokingFreshness() {
        var mongoHead = head("mongo");
        when(reader.readActivate()).thenThrow(new IllegalStateException("ZooKeeper unavailable"));
        when(cache.readSnapshotHead()).thenReturn(mongoHead);

        reconciler.run();

        var order = inOrder(cache);
        order.verify(cache).setActivationHead(mongoHead);
        order.verify(cache).prepare(mongoHead);
        order.verify(cache).activate(mongoHead);
        verify(cache, never()).disconnected();
        verify(cache, never()).suspended();
    }

    @Test
    void repeatedMongoHeadFallbackReusesActivatedSnapshot() {
        var mongoHead = head("mongo");
        when(cache.readSnapshotHead()).thenReturn(mongoHead);
        when(cache.isActiveSnapshot(mongoHead)).thenReturn(false, true);

        reconciler.reconcileFromMongoHead();
        reconciler.reconcileFromMongoHead();

        verify(cache).prepare(mongoHead);
        verify(cache, never()).discardPreparedSnapshot();
        verify(cache, times(2)).activate(mongoHead);
    }

    @Test
    void failedMongoHeadLoadKeepsCacheStale() {
        var mongoHead = head("mongo");
        when(cache.readSnapshotHead()).thenReturn(mongoHead);
        doThrow(new SubscriptionCacheSnapshotException("count mismatch")).when(cache).prepare(mongoHead);

        reconciler.reconcileFromMongoHead();

        verify(cache).activationFailed(mongoHead);
        verify(cache, never()).activate(mongoHead);
    }

    @Test
    void disabledMongoHeadFallbackMarksCacheStaleWithoutReadingMongo() {
        var withoutFallback = new ZooKeeperSubscriptionHeadReconciler(reader, cache, false);
        when(reader.readActivate()).thenThrow(new IllegalStateException("ZooKeeper unavailable"));

        withoutFallback.run();

        verify(cache).disconnected();
        verify(cache, never()).readSnapshotHead();
    }

    @Test
    void unreadableMongoHeadMarksCacheStale() {
        when(cache.readSnapshotHead()).thenThrow(new SubscriptionCacheSnapshotException("no head"));

        reconciler.reconcileFromMongoHead();

        verify(cache).disconnected();
        verify(cache, never()).setActivationHead(org.mockito.ArgumentMatchers.any());
        verify(cache, never()).activationFailed(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reconnectRereadsBothHeadsAndReloadsUnchangedActiveHead() {
        var active = head("active");
        when(reader.readActivate()).thenReturn(Optional.of(active));
        when(cache.isActiveSnapshotUpToDate()).thenReturn(true);

        reconciler.run();
        reconciler.run();
        verify(cache).prepare(active);

        reconciler.reconcileAfterReconnect();

        verify(reader, times(3)).readPrepared();
        verify(reader, times(3)).readActivate();
        verify(cache, never()).disconnected();
        verify(cache, never()).discardPreparedSnapshot();
        verify(cache, times(2)).prepare(active);
        verify(cache, times(2)).activate(active);
    }

    @Test
    void suspensionDuringLoadPreventsLateActivation() throws Exception {
        var active = head("active");
        when(reader.readActivate()).thenReturn(Optional.of(active));
        var loading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(ignored -> {
            loading.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return null;
        }).when(cache).prepare(active);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var reconciliation = executor.submit(reconciler::run);
            assertTrue(loading.await(10, TimeUnit.SECONDS));
            reconciler.suspended();
            verify(cache, never()).suspended();
            verify(cache, never()).disconnected();
            release.countDown();
            reconciliation.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }

        verify(cache, never()).activate(active);
    }

    @Test
    void lostConnectionKeepsCacheFreshWhenMongoHeadConfirmsIt() {
        var mongoHead = head("mongo");
        when(cache.readSnapshotHead()).thenReturn(mongoHead);

        reconciler.lost();
        reconciler.reconcileFromMongoHead();

        verify(cache, never()).disconnected();
        verify(cache, never()).suspended();
        verify(cache).activate(mongoHead);
    }

    private static SubscriptionSnapshotHead head(String snapshotId) {
        var head = new SubscriptionSnapshotHead();
        head.setSnapshotId(snapshotId);
        head.setDocumentCount(1L);
        head.setCreatedAt(new Date(0));
        return head;
    }
}