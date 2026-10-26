// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.cache.service.MongoSubscriptionSnapshotLoader;
import de.telekom.eni.pandora.horizon.cache.service.SubscriptionSnapshotHeads;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.Date;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

class ZooKeeperSubscriptionHeadReconcilerTest {

    private final ZooKeeperSubscriptionSnapshotHeadReader reader = mock(ZooKeeperSubscriptionSnapshotHeadReader.class);
    private final LocalSubscriptionCache cache = mock(LocalSubscriptionCache.class);
    private final GatedReconciler reconciler = new GatedReconciler(
        new ZooKeeperSubscriptionHeadReconciler(reader, cache));

    /** Test adapter that passes a configurable gate to every reconciliation call. */
    private static final class GatedReconciler {
        private final ZooKeeperSubscriptionHeadReconciler target;
        private ZooKeeperSubscriptionHeadReconciler.CacheCommitGate gate =
            ZooKeeperSubscriptionHeadReconciler.CacheCommitGate.OPEN;

        private GatedReconciler(ZooKeeperSubscriptionHeadReconciler target) {
            this.target = target;
        }

        void setCommitGate(ZooKeeperSubscriptionHeadReconciler.CacheCommitGate gate) {
            this.gate = gate;
        }

        void reconcilePreparedAndActivatedHeads() {
            target.reconcilePreparedAndActivatedHeads(gate);
        }

        void reconcileActivatedHead() {
            target.reconcileActivatedHead(gate);
        }

        long getActivatedHeadReadFailureCount() {
            return target.getActivatedHeadReadFailureCount();
        }
    }

    @Test
    void interruptedHeadReadIsNeitherCountedNorFallsBackToMongo() {
        when(reader.readActivatedHead()).thenThrow(new IllegalStateException("Interrupted while reading",
            new InterruptedException()));

        reconciler.reconcilePreparedAndActivatedHeads();

        assertEquals(0, reconciler.getActivatedHeadReadFailureCount());
        verify(cache, never()).clearExpectedHead();
    }

    @Test
    void prioritizesActivatedHeadOnStartupWithoutLoadingPreparedTwice() {
        var prepared = head("prepared");
        var active = head("active");
        when(reader.readPreparedHead()).thenReturn(Optional.of(prepared));
        when(reader.readActivatedHead()).thenReturn(Optional.of(active));

        reconciler.reconcilePreparedAndActivatedHeads();

        var order = inOrder(reader, cache);
        order.verify(reader).readPreparedHead();
        order.verify(reader).readActivatedHead();
        order.verify(cache).setExpectedHead(active);
        order.verify(cache).prepare(active);
        order.verify(cache).activate(active);
        verify(cache, never()).prepare(prepared);
    }

    @Test
    void preloadsNewPreparedHeadWithoutReactivatingConfirmedHead() {
        var active = head("active");
        var prepared = head("next");
        when(reader.readActivatedHead()).thenReturn(Optional.of(active));
        reconciler.reconcilePreparedAndActivatedHeads();

        when(cache.isFresh()).thenReturn(true);
        when(cache.isActiveSnapshot(active)).thenReturn(true);
        when(reader.readPreparedHead()).thenReturn(Optional.of(prepared));
        reconciler.reconcilePreparedAndActivatedHeads();

        verify(cache).activate(active);
        verify(cache).prepare(active);
        verify(cache).prepare(prepared);
    }

    @Test
    void missingActivatedHeadMarksCacheStale() {
        var prepared = head("next");
        when(reader.readPreparedHead()).thenReturn(Optional.of(prepared));

        reconciler.reconcilePreparedAndActivatedHeads();

        verify(cache, never()).prepare(prepared);
        verify(cache).clearExpectedHead();
        assertEquals(1, reconciler.getActivatedHeadReadFailureCount());
    }

