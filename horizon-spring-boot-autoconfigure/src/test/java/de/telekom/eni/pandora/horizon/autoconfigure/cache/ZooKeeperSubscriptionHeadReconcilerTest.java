package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.cache.service.MongoSubscriptionSnapshotLoader;
import de.telekom.eni.pandora.horizon.cache.service.SubscriptionSnapshotHeads;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class ZooKeeperSubscriptionHeadReconcilerTest {

    private final ZooKeeperSubscriptionSnapshotHeadReader reader = mock(ZooKeeperSubscriptionSnapshotHeadReader.class);
    private final LocalSubscriptionCache cache = mock(LocalSubscriptionCache.class);
    private final ZooKeeperSubscriptionHeadReconciler reconciler = new ZooKeeperSubscriptionHeadReconciler(
        reader, cache);

    @Test
    void interruptedHeadReadIsNeitherCountedNorFallsBackToMongo() {
        when(reader.readActivate()).thenThrow(new IllegalStateException("Interrupted while reading",
            new InterruptedException()));

        reconciler.run();

        assertEquals(0, reconciler.headReadFailureCount());
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
        when(cache.isActiveSnapshot(active)).thenReturn(true);
        when(reader.readPrepared()).thenReturn(Optional.of(prepared));
        reconciler.run();

        verify(cache).activate(active);
        verify(cache).prepare(active);
        verify(cache).prepare(prepared);
    }

    @Test
    void missingActivateHeadMarksCacheStale() {
        var prepared = head("next");
        when(reader.readPrepared()).thenReturn(Optional.of(prepared));

        reconciler.run();

        verify(cache, never()).prepare(prepared);
        verify(cache).disconnected();
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
    void invalidActivateHeadMarksCacheStaleWithoutLoadingPrepared() {
        var prepared = head("next");
        when(reader.readPrepared()).thenReturn(Optional.of(prepared));
        when(reader.readActivate()).thenThrow(new SubscriptionCacheSnapshotException("invalid activate"));

        reconciler.run();

        verify(cache).disconnected();
        verify(cache, never()).prepare(prepared);
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
        verify(cache).discardPreparedSnapshot(active);
        verify(cache, never()).discardPreparedSnapshot();

        reconciler.run();
        verify(cache, times(2)).prepare(active);
        verify(cache).activate(active);
    }

    @Test
    void activationLogsSuppressRepeatedStackTracesAndResetAfterRecovery() {
        var active = head("active");
        when(reader.readActivate()).thenReturn(Optional.of(active));
        var failure = new SubscriptionCacheSnapshotException("count mismatch");
        doThrow(failure).doThrow(failure).doNothing().doNothing().doThrow(failure)
            .when(cache).prepare(active);
        Runnable reconcile = reconciler::reconcileActiveHead;
        var logger = (Logger) LoggerFactory.getLogger(ZooKeeperSubscriptionHeadReconciler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (int attempt = 0; attempt < 5; attempt++) {
                reconcile.run();
            }

            var warnings = appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
            assertEquals(3, warnings.size());
            assertNotNull(warnings.get(0).getThrowableProxy());
            assertNull(warnings.get(1).getThrowableProxy());
            assertTrue(warnings.get(1).getFormattedMessage().contains("2 consecutive failed attempts"));
            assertTrue(warnings.get(1).getFormattedMessage().contains("count mismatch"));
            assertNotNull(warnings.get(2).getThrowableProxy());
            var recoveries = appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("recovered")).toList();
            assertEquals(1, recoveries.size());
            assertEquals(Level.INFO, recoveries.getFirst().getLevel());
            assertTrue(recoveries.getFirst().getFormattedMessage().contains("2 failed attempts"));
            verify(cache, times(3)).activationFailed(active);
            verify(cache, times(2)).activate(active);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
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
        var activeHead = new AtomicReference<SubscriptionSnapshotHead>();
        doAnswer(invocation -> {
            activeHead.set(invocation.getArgument(0));
            return null;
        }).when(cache).activate(any());
        when(cache.isActiveSnapshot(any())).thenAnswer(invocation ->
            SubscriptionSnapshotHeads.isSameSnapshot(invocation.getArgument(0), activeHead.get()));

        reconciler.run();
        reconciler.run();
        reconciler.run();

        verify(cache).prepare(original);
        verify(cache).prepare(revised);
        verify(cache).prepare(rollback);
        verify(cache).activate(revised);
        verify(cache).activate(rollback);
    }

    @Test
    void activateReadFailureMarksCacheStale() {
        when(reader.readActivate()).thenThrow(new IllegalStateException("ZooKeeper unavailable"));

        reconciler.run();

        var order = inOrder(cache);
        verify(cache).disconnected();
        verify(cache, never()).suspended();
    }

    @Test
    void reconnectRereadsBothHeadsAndReactivatesStaleActiveHead() {
        var active = head("active");
        when(reader.readActivate()).thenReturn(Optional.of(active));

        reconciler.run();
        when(cache.isActiveSnapshotUpToDate()).thenReturn(true);
        when(cache.isActiveSnapshot(active)).thenReturn(true);
        reconciler.run();
        verify(cache).prepare(active);

        when(cache.isActiveSnapshotUpToDate()).thenReturn(false);
        reconciler.run();

        verify(reader, times(3)).readPrepared();
        verify(reader, times(3)).readActivate();
        verify(cache, never()).disconnected();
        verify(cache, never()).discardPreparedSnapshot();
        verify(cache, times(2)).prepare(active);
        verify(cache, times(2)).activate(active);
    }

    @Test
    void closedGateDuringLoadPreventsLateActivation() throws Exception {
        var active = head("active");
        when(reader.readActivate()).thenReturn(Optional.of(active));
        var current = new AtomicBoolean(true);
        reconciler.setActivationGate(commit -> {
            if (!current.get()) {
                return false;
            }
            commit.run();
            return true;
        });
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
            current.set(false);
            release.countDown();
            reconciliation.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }

        verify(cache).setActivationHead(active);
        verify(cache, never()).activate(active);
        verify(cache, never()).activationFailed(active);
        verify(cache, never()).suspended();
        verify(cache, never()).disconnected();
    }

    @Test
    void closedGateSkipsZooKeeperHeadWithoutTouchingCache() {
        var active = head("active");
        when(reader.readActivate()).thenReturn(Optional.of(active));
        reconciler.setActivationGate(commit -> false);

        reconciler.run();

        verify(cache, never()).setActivationHead(active);
        verify(cache, never()).prepare(active);
        verify(cache, never()).activate(active);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closedGateDoesNotRevokeFreshnessForAnObsoleteHeadRead(boolean readFails) {
        if (readFails) {
            when(reader.readActivate()).thenThrow(new IllegalStateException("ZooKeeper unavailable"));
        }
        reconciler.setActivationGate(commit -> false);

        reconciler.run();

        verify(cache, never()).disconnected();
        assertEquals(1, reconciler.headReadFailureCount());
    }

    private static SubscriptionSnapshotHead head(String snapshotId) {
        var head = new SubscriptionSnapshotHead();
        head.setSnapshotId(snapshotId);
        head.setDocumentCount(1L);
        head.setCreatedAt(new Date(0));
        return head;
    }
}