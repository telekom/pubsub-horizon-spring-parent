// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.kubernetes.resource.Subscription;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResourceSpec;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionMongoDocument;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotEntry;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalSubscriptionCacheTest {

    private final MongoSubscriptionSnapshotLoader snapshotLoader = mock(MongoSubscriptionSnapshotLoader.class);
    private final LocalSubscriptionCache cache = new LocalSubscriptionCache(snapshotLoader);

    @Test
    void shouldPrepareAndAtomicallyActivateSnapshot() {
        var oldSubscription = subscription("old-id", "production", "old-event");
        var newSubscription = subscription("new-id", "production", "new-event");
        var oldHead = snapshotHead("snapshot-1");
        var newHead = snapshotHead("snapshot-2");
        when(snapshotLoader.load(oldHead)).thenReturn(snapshot("snapshot-1", List.of(oldSubscription)));
        when(snapshotLoader.load(newHead)).thenReturn(snapshot("snapshot-2", List.of(newSubscription)));

        assertFalse(cache.firstFreshSnapshot().toCompletableFuture().isDone());
        cache.prepare(oldHead);

        assertFalse(cache.firstFreshSnapshot().toCompletableFuture().isDone());
        assertTrue(cache.getById("old-id").isEmpty());
        assertTrue(cache.findByEnvironmentAndEventType("production", "old-event").isEmpty());
        assertFalse(cache.isInitialized());
        assertTrue(cache.localSnapshotId().isEmpty());

        cache.activate(oldHead);
        assertTrue(cache.firstFreshSnapshot().toCompletableFuture().isDone());
        assertTrue(cache.isInitialized());
        assertEquals("snapshot-1", cache.localSnapshotId().orElseThrow());
        cache.prepare(newHead);

        assertTrue(cache.getById("old-id").isPresent());
        assertTrue(cache.getById("new-id").isEmpty());

        cache.activate(newHead);

        assertTrue(cache.getById("old-id").isEmpty());
        assertEquals(List.of(newSubscription), cache.findByEnvironmentAndEventType("production", "new-event"));
    }

    @Test
    void shouldRejectActivationWithoutPreparedSnapshot() {
        var exception = assertThrows(IllegalStateException.class, () -> cache.activate(snapshotHead("snapshot-1")));

        assertEquals("No prepared subscription snapshot available", exception.getMessage());
        assertFalse(cache.firstFreshSnapshot().toCompletableFuture().isDone());
    }

    @Test
    void firstFreshSnapshotWaitsForConfirmedHeadAndRemainsCompleteAfterDisconnect() {
        var head = snapshotHead("snapshot-1");
        when(snapshotLoader.load(head)).thenReturn(snapshot(head,
            List.of(subscription("active-id", "production", "event"))));

        cache.prepare(head);
        cache.disconnected();
        cache.activate(head);
        assertEquals(LocalSubscriptionCache.Status.STALE, cache.status());
        assertFalse(cache.firstFreshSnapshot().toCompletableFuture().isDone());
        assertFalse(cache.hasFirstFreshSnapshot());

        cache.setActivationHead(head);
        cache.activate(head);
        assertTrue(cache.firstFreshSnapshot().toCompletableFuture().isDone());
        assertTrue(cache.hasFirstFreshSnapshot());

        cache.disconnected();
        assertTrue(cache.firstFreshSnapshot().toCompletableFuture().isDone());
        assertTrue(cache.hasFirstFreshSnapshot());
    }

    @Test
    void shouldRejectActivationForDifferentPreparedSnapshotId() {
        var head = snapshotHead("snapshot-1");
        when(snapshotLoader.load(head)).thenReturn(snapshot(head,
            List.of(subscription("active-id", "production", "event"))));

        cache.prepare(head);

        var differentHead = snapshotHead("snapshot-2");
        var exception = assertThrows(IllegalStateException.class, () -> cache.activate(differentHead));
        assertEquals("Prepared subscription snapshot does not match snapshot head to activate", exception.getMessage());
    }

    @Test
    void shouldRejectActivationOfEmptySnapshot() {
        var head = snapshotHead("snapshot-1");
        when(snapshotLoader.load(head)).thenReturn(snapshot("snapshot-1", List.of()));

        cache.prepare(head);

        var exception = assertThrows(SubscriptionCacheSnapshotException.class, () -> cache.activate(head));

        assertEquals("Cannot activate empty subscription snapshot", exception.getMessage());
        assertFalse(cache.isInitialized());
    }

    @Test
    void shouldKeepActiveSnapshotWhenPreparationFails() {
        var activeSubscription = subscription("active-id", "production", "event");
        var activeHead = snapshotHead("snapshot-1");
        var failingHead = snapshotHead("snapshot-2");
        when(snapshotLoader.load(activeHead)).thenReturn(snapshot("snapshot-1", List.of(activeSubscription)));
        when(snapshotLoader.load(failingHead)).thenThrow(new SubscriptionCacheSnapshotException("Snapshot loading failed"));
        cache.prepare(activeHead);
        cache.activate(activeHead);

        assertThrows(SubscriptionCacheSnapshotException.class, () -> cache.prepare(failingHead));

        assertTrue(cache.getById("active-id").isPresent());
    }

    @Test
    void shouldReturnEmptyResultsForUnknownOrInvalidLookups() {
        assertTrue(cache.getById("unknown").isEmpty());
        assertTrue(cache.getById(null).isEmpty());
        assertTrue(cache.findByEnvironmentAndEventType("production", "unknown").isEmpty());
        assertTrue(cache.findByEnvironmentAndEventType(null, "event").isEmpty());
        assertTrue(cache.findByEnvironmentAndEventType("production", null).isEmpty());
    }

    @Test
    void shouldReplacePreviouslyPreparedSnapshot() {
        var discardedSubscription = subscription("discarded-id", "production", "event");
        var activatedSubscription = subscription("activated-id", "production", "event");
        var discardedHead = snapshotHead("snapshot-1");
        var activatedHead = snapshotHead("snapshot-2");
        when(snapshotLoader.load(discardedHead)).thenReturn(snapshot("snapshot-1", List.of(discardedSubscription)));
        when(snapshotLoader.load(activatedHead)).thenReturn(snapshot("snapshot-2", List.of(activatedSubscription)));

        cache.prepare(discardedHead);
        cache.prepare(activatedHead);
        cache.activate(activatedHead);

        assertTrue(cache.getById("discarded-id").isEmpty());
        assertTrue(cache.getById("activated-id").isPresent());
    }

    @Test
    void shouldKeepPreparedSnapshotWhenNewPreparationFails() {
        var snapshotLoader = mock(MongoSubscriptionSnapshotLoader.class);
        var snapshotCache = new LocalSubscriptionCache(snapshotLoader);
        var firstHead = snapshotHead("snapshot-1");
        var secondHead = snapshotHead("snapshot-2");
        var failingHead = snapshotHead("snapshot-3");
        when(snapshotLoader.load(firstHead)).thenReturn(snapshot(firstHead,
            List.of(subscription("active-id", "production", "event"))));
        when(snapshotLoader.load(secondHead)).thenReturn(snapshot(secondHead,
            List.of(subscription("active-id", "production", "event"))));
        when(snapshotLoader.load(failingHead)).thenThrow(new IllegalStateException("Snapshot loading failed"));
        snapshotCache.prepare(firstHead);
        snapshotCache.activate(firstHead);
        snapshotCache.prepare(secondHead);

        assertThrows(IllegalStateException.class, () -> snapshotCache.prepare(failingHead));
        snapshotCache.activate(secondHead);
        assertTrue(snapshotCache.isInitialized());
    }

    @Test
    void shouldSkipPreparationForActiveSnapshot() {
        var snapshotLoader = mock(MongoSubscriptionSnapshotLoader.class);
        var snapshotCache = new LocalSubscriptionCache(snapshotLoader);
        var activeHead = snapshotHead("snapshot-1");
        when(snapshotLoader.load(activeHead)).thenReturn(snapshot(activeHead,
            List.of(subscription("active-id", "production", "event"))));
        snapshotCache.prepare(activeHead);
        snapshotCache.activate(activeHead);

        snapshotCache.prepare(activeHead);
        assertFalse(snapshotCache.hasPendingSnapshot());
        snapshotCache.activate(activeHead);
        verify(snapshotLoader).load(activeHead);
    }

    @Test
    void shouldSkipPreparationForAlreadyPreparedSnapshot() {
        var snapshotLoader = mock(MongoSubscriptionSnapshotLoader.class);
        var snapshotCache = new LocalSubscriptionCache(snapshotLoader);
        var preparedHead = snapshotHead("snapshot-1");
        when(snapshotLoader.load(preparedHead)).thenReturn(snapshot(preparedHead,
            List.of(subscription("active-id", "production", "event"))));

        snapshotCache.prepare(preparedHead);
        snapshotCache.disconnected();
        snapshotCache.prepare(preparedHead);

        verify(snapshotLoader).load(preparedHead);
    }

    @Test
    void shouldReloadAndActivateWhenSnapshotMetadataChangesForSameId() {
        var initialHead = snapshotHead("snapshot-1");
        initialHead.setRevision(1L);
        initialHead.setSourceHash("hash-1");
        var revisedHead = snapshotHead("snapshot-1");
        revisedHead.setRevision(2L);
        revisedHead.setSourceHash("hash-1");
        var rehashedHead = snapshotHead("snapshot-1");
        rehashedHead.setRevision(2L);
        rehashedHead.setSourceHash("hash-2");
        var initialSubscription = subscription("initial-id", "production", "event");
        var revisedSubscription = subscription("revised-id", "production", "event");
        var rehashedSubscription = subscription("rehashed-id", "production", "event");
        when(snapshotLoader.load(initialHead)).thenReturn(snapshot(initialHead, List.of(initialSubscription)));
        when(snapshotLoader.load(revisedHead)).thenReturn(snapshot(revisedHead, List.of(revisedSubscription)));
        when(snapshotLoader.load(rehashedHead)).thenReturn(snapshot(rehashedHead, List.of(rehashedSubscription)));

        cache.prepare(initialHead);
        cache.activate(initialHead);
        cache.prepare(revisedHead);

        assertTrue(cache.hasPendingSnapshot());
        cache.activate(revisedHead);
        assertTrue(cache.getById("initial-id").isEmpty());
        assertTrue(cache.getById("revised-id").isPresent());

        cache.prepare(rehashedHead);

        assertTrue(cache.hasPendingSnapshot());
        cache.activate(rehashedHead);
        assertTrue(cache.getById("revised-id").isEmpty());
        assertTrue(cache.getById("rehashed-id").isPresent());
    }

    @Test
    void concurrentReadersShouldOnlySeeCompleteSnapshots() throws Exception {
        var oldSubscriptions = subscriptions("old", 100);
        var newSubscriptions = subscriptions("new", 100);
        var oldHead = snapshotHead("snapshot-1");
        var newHead = snapshotHead("snapshot-2");
        when(snapshotLoader.load(oldHead)).thenReturn(snapshot("snapshot-1", oldSubscriptions));
        when(snapshotLoader.load(newHead)).thenReturn(snapshot("snapshot-2", newSubscriptions));
        cache.prepare(oldHead);
        cache.activate(oldHead);
        cache.prepare(newHead);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var observedSnapshots = executor.submit(() -> {
                var observations = new ArrayList<List<SubscriptionResource>>();
                for (int index = 0; index < 10_000; index++) {
                    observations.add(cache.findByEnvironmentAndEventType("production", "event"));
                }
                return observations;
            });

            cache.activate(newHead);

            for (var snapshot : observedSnapshots.get()) {
                assertEquals(100, snapshot.size());
                var prefix = snapshot.getFirst().getSpec().getSubscription().getSubscriptionId().split("-")[0];
                assertTrue(prefix.equals("old") || prefix.equals("new"));
                assertTrue(snapshot.stream()
                        .allMatch(resource -> resource.getSpec().getSubscription().getSubscriptionId().startsWith(prefix + "-")));
            }
        }
    }

    @Test
    void shouldRejectDuplicateSubscriptionIdsWithoutChangingActiveSnapshot() {
        var activeSubscription = subscription("active-id", "production", "event");
        var duplicateOne = subscription("duplicate-id", "production", "event-one");
        var duplicateTwo = subscription("duplicate-id", "production", "event-two");
        var activeHead = snapshotHead("snapshot-1");
        var duplicateHead = snapshotHead("snapshot-2");
        when(snapshotLoader.load(activeHead)).thenReturn(snapshot("snapshot-1", List.of(activeSubscription)));
        when(snapshotLoader.load(duplicateHead)).thenAnswer(ignored ->
            snapshot("snapshot-2", List.of(duplicateOne, duplicateTwo)));
        cache.prepare(activeHead);
        cache.activate(activeHead);

        assertThrows(SubscriptionCacheSnapshotException.class, () -> cache.prepare(duplicateHead));

        assertTrue(cache.getById("active-id").isPresent());
        assertFalse(cache.getById("duplicate-id").isPresent());
    }

    @Test
    void retainsFreshSnapshotUntilCurrentHeadFails() {
        var first = snapshotHead("first");
        var second = snapshotHead("second");
        when(snapshotLoader.load(first)).thenReturn(snapshot(first,
            List.of(subscription("first-id", "production", "event"))));
        when(snapshotLoader.load(second)).thenReturn(snapshot(second,
            List.of(subscription("second-id", "production", "event"))));

        assertEquals(LocalSubscriptionCache.Status.UNINITIALIZED, cache.status());
        cache.setActivationHead(first);
        cache.prepare(first);
        cache.activate(first);
        assertTrue(cache.isActiveSnapshotUpToDate());

        cache.setActivationHead(second);
        assertTrue(cache.isActiveSnapshotUpToDate());
        assertTrue(cache.getById("first-id").isPresent());
        cache.activationFailed(second);
        assertEquals(LocalSubscriptionCache.Status.STALE, cache.status());
        assertTrue(cache.isInitialized());
        assertTrue(cache.getById("first-id").isPresent());

        cache.prepare(second);
        cache.activate(second);
        assertTrue(cache.isActiveSnapshotUpToDate());
        assertTrue(cache.getById("second-id").isPresent());
    }

    @Test
    void disconnectPreventsLateActivationUntilActivationHeadIsSet() {
        var head = snapshotHead("first");
        when(snapshotLoader.load(head)).thenReturn(snapshot(head,
            List.of(subscription("first-id", "production", "event"))));

        cache.setActivationHead(head);
        cache.prepare(head);
        cache.disconnected();
        cache.activate(head);
        assertEquals(LocalSubscriptionCache.Status.STALE, cache.status());
        assertTrue(cache.isInitialized());
        assertFalse(cache.canServeLocalReads());

        cache.setActivationHead(head);
        cache.activate(head);
        assertTrue(cache.isActiveSnapshotUpToDate());
    }

    @Test
    void staleReadGraceAppliesAcrossSuspendedAndDisconnected() {
        var head = snapshotHead("suspended");
        var clock = new MutableClock(Instant.parse("2026-10-02T00:00:00Z"));
        var graceCache = new LocalSubscriptionCache(snapshotLoader, Duration.ofSeconds(5), clock);
        when(snapshotLoader.load(head)).thenReturn(snapshot(head,
            List.of(subscription("local-id", "production", "event"))));
        graceCache.setActivationHead(head);
        graceCache.prepare(head);
        graceCache.activate(head);

        clock.advance(Duration.ofSeconds(1));
        graceCache.suspended();

        assertEquals(LocalSubscriptionCache.Status.STALE, graceCache.status());
        assertTrue(graceCache.isInitialized());
        assertFalse(graceCache.isActiveSnapshotUpToDate());
        assertTrue(graceCache.canServeLocalReads());
        assertTrue(graceCache.isReady());
        assertTrue(graceCache.getById("local-id").isPresent());

        clock.advance(Duration.ofSeconds(3));
        graceCache.disconnected();
        assertTrue(graceCache.canServeLocalReads(), "another stale cause must not restart the grace period");

        clock.advance(Duration.ofSeconds(2));
        assertFalse(graceCache.canServeLocalReads());
        assertTrue(graceCache.isInitialized());
        assertFalse(graceCache.isReady());
    }

    @Test
    void activationFailureUsesTheSameStaleGracePeriod() {
        var head = snapshotHead("failed-activation");
        var clock = new MutableClock(Instant.parse("2026-10-02T00:00:00Z"));
        var graceCache = new LocalSubscriptionCache(snapshotLoader, Duration.ofSeconds(5), clock);
        when(snapshotLoader.load(head)).thenReturn(snapshot(head,
            List.of(subscription("local-id", "production", "event"))));
        graceCache.setActivationHead(head);
        graceCache.prepare(head);
        graceCache.activate(head);

        clock.advance(Duration.ofSeconds(1));
        graceCache.setActivationHead(snapshotHead("new-head"));
        graceCache.activationFailed(snapshotHead("new-head"));

        assertEquals(LocalSubscriptionCache.Status.STALE, graceCache.status());
        assertTrue(graceCache.canServeLocalReads());
        assertFalse(graceCache.canServeLocalReads(clock.instant().plusSeconds(5)));
    }

    @Test
    void diagnosticsReportActiveExpectedAndPendingSnapshots() {
        var activeHead = snapshotHead("active");
        var nextHead = snapshotHead("next");
        var activatedAt = Instant.parse("2026-10-02T00:00:00Z");
        var clock = new MutableClock(activatedAt);
        var diagnosticsCache = new LocalSubscriptionCache(snapshotLoader, Duration.ofSeconds(5), clock);
        when(snapshotLoader.load(activeHead)).thenReturn(snapshot(activeHead,
            List.of(subscription("a", "production", "event"), subscription("b", "production", "event"))));
        when(snapshotLoader.load(nextHead)).thenReturn(snapshot(nextHead,
            List.of(subscription("c", "production", "event"))));
        diagnosticsCache.setActivationHead(activeHead);
        diagnosticsCache.prepare(activeHead);
        diagnosticsCache.activate(activeHead);

        clock.advance(Duration.ofSeconds(1));
        diagnosticsCache.setActivationHead(nextHead);
        diagnosticsCache.prepare(nextHead);
        diagnosticsCache.activationFailed(nextHead);

        var diagnostics = diagnosticsCache.diagnostics();
        assertEquals(LocalSubscriptionCache.Status.STALE, diagnostics.status());
        assertTrue(diagnostics.localReadsAllowed());
        assertEquals("active", diagnostics.activeSnapshotId());
        assertEquals(2, diagnostics.subscriptionCount());
        assertEquals(activatedAt, diagnostics.activatedAt());
        assertEquals("next", diagnostics.expectedSnapshotId());
        assertEquals("next", diagnostics.pendingSnapshotId());
        assertEquals(activatedAt.plusSeconds(1), diagnostics.staleSince());
        assertEquals(2, diagnosticsCache.snapshotLoadCount());
        assertTrue(diagnosticsCache.lastSnapshotLoadNanos() >= 0);
        assertEquals(1, diagnosticsCache.activationFailureCount());
    }

    @Test
    void rejectsNegativeStaleCacheReadGracePeriod() {
        assertThrows(IllegalArgumentException.class,
            () -> new LocalSubscriptionCache(snapshotLoader, Duration.ofSeconds(-1)));
    }

    @Test
    void suspendedUninitializedCacheRemainsUnavailable() {
        cache.suspended();

        assertEquals(LocalSubscriptionCache.Status.UNINITIALIZED, cache.status());
        assertFalse(cache.isInitialized());
        assertFalse(cache.canServeLocalReads());
        assertFalse(cache.isReady());
    }

    @Test
    void ignoresMongoIdButComparesCompleteActivationHead() {
        var mongoHead = snapshotHead("same");
        var zooKeeperHead = snapshotHead("same");
        zooKeeperHead.setId(null);
        var changedHead = snapshotHead("same");
        changedHead.setId(null);
        changedHead.setRevision(2L);
        when(snapshotLoader.load(mongoHead)).thenReturn(snapshot(mongoHead,
            List.of(subscription("first-id", "production", "event"))));
        when(snapshotLoader.load(changedHead)).thenReturn(snapshot(changedHead,
            List.of(subscription("second-id", "production", "event"))));

        cache.prepare(mongoHead);
        cache.activate(mongoHead);
        cache.setActivationHead(zooKeeperHead);
        cache.activate(mongoHead);
        assertTrue(cache.isActiveSnapshotUpToDate());

        cache.setActivationHead(changedHead);
        cache.activate(mongoHead);
        assertTrue(cache.isActiveSnapshotUpToDate());
        cache.prepare(changedHead);
        cache.activate(changedHead);
        assertTrue(cache.isActiveSnapshotUpToDate());
        assertTrue(cache.getById("second-id").isPresent());
    }

    @Test
    void ignoresFailureOfSupersededHead() {
        var first = snapshotHead("first");
        var superseded = snapshotHead("superseded");
        var latest = snapshotHead("latest");
        when(snapshotLoader.load(first)).thenReturn(snapshot(first,
            List.of(subscription("first-id", "production", "event"))));
        cache.setActivationHead(first);
        cache.prepare(first);
        cache.activate(first);

        cache.setActivationHead(superseded);
        cache.setActivationHead(latest);
        cache.activationFailed(superseded);

        assertTrue(cache.isActiveSnapshotUpToDate());
        assertTrue(cache.getById("first-id").isPresent());
    }

    @Test
    void lateActivationCannotRestoreFreshnessAfterDisconnect() {
        var first = snapshotHead("first");
        var next = snapshotHead("next");
        when(snapshotLoader.load(first)).thenReturn(snapshot(first,
            List.of(subscription("first-id", "production", "event"))));
        when(snapshotLoader.load(next)).thenReturn(snapshot(next,
            List.of(subscription("next-id", "production", "event"))));
        cache.setActivationHead(first);
        cache.prepare(first);
        cache.activate(first);
        cache.setActivationHead(next);
        cache.prepare(next);
        cache.disconnected();

        cache.activate(next);

        assertFalse(cache.isActiveSnapshotUpToDate());
        assertTrue(cache.isInitialized());
        assertTrue(cache.getById("next-id").isPresent());
    }

    @Test
    void rejectsInvalidActivationHead() {
        var head = snapshotHead("first");
        head.setDocumentCount(0L);

        assertThrows(SubscriptionCacheSnapshotException.class, () -> cache.setActivationHead(head));
        assertEquals(LocalSubscriptionCache.Status.UNINITIALIZED, cache.status());
    }

    @Test
    void concurrentReadersRemainAvailableDuringPreparation() throws Exception {
        var first = snapshotHead("first");
        var next = snapshotHead("next");
        var activeSubscription = subscription("first-id", "production", "event");
        when(snapshotLoader.load(first)).thenReturn(snapshot(first, List.of(activeSubscription)));
        cache.setActivationHead(first);
        cache.prepare(first);
        cache.activate(first);
        cache.setActivationHead(next);
        var loading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        blockSnapshotLoad(next, loading, release);

        try (var executor = Executors.newFixedThreadPool(9)) {
            var preparation = executor.submit(() -> cache.prepare(next));
            try {
                assertTrue(loading.await(10, TimeUnit.SECONDS));
                var readers = new ArrayList<java.util.concurrent.Future<?>>();
                for (int readerIndex = 0; readerIndex < 8; readerIndex++) {
                    readers.add(executor.submit(() -> {
                        for (int lookupIndex = 0; lookupIndex < 500; lookupIndex++) {
                            assertEquals(activeSubscription, cache.getById("first-id").orElseThrow());
                            assertEquals(List.of(activeSubscription),
                                cache.findByEnvironmentAndEventType("production", "event"));
                            assertTrue(cache.isInitialized());
                            assertTrue(cache.isActiveSnapshotUpToDate());
                        }
                    }));
                }
                for (var reader : readers) {
                    reader.get(10, TimeUnit.SECONDS);
                }
                assertFalse(preparation.isDone());
                assertFalse(cache.hasPendingSnapshot());
                release.countDown();
                preparation.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }

        assertTrue(cache.isActiveSnapshotUpToDate());
        assertTrue(cache.hasPendingSnapshot());
        assertTrue(cache.getById("first-id").isPresent());
        assertTrue(cache.getById("next-id").isEmpty());
        cache.activate(next);
        assertTrue(cache.isActiveSnapshotUpToDate());
        assertFalse(cache.hasPendingSnapshot());
        assertTrue(cache.getById("next-id").isPresent());
        verify(snapshotLoader).load(next);
    }

    @Test
    void disconnectDuringPreparationIsNotOverwrittenByLoadedSnapshot() throws Exception {
        var first = snapshotHead("first");
        var next = snapshotHead("next");
        when(snapshotLoader.load(first)).thenReturn(snapshot(first,
            List.of(subscription("first-id", "production", "event"))));
        cache.setActivationHead(first);
        cache.prepare(first);
        cache.activate(first);
        cache.setActivationHead(next);
        var loading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        blockSnapshotLoad(next, loading, release);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var preparation = executor.submit(() -> cache.prepare(next));
            try {
                assertTrue(loading.await(10, TimeUnit.SECONDS));
                executor.submit(cache::disconnected).get(10, TimeUnit.SECONDS);
                assertEquals(LocalSubscriptionCache.Status.STALE, cache.status());
                assertTrue(cache.getById("first-id").isPresent());
                release.countDown();
                preparation.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }

        assertEquals(LocalSubscriptionCache.Status.STALE, cache.status());
        assertTrue(cache.hasPendingSnapshot());
        cache.activate(next);
        assertFalse(cache.isActiveSnapshotUpToDate());
        assertTrue(cache.isInitialized());
        assertTrue(cache.getById("next-id").isPresent());
        cache.setActivationHead(next);
        cache.activate(next);
        assertTrue(cache.isActiveSnapshotUpToDate());
        verify(snapshotLoader).load(next);
    }

    @Test
    void newerActivationHeadDuringPreparationIsNotOverwrittenByLoadedSnapshot() throws Exception {
        var first = snapshotHead("first");
        var next = snapshotHead("next");
        var latest = snapshotHead("latest");
        when(snapshotLoader.load(first)).thenReturn(snapshot(first,
            List.of(subscription("first-id", "production", "event"))));
        when(snapshotLoader.load(latest)).thenReturn(snapshot(latest,
            List.of(subscription("latest-id", "production", "event"))));
        cache.setActivationHead(first);
        cache.prepare(first);
        cache.activate(first);
        cache.setActivationHead(next);
        var loading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        blockSnapshotLoad(next, loading, release);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var preparation = executor.submit(() -> cache.prepare(next));
            try {
                assertTrue(loading.await(10, TimeUnit.SECONDS));
                executor.submit(() -> cache.setActivationHead(latest)).get(10, TimeUnit.SECONDS);
                release.countDown();
                preparation.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }

        assertTrue(cache.isActiveSnapshotUpToDate());
        cache.activationFailed(next);
        assertTrue(cache.isActiveSnapshotUpToDate());
        cache.activate(next);
        assertFalse(cache.isActiveSnapshotUpToDate());
        cache.prepare(latest);
        cache.activate(latest);
        assertTrue(cache.isActiveSnapshotUpToDate());
        assertTrue(cache.getById("latest-id").isPresent());
        verify(snapshotLoader).load(next);
    }

    @Test
    void concurrentActivationAndDisconnectCannotLeaveSnapshotFresh() throws Exception {
        var next = snapshotHead("next");
        when(snapshotLoader.load(next)).thenReturn(snapshot(next,
            List.of(subscription("next-id", "production", "event"))));
        cache.setActivationHead(next);
        cache.prepare(next);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var activation = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                cache.activate(next);
                return null;
            });
            var disconnect = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                cache.disconnected();
                return null;
            });
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                activation.get(10, TimeUnit.SECONDS);
                disconnect.get(10, TimeUnit.SECONDS);
            } finally {
                start.countDown();
            }
        }

        assertEquals(LocalSubscriptionCache.Status.STALE, cache.status());
        assertTrue(cache.isInitialized());
        assertTrue(cache.getById("next-id").isPresent());
        assertFalse(cache.hasPendingSnapshot());
    }

    @Test
    void discardingPreparedSnapshotPreservesActiveSnapshotAndActivationHeadRequirement() {
        var first = snapshotHead("first");
        var next = snapshotHead("next");
        when(snapshotLoader.load(first)).thenReturn(snapshot(first,
            List.of(subscription("first-id", "production", "event"))));
        when(snapshotLoader.load(next)).thenReturn(snapshot(next,
            List.of(subscription("next-id", "production", "event"))));
        cache.setActivationHead(first);
        cache.prepare(first);
        cache.activate(first);
        cache.setActivationHead(next);
        cache.prepare(next);
        cache.activationFailed(next);
        cache.discardPreparedSnapshot();

        assertFalse(cache.hasPendingSnapshot());
        assertTrue(cache.isInitialized());
        assertTrue(cache.getById("first-id").isPresent());
        assertTrue(cache.getById("next-id").isEmpty());
        cache.activate(first);
        assertEquals(LocalSubscriptionCache.Status.STALE, cache.status());
        cache.prepare(next);
        cache.activate(next);
        assertTrue(cache.isActiveSnapshotUpToDate());
        assertTrue(cache.getById("next-id").isPresent());
    }

    @Test
    void preparingActiveHeadKeepsPendingSnapshotWithoutReloading() {
        var active = snapshotHead("active");
        var pending = snapshotHead("pending");
        when(snapshotLoader.load(active)).thenReturn(snapshot(active,
            List.of(subscription("active-id", "production", "event"))));
        when(snapshotLoader.load(pending)).thenReturn(snapshot(pending,
            List.of(subscription("pending-id", "production", "event"))));
        cache.setActivationHead(active);
        cache.prepare(active);
        cache.activate(active);
        cache.prepare(pending);

        cache.prepare(active);
        cache.activate(active);

        verify(snapshotLoader, times(1)).load(active);
        assertTrue(cache.hasPendingSnapshot());
        assertEquals(LocalSubscriptionCache.Status.FRESH, cache.status());
        assertTrue(cache.getById("active-id").isPresent());

        cache.setActivationHead(pending);
        cache.activate(pending);
        verify(snapshotLoader, times(1)).load(pending);
        assertTrue(cache.getById("pending-id").isPresent());
        assertTrue(cache.isActiveSnapshotUpToDate());
    }

    @Test
    void behindActivationHeadComparesFullSnapshotIdentity() {
        var active = snapshotHead("snapshot");
        when(snapshotLoader.load(active)).thenReturn(snapshot(active,
            List.of(subscription("id", "production", "event"))));
        assertFalse(cache.isBehindActivationHead());
        cache.setActivationHead(active);
        assertTrue(cache.isBehindActivationHead());
        cache.prepare(active);
        cache.activate(active);
        assertFalse(cache.isBehindActivationHead());

        var revised = snapshotHead("snapshot");
        revised.setRevision(2L);
        cache.setActivationHead(revised);
        assertTrue(cache.isBehindActivationHead());

        var withoutOptionalFields = snapshotHead("snapshot");
        withoutOptionalFields.setRevision(null);
        withoutOptionalFields.setSourceHash(null);
        cache.setActivationHead(withoutOptionalFields);
        assertFalse(cache.isBehindActivationHead());
    }

    private void blockSnapshotLoad(SubscriptionSnapshotHead head, CountDownLatch loading, CountDownLatch release) {
        when(snapshotLoader.load(head)).thenAnswer(ignored -> {
            loading.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS));
            return snapshot(head, List.of(subscription("next-id", "production", "event")));
        });
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> currentInstant;

        private MutableClock(Instant initialInstant) {
            this.currentInstant = new AtomicReference<>(initialInstant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return currentInstant.get();
        }

        private void advance(Duration duration) {
            currentInstant.updateAndGet(instant -> instant.plus(duration));
        }
    }

    private List<SubscriptionMongoDocument> subscriptions(String prefix, int count) {
        var subscriptions = new ArrayList<SubscriptionMongoDocument>();
        for (int index = 0; index < count; index++) {
            subscriptions.add(subscription(prefix + "-" + index, "production", "event"));
        }
        return subscriptions;
    }

    private IndexedSubscriptionSnapshot snapshot(String snapshotId, List<SubscriptionMongoDocument> subscriptions) {
        return snapshot(snapshotHead(snapshotId), subscriptions);
    }

    private IndexedSubscriptionSnapshot snapshot(SubscriptionSnapshotHead snapshotHead,
                                                  List<SubscriptionMongoDocument> subscriptions) {
        var entries = subscriptions.stream().map(subscription -> {
            var entry = new SubscriptionSnapshotEntry();
            entry.setSnapshotId(snapshotHead.getSnapshotId());
            entry.setResource(subscription);
            return entry;
        }).toList();
        return IndexedSubscriptionSnapshot.fromSnapshotEntries(snapshotHead, entries);
    }

    private SubscriptionSnapshotHead snapshotHead(String snapshotId) {
        var snapshotHead = new SubscriptionSnapshotHead();
        snapshotHead.setId("head");
        snapshotHead.setSnapshotId(snapshotId);
        snapshotHead.setDocumentCount(1L);
        snapshotHead.setRevision(1L);
        snapshotHead.setSourceHash("hash");
        snapshotHead.setCreatedAt(new Date(0));
        return snapshotHead;
    }

    private SubscriptionMongoDocument subscription(String subscriptionId, String environment, String eventType) {
        var subscription = new Subscription();
        subscription.setSubscriptionId(subscriptionId);
        subscription.setType(eventType);

        var spec = new SubscriptionResourceSpec();
        spec.setEnvironment(environment);
        spec.setSubscription(subscription);

        var document = new SubscriptionMongoDocument();
        document.setSpec(spec);
        return document;
    }
}