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
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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

        cache.prepare(oldHead);

        assertTrue(cache.getById("old-id").isEmpty());
        assertTrue(cache.findByEnvironmentAndEventType("production", "old-event").isEmpty());
        assertFalse(cache.isReady());

        cache.activate("snapshot-1");
        assertTrue(cache.isReady());
        cache.prepare(newHead);

        assertTrue(cache.getById("old-id").isPresent());
        assertTrue(cache.getById("new-id").isEmpty());

        cache.activate("snapshot-2");

        assertTrue(cache.getById("old-id").isEmpty());
        assertEquals(List.of(newSubscription), cache.findByEnvironmentAndEventType("production", "new-event"));
    }

    @Test
    void shouldRejectActivationWithoutPreparedSnapshot() {
        var exception = assertThrows(IllegalStateException.class, () -> cache.activate("snapshot-1"));

        assertEquals("No prepared subscription snapshot available", exception.getMessage());
    }

    @Test
    void shouldRejectActivationForDifferentPreparedSnapshotId() {
        var head = snapshotHead("snapshot-1");
        when(snapshotLoader.load(head)).thenReturn(snapshot(head,
            List.of(subscription("active-id", "production", "event"))));

        cache.prepare(head);

        var exception = assertThrows(IllegalStateException.class, () -> cache.activate("snapshot-2"));
        assertEquals("Prepared subscription snapshot does not match snapshotId to activate", exception.getMessage());
    }

    @Test
    void shouldRejectActivationOfEmptySnapshot() {
        var head = snapshotHead("snapshot-1");
        when(snapshotLoader.load(head)).thenReturn(snapshot("snapshot-1", List.of()));

        cache.prepare(head);

        var exception = assertThrows(SubscriptionCacheSnapshotException.class, () -> cache.activate("snapshot-1"));

        assertEquals("Cannot activate empty subscription snapshot", exception.getMessage());
        assertFalse(cache.isReady());
    }

    @Test
    void shouldKeepActiveSnapshotWhenPreparationFails() {
        var activeSubscription = subscription("active-id", "production", "event");
        var activeHead = snapshotHead("snapshot-1");
        var failingHead = snapshotHead("snapshot-2");
        when(snapshotLoader.load(activeHead)).thenReturn(snapshot("snapshot-1", List.of(activeSubscription)));
        when(snapshotLoader.load(failingHead)).thenThrow(new SubscriptionCacheSnapshotException("Snapshot loading failed"));
        cache.prepare(activeHead);
        cache.activate("snapshot-1");

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
        cache.activate("snapshot-2");

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
        snapshotCache.activate("snapshot-1");
        snapshotCache.prepare(secondHead);

        assertThrows(IllegalStateException.class, () -> snapshotCache.prepare(failingHead));
        snapshotCache.activate("snapshot-2");
        assertTrue(snapshotCache.isReady());
    }

    @Test
    void shouldSkipPreparationForActiveSnapshot() {
        var snapshotLoader = mock(MongoSubscriptionSnapshotLoader.class);
        var snapshotCache = new LocalSubscriptionCache(snapshotLoader);
        var activeHead = snapshotHead("snapshot-1");
        when(snapshotLoader.load(activeHead)).thenReturn(snapshot(activeHead,
            List.of(subscription("active-id", "production", "event"))));
        snapshotCache.prepare(activeHead);
        snapshotCache.activate("snapshot-1");

        snapshotCache.prepare(activeHead);
        assertFalse(snapshotCache.hasPendingSnapshot());
        snapshotCache.activate("snapshot-1");
    }

    @Test
    void shouldSkipPreparationForAlreadyPreparedSnapshot() {
        var snapshotLoader = mock(MongoSubscriptionSnapshotLoader.class);
        var snapshotCache = new LocalSubscriptionCache(snapshotLoader);
        var preparedHead = snapshotHead("snapshot-1");
        when(snapshotLoader.load(preparedHead)).thenReturn(snapshot(preparedHead,
            List.of(subscription("active-id", "production", "event"))));

        snapshotCache.prepare(preparedHead);
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
        cache.activate("snapshot-1");
        cache.prepare(revisedHead);

        assertTrue(cache.hasPendingSnapshot());
        cache.activate("snapshot-1");
        assertTrue(cache.getById("initial-id").isEmpty());
        assertTrue(cache.getById("revised-id").isPresent());

        cache.prepare(rehashedHead);

        assertTrue(cache.hasPendingSnapshot());
        cache.activate("snapshot-1");
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
        cache.activate("snapshot-1");
        cache.prepare(newHead);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var observedSnapshots = executor.submit(() -> {
                var observations = new ArrayList<List<SubscriptionResource>>();
                for (int index = 0; index < 10_000; index++) {
                    observations.add(cache.findByEnvironmentAndEventType("production", "event"));
                }
                return observations;
            });

            cache.activate("snapshot-2");

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
        cache.activate("snapshot-1");

        assertThrows(SubscriptionCacheSnapshotException.class, () -> cache.prepare(duplicateHead));

        assertTrue(cache.getById("active-id").isPresent());
        assertFalse(cache.getById("duplicate-id").isPresent());
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