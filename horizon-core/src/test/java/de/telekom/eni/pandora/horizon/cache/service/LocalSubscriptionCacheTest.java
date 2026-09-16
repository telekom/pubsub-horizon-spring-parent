// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.kubernetes.resource.Subscription;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResourceSpec;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionMongoDocument;
import de.telekom.eni.pandora.horizon.mongo.repository.SubscriptionsMongoRepo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalSubscriptionCacheTest {

    private final SubscriptionsMongoRepo subscriptionsMongoRepo = mock(SubscriptionsMongoRepo.class);
    private final LocalSubscriptionCache cache = new LocalSubscriptionCache(subscriptionsMongoRepo);

    @Test
    void shouldPrepareAndAtomicallyActivateSnapshot() {
        var oldSubscription = subscription("old-id", "production", "old-event");
        var newSubscription = subscription("new-id", "production", "new-event");
        when(subscriptionsMongoRepo.findAll()).thenReturn(List.of(oldSubscription)).thenReturn(List.of(newSubscription));

        cache.prepare();

        assertTrue(cache.getById("old-id").isEmpty());
        assertTrue(cache.getByQuery("production", "old-event").isEmpty());

        cache.activate();
        cache.prepare();

        assertTrue(cache.getById("old-id").isPresent());
        assertTrue(cache.getById("new-id").isEmpty());

        cache.activate();

        assertTrue(cache.getById("old-id").isEmpty());
        assertEquals(List.of(newSubscription), cache.getByQuery("production", "new-event"));
    }

    @Test
    void shouldRejectActivationWithoutPreparedSnapshot() {
        var exception = assertThrows(IllegalStateException.class, cache::activate);

        assertEquals("No prepared subscription snapshot available", exception.getMessage());
    }

    @Test
    void shouldKeepActiveSnapshotWhenPreparationFails() {
        var activeSubscription = subscription("active-id", "production", "event");
        var invalidSubscription = subscription(null, "production", "event");
        when(subscriptionsMongoRepo.findAll()).thenReturn(List.of(activeSubscription)).thenReturn(List.of(invalidSubscription));
        cache.prepare();
        cache.activate();

        assertThrows(IllegalStateException.class, cache::prepare);

        assertTrue(cache.getById("active-id").isPresent());
    }

    @Test
    void shouldReturnEmptyResultsForUnknownOrInvalidLookups() {
        assertTrue(cache.getById("unknown").isEmpty());
        assertTrue(cache.getById(null).isEmpty());
        assertTrue(cache.getByQuery("production", "unknown").isEmpty());
        assertTrue(cache.getByQuery(null, "event").isEmpty());
        assertTrue(cache.getByQuery("production", null).isEmpty());
    }

    @Test
    void shouldReplacePreviouslyPreparedSnapshot() {
        var discardedSubscription = subscription("discarded-id", "production", "event");
        var activatedSubscription = subscription("activated-id", "production", "event");
        when(subscriptionsMongoRepo.findAll()).thenReturn(List.of(discardedSubscription)).thenReturn(List.of(activatedSubscription));

        cache.prepare();
        cache.prepare();
        cache.activate();

        assertTrue(cache.getById("discarded-id").isEmpty());
        assertTrue(cache.getById("activated-id").isPresent());
    }

    @Test
    void concurrentReadersShouldOnlySeeCompleteSnapshots() throws Exception {
        var oldSubscriptions = subscriptions("old", 100);
        var newSubscriptions = subscriptions("new", 100);
        when(subscriptionsMongoRepo.findAll()).thenReturn(oldSubscriptions).thenReturn(newSubscriptions);
        cache.prepare();
        cache.activate();
        cache.prepare();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var observedSnapshots = executor.submit(() -> {
                var observations = new ArrayList<List<SubscriptionResource>>();
                for (int index = 0; index < 10_000; index++) {
                    observations.add(cache.getByQuery("production", "event"));
                }
                return observations;
            });

            cache.activate();

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
        when(subscriptionsMongoRepo.findAll()).thenReturn(List.of(activeSubscription))
                .thenReturn(List.of(duplicateOne, duplicateTwo));
        cache.prepare();
        cache.activate();

        assertThrows(IllegalStateException.class, cache::prepare);

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