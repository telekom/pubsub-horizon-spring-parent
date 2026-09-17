// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionMongoDocument;
import de.telekom.eni.pandora.horizon.mongo.repository.SubscriptionsMongoRepo;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class LocalSubscriptionCache implements CacheReader<SubscriptionResource> {

    private final SubscriptionsMongoRepo subscriptionsMongoRepo;
    private final AtomicReference<Snapshot> activeSnapshot = new AtomicReference<>(Snapshot.empty());
    private final AtomicReference<Snapshot> preparedSnapshot = new AtomicReference<>();
    private final AtomicBoolean ready = new AtomicBoolean();

    public LocalSubscriptionCache(SubscriptionsMongoRepo subscriptionsMongoRepo) {
        this.subscriptionsMongoRepo = subscriptionsMongoRepo;
    }

    public void prepare() {
        ready.set(false);
        preparedSnapshot.set(Snapshot.from(subscriptionsMongoRepo.findAll()));
    }

    public void activate() {
        var snapshot = preparedSnapshot.getAndSet(null);
        if (snapshot == null) {
            throw new IllegalStateException("No prepared subscription snapshot available");
        }
        activeSnapshot.set(snapshot);
        ready.set(true);
    }

    public Optional<SubscriptionResource> getById(String subscriptionId) {
        if (subscriptionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(activeSnapshot.get().byId().get(subscriptionId));
    }

    @Override
    public Optional<SubscriptionResource> getByKey(String key) {
        return getById(key);
    }

    public List<SubscriptionResource> getByQuery(String environment, String eventType) {
        if (environment == null || eventType == null) {
            return List.of();
        }
        return activeSnapshot.get().byQuery().getOrDefault(new QueryKey(environment, eventType), List.of());
    }

    @Override
    public List<SubscriptionResource> getQuery(Query query) throws JsonCacheException {
        var startedAt = System.nanoTime();
        var result = getByQuery(query.getEnvironment(), query.getEventType());
        var accessTimeMicros = (System.nanoTime() - startedAt) / 1_000;
        log.debug("Local subscription cache query completed: environment={}, eventType={}, durationMicros={}, matchingEntries={}, totalEntries={}",
                query.getEnvironment(), query.getEventType(), accessTimeMicros, result.size(), getEntryCount());
        return result;
    }

    @Override
    public List<SubscriptionResource> getAll() {
        return new ArrayList<>(activeSnapshot.get().byId().values());
    }

    public int getEntryCount() {
        return activeSnapshot.get().byId().size();
    }

    public boolean isReady() {
        return ready.get();
    }

    private record QueryKey(String environment, String eventType) {
    }

    private record Snapshot(Map<String, SubscriptionResource> byId,
                            Map<QueryKey, List<SubscriptionResource>> byQuery) {

        private static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of());
        }

        private static Snapshot from(List<SubscriptionMongoDocument> documents) {
            var byId = new HashMap<String, SubscriptionResource>();
            var mutableByQuery = new HashMap<QueryKey, List<SubscriptionResource>>();

            for (var document : documents) {
                validate(document);
                var subscription = document.getSpec().getSubscription();
                var previous = byId.put(subscription.getSubscriptionId(), document);
                if (previous != null) {
                    throw new IllegalStateException("Duplicate subscription id: " + subscription.getSubscriptionId());
                }

                var queryKey = new QueryKey(document.getSpec().getEnvironment(), subscription.getType());
                mutableByQuery.computeIfAbsent(queryKey, ignored -> new ArrayList<>()).add(document);
            }

            var byQuery = new HashMap<QueryKey, List<SubscriptionResource>>();
            mutableByQuery.forEach((key, value) -> byQuery.put(key, List.copyOf(value)));
            return new Snapshot(Map.copyOf(byId), Map.copyOf(byQuery));
        }

        private static void validate(SubscriptionMongoDocument document) {
            if (document == null || document.getSpec() == null || document.getSpec().getSubscription() == null) {
                throw new IllegalStateException("Invalid subscription document without subscription data");
            }
            var subscription = document.getSpec().getSubscription();
            if (subscription.getSubscriptionId() == null || document.getSpec().getEnvironment() == null || subscription.getType() == null) {
                throw new IllegalStateException("Invalid subscription document without id, environment or event type");
            }
        }
    }
}