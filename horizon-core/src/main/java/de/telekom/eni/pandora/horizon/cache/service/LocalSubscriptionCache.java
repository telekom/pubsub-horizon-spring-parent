// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import de.telekom.eni.pandora.horizon.mongo.repository.SubscriptionsMongoRepo;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class LocalSubscriptionCache implements CacheReader<SubscriptionResource> {

    private final SubscriptionsMongoRepo subscriptionsMongoRepo;
    private final SubscriptionSnapshotLoader snapshotLoader;
    private final AtomicReference<IndexedSubscriptionSnapshot> activeSnapshot = new AtomicReference<>(IndexedSubscriptionSnapshot.empty());
    private final AtomicReference<IndexedSubscriptionSnapshot> preparedSnapshot = new AtomicReference<>();
    private final AtomicBoolean snapshotUpToDate = new AtomicBoolean();

    public LocalSubscriptionCache(SubscriptionsMongoRepo subscriptionsMongoRepo) {
        this.subscriptionsMongoRepo = subscriptionsMongoRepo;
        this.snapshotLoader = null;
    }

    public LocalSubscriptionCache(SubscriptionSnapshotLoader snapshotLoader) {
        this.subscriptionsMongoRepo = null;
        this.snapshotLoader = snapshotLoader;
    }

    public void prepare() {
        if (snapshotLoader == null) {
            preparedSnapshot.set(IndexedSubscriptionSnapshot.fromSubscriptionDocuments(subscriptionsMongoRepo.findAll()));
            return;
        }
        prepare(readSnapshotHead());
    }

    public boolean prepare(SubscriptionSnapshotHead snapshotHead) {
        if (snapshotLoader == null) {
            throw new IllegalStateException("Snapshot-head-based preparation is unavailable for repository-backed cache");
        }
        preparedSnapshot.set(null);
        if (snapshotHead.getSnapshotId().equals(activeSnapshot.get().snapshotId())) {
            return false;
        }
        snapshotUpToDate.set(false);

        preparedSnapshot.set(snapshotLoader.load(snapshotHead));
        return true;
    }

    public SubscriptionSnapshotHead readSnapshotHead() {
        if (snapshotLoader == null) {
            throw new IllegalStateException("Snapshot head is unavailable for repository-backed cache");
        }
        return snapshotLoader.readSnapshotHead();
    }

    public void activate() {
        var snapshot = preparedSnapshot.getAndSet(null);
        if (snapshot == null) {
            throw new IllegalStateException("No prepared subscription snapshot available");
        }
        activeSnapshot.set(snapshot);
        snapshotUpToDate.set(true);
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
        return activeSnapshot.get().getByQuery(environment, eventType);
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

    @Override
    public boolean isReady() {
        return activeSnapshot.get().snapshotId() != null;
    }

    private int getEntryCount() {
        return activeSnapshot.get().byId().size();
    }

    public boolean isCacheUpToDate() {
        return snapshotUpToDate.get();
    }

}