    @Test
    void invalidPreparedHeadDoesNotBlockValidActivatedHead() {
        var active = head("active");
        when(reader.readPreparedHead()).thenThrow(new SubscriptionCacheSnapshotException("invalid prepared"));
        when(reader.readActivatedHead()).thenReturn(Optional.of(active));

        reconciler.reconcilePreparedAndActivatedHeads();

        verify(cache).setExpectedHead(active);
        verify(cache).prepare(active);
        verify(cache).activate(active);
    }

    @Test
    void preparedReadFailureDoesNotBlockActivatedHead() {
        var active = head("active");
        when(reader.readPreparedHead()).thenThrow(new IllegalStateException("ZooKeeper read denied"));
        when(reader.readActivatedHead()).thenReturn(Optional.of(active));

        reconciler.reconcilePreparedAndActivatedHeads();

        verify(reader).readActivatedHead();
        verify(cache).setExpectedHead(active);
        verify(cache).prepare(active);
        verify(cache).activate(active);
    }

    @Test
    void activatedOnlyReconciliationDoesNotReadPreparedHead() {
        var active = head("active");
        when(reader.readActivatedHead()).thenReturn(Optional.of(active));

        reconciler.reconcileActivatedHead();

        verify(reader).readActivatedHead();
        verify(reader, never()).readPreparedHead();
        verify(cache).setExpectedHead(active);
        verify(cache).prepare(active);
        verify(cache).activate(active);
        assertEquals(0, reconciler.getActivatedHeadReadFailureCount());
    }

    @Test
    void invalidActivatedHeadMarksCacheStaleWithoutLoadingPrepared() {
        var prepared = head("next");
        when(reader.readPreparedHead()).thenReturn(Optional.of(prepared));
        when(reader.readActivatedHead()).thenThrow(new SubscriptionCacheSnapshotException("invalid activated"));

        reconciler.reconcilePreparedAndActivatedHeads();

        verify(cache).clearExpectedHead();
        verify(cache, never()).prepare(prepared);
        assertEquals(1, reconciler.getActivatedHeadReadFailureCount());
    }

    @Test
    void failedActivationRevokesFreshnessAndCanRetryOnNextEvent() {
        var active = head("active");
        when(reader.readActivatedHead()).thenReturn(Optional.of(active));
        doThrow(new SubscriptionCacheSnapshotException("MongoDB unavailable"))
            .doNothing().when(cache).prepare(active);

        reconciler.reconcilePreparedAndActivatedHeads();
        verify(cache).recordActivationFailure(active);
        verify(cache).discardPreparedSnapshot(active);
        verify(cache, never()).discardPreparedSnapshot();

        reconciler.reconcilePreparedAndActivatedHeads();
        verify(cache, times(2)).prepare(active);
        verify(cache).activate(active);
    }

    @Test
    void activationLogsSuppressRepeatedStackTracesAndResetAfterRecovery() {
        var active = head("active");
        when(reader.readActivatedHead()).thenReturn(Optional.of(active));
        var failure = new SubscriptionCacheSnapshotException("count mismatch");
        doThrow(failure).doThrow(failure).doNothing().doNothing().doThrow(failure)
            .when(cache).prepare(active);
        Runnable reconcile = reconciler::reconcileActivatedHead;
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
            verify(cache, times(3)).recordActivationFailure(active);
            verify(cache, times(2)).activate(active);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void interruptedLoadIsNeitherCountedNorMarksCacheStale() {
        var active = head("active");
        when(reader.readActivatedHead()).thenReturn(Optional.of(active));
        doThrow(new IllegalStateException("load interrupted", new InterruptedException()))
            .when(cache).prepare(active);
        var logger = (Logger) LoggerFactory.getLogger(ZooKeeperSubscriptionHeadReconciler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            reconciler.reconcileActivatedHead();

            verify(cache, never()).recordActivationFailure(active);
            verify(cache, never()).activate(active);
            assertTrue(appender.list.stream().noneMatch(event -> event.getLevel() == Level.WARN));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void errorDuringActivationIsRecordedAndRethrown() {
        var active = head("active");
        when(reader.readActivatedHead()).thenReturn(Optional.of(active));
        doThrow(new OutOfMemoryError("test")).when(cache).prepare(active);

        assertThrows(OutOfMemoryError.class, reconciler::reconcileActivatedHead);

        verify(cache).recordActivationFailure(active);
        verify(cache).discardPreparedSnapshot(active);
    }

    @Test
    @SuppressWarnings("unchecked")
    void errorWhileLoadingNewerHeadRevokesFreshnessUntilRetrySucceeds() {
        var template = mock(MongoTemplate.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(template.getCollection("snapshots")).thenReturn(collection);
        var failing = new AtomicBoolean(true);
        when(collection.find(any(Bson.class))).thenAnswer(invocation -> {
            var snapshotId = invocation.getArgument(0, Bson.class).toBsonDocument().getString("snapshotId").getValue();
            if (snapshotId.equals("next") && failing.get()) {
                throw new OutOfMemoryError("test");
            }
            FindIterable<Document> result = mock(FindIterable.class);
            when(result.maxTime(anyLong(), any(TimeUnit.class))).thenReturn(result);
            when(result.into(any())).thenAnswer(into -> {
                Collection<Document> target = into.getArgument(0);
                target.add(snapshotEntry(snapshotId));
                return target;
            });
            return result;
        });
        var realCache = new LocalSubscriptionCache(
            new MongoSubscriptionSnapshotLoader(template, "snapshots", Duration.ofSeconds(60)), Duration.ofSeconds(300));
        var target = new ZooKeeperSubscriptionHeadReconciler(reader, realCache);
        var gate = ZooKeeperSubscriptionHeadReconciler.CacheCommitGate.OPEN;
        when(reader.readActivatedHead()).thenReturn(Optional.of(head("active")));
        target.reconcileActivatedHead(gate);
        assertEquals(LocalSubscriptionCache.Status.FRESH, realCache.getStatus());

        when(reader.readActivatedHead()).thenReturn(Optional.of(head("next")));
        assertThrows(OutOfMemoryError.class, () -> target.reconcileActivatedHead(gate));

        assertEquals(LocalSubscriptionCache.Status.STALE, realCache.getStatus());
        assertTrue(realCache.isBehindExpectedHead());
        assertEquals(1, realCache.getActivationFailureCount());
        assertEquals(Optional.of("active"), realCache.getLocalSnapshotId());

        failing.set(false);
        target.reconcileActivatedHead(gate);

        assertEquals(LocalSubscriptionCache.Status.FRESH, realCache.getStatus());
        assertEquals(Optional.of("next"), realCache.getLocalSnapshotId());
    }

    @Test
    void changedMetadataForSameIdIsSkippedButRollbackIsNot() {
        var original = head("active");
        var revised = head("active");
        revised.setDocumentCount(2L);
        var rollback = head("previous");
        when(reader.readActivatedHead()).thenReturn(Optional.of(original), Optional.of(revised), Optional.of(rollback));
        when(cache.isFresh()).thenReturn(true);
        var activeHead = new AtomicReference<SubscriptionSnapshotHead>();
        doAnswer(invocation -> {
            activeHead.set(invocation.getArgument(0));
            return null;
        }).when(cache).activate(any());
        when(cache.isActiveSnapshot(any())).thenAnswer(invocation ->
            SubscriptionSnapshotHeads.isSameSnapshot(invocation.getArgument(0), activeHead.get()));

        reconciler.reconcilePreparedAndActivatedHeads();
        reconciler.reconcilePreparedAndActivatedHeads();
        reconciler.reconcilePreparedAndActivatedHeads();

        verify(cache).prepare(original);
        verify(cache, never()).prepare(revised);
        verify(cache).prepare(rollback);
        verify(cache, never()).activate(revised);
        verify(cache).activate(rollback);
    }

    @Test
    void activatedReadFailureMarksCacheStale() {
        when(reader.readActivatedHead()).thenThrow(new IllegalStateException("ZooKeeper unavailable"));

        reconciler.reconcilePreparedAndActivatedHeads();

        verify(cache).clearExpectedHead();
    }

    @Test
    void errorWhileReadingActivatedHeadMarksCacheUnconfirmedAndIsRethrown() {
        when(reader.readActivatedHead()).thenThrow(new OutOfMemoryError("test"));

        assertThrows(OutOfMemoryError.class, reconciler::reconcileActivatedHead);

        verify(cache).clearExpectedHead();
        assertEquals(1, reconciler.getActivatedHeadReadFailureCount());
    }

    @Test
    void reconnectRereadsBothHeadsAndReactivatesStaleActiveHead() {
        var active = head("active");
        when(reader.readActivatedHead()).thenReturn(Optional.of(active));

        reconciler.reconcilePreparedAndActivatedHeads();
        when(cache.isFresh()).thenReturn(true);
        when(cache.isActiveSnapshot(active)).thenReturn(true);
        reconciler.reconcilePreparedAndActivatedHeads();
        verify(cache).prepare(active);

        when(cache.isFresh()).thenReturn(false);
        reconciler.reconcilePreparedAndActivatedHeads();

        verify(reader, times(3)).readPreparedHead();
        verify(reader, times(3)).readActivatedHead();
        verify(cache, never()).clearExpectedHead();
        verify(cache, never()).discardPreparedSnapshot();
        verify(cache, times(2)).prepare(active);
        verify(cache, times(2)).activate(active);
    }

    @Test
    void closedGateDuringLoadPreventsLateActivation() throws Exception {
        var active = head("active");
        when(reader.readActivatedHead()).thenReturn(Optional.of(active));
        var current = new AtomicBoolean(true);
        reconciler.setCommitGate(commit -> {
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
            var reconciliation = executor.submit(reconciler::reconcilePreparedAndActivatedHeads);
            assertTrue(loading.await(10, TimeUnit.SECONDS));
            current.set(false);
            release.countDown();
            reconciliation.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }

        verify(cache).setExpectedHead(active);
        verify(cache, never()).activate(active);
        verify(cache, never()).recordActivationFailure(active);
        verify(cache, never()).clearExpectedHead();
    }

    @Test
    void closedGateSkipsZooKeeperHeadWithoutTouchingCache() {
        var active = head("active");
        when(reader.readActivatedHead()).thenReturn(Optional.of(active));
        reconciler.setCommitGate(commit -> false);

        reconciler.reconcilePreparedAndActivatedHeads();

        verify(cache, never()).setExpectedHead(active);
        verify(cache, never()).prepare(active);
        verify(cache, never()).activate(active);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closedGateDoesNotRevokeFreshnessForAnObsoleteHeadRead(boolean readFails) {
        if (readFails) {
            when(reader.readActivatedHead()).thenThrow(new IllegalStateException("ZooKeeper unavailable"));
        }
        reconciler.setCommitGate(commit -> false);

        reconciler.reconcilePreparedAndActivatedHeads();

        verify(cache, never()).clearExpectedHead();
        assertEquals(1, reconciler.getActivatedHeadReadFailureCount());
    }

    private static SubscriptionSnapshotHead head(String snapshotId) {
        var head = new SubscriptionSnapshotHead();
        head.setSnapshotId(snapshotId);
        head.setDocumentCount(1L);
        head.setCreated(new Date(0));
        return head;
    }

    private static Document snapshotEntry(String snapshotId) {
        var subscription = new Document("subscriptionId", "subscription-" + snapshotId).append("type", "event.v1");
        var spec = new Document("environment", "default").append("subscription", subscription);
        return new Document("snapshotId", snapshotId)
            .append("subscriptionId", "subscription-" + snapshotId)
            .append("resource", new Document("spec", spec));
    }
}