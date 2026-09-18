// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import de.telekom.eni.pandora.horizon.mongo.repository.SubscriptionsMongoRepo;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pod-local subscription cache with an explicit prepare/activate lifecycle.
 *
 * <p>A prepared snapshot becomes visible only after a successful call to
 * {@link #activate()}.</p>
 */
public class LocalSubscriptionCache implements SubscriptionCacheReader {

    private final SubscriptionsMongoRepo liveSubscriptionsRepository;
    private final MongoSubscriptionSnapshotLoader snapshotLoader;
    private final AtomicReference<IndexedSubscriptionSnapshot> activeSnapshot = new AtomicReference<>(IndexedSubscriptionSnapshot.empty());
    private final AtomicReference<IndexedSubscriptionSnapshot> preparedSnapshot = new AtomicReference<>();
    private final AtomicBoolean snapshotUpToDate = new AtomicBoolean();

    /**
     * Creates a cache backed by the current live subscription repository.
     *
     * @param liveSubscriptionsRepository repository containing current subscriptions
     */
    public LocalSubscriptionCache(SubscriptionsMongoRepo liveSubscriptionsRepository) {
        this.liveSubscriptionsRepository = liveSubscriptionsRepository;
        this.snapshotLoader = null;
    }

    /**
     * Creates a cache backed by persisted subscription snapshots.
     *
     * @param snapshotLoader loader for snapshot metadata and entries
     */
    public LocalSubscriptionCache(MongoSubscriptionSnapshotLoader snapshotLoader) {
        this.liveSubscriptionsRepository = null;
        this.snapshotLoader = snapshotLoader;
    }

    /**
     * Prepares a cache snapshot from live subscriptions or a persisted subscription snapshot.
     *
     * @return {@code true} if a new snapshot was prepared
     */
    public boolean prepare() {
        if (snapshotLoader == null) {
            return prepareFromLiveSubscriptions();
        }
        return prepareFromSubscriptionSnapshot(readSnapshotHead());
    }

    /**
     * Prepares a cache snapshot from the live subscription repository.
     *
     * @return {@code true} after preparing a snapshot
     */
    public boolean prepareFromLiveSubscriptions() {
        if (snapshotLoader != null) {
            throw new IllegalStateException("Mongo repository preparation is unavailable for snapshot-backed cache");
        }
        var snapshot = IndexedSubscriptionSnapshot.fromSubscriptionMongoDocuments(liveSubscriptionsRepository.findAll());
        preparedSnapshot.set(snapshot);
        return true;
    }

    /**
     * Prepares a cache snapshot from a persisted subscription snapshot.
     *
     * @param snapshotHead metadata identifying the snapshot to load
     * @return {@code true} if a new snapshot was prepared, otherwise {@code false}
     */
    public boolean prepareFromSubscriptionSnapshot(SubscriptionSnapshotHead snapshotHead) {
        if (snapshotLoader == null) {
            throw new IllegalStateException("Snapshot-head-based preparation is unavailable for repository-backed cache");
        }
        preparedSnapshot.set(null);
        if (snapshotHead.getSnapshotId().equals(activeSnapshot.get().snapshotId())) {
            return false;
        }
        snapshotUpToDate.set(false);

        var snapshot = snapshotLoader.load(snapshotHead);
        preparedSnapshot.set(snapshot);
        return true;
    }

    /**
     * Reads the currently published snapshot metadata.
     *
     * @return the current snapshot head
     * @throws IllegalStateException if this cache uses the live source
     */
    public SubscriptionSnapshotHead readSnapshotHead() {
        if (snapshotLoader == null) {
            throw new IllegalStateException("Snapshot head is unavailable for repository-backed cache");
        }
        return snapshotLoader.readSnapshotHead();
    }

    /**
     * Atomically publishes the prepared snapshot to readers.
     *
     * @throws IllegalStateException if no snapshot was prepared or the snapshot is empty
     */
    public void activate() {
        var snapshot = preparedSnapshot.getAndSet(null);
        if (snapshot == null) {
            throw new IllegalStateException("No prepared subscription snapshot available");
        }
        if (snapshot.isEmpty()) {
            throw new IllegalStateException("Cannot activate empty subscription snapshot");
        }
        activeSnapshot.set(snapshot);
        snapshotUpToDate.set(true);
    }

    @Override
    public Optional<SubscriptionResource> getById(String subscriptionId) {
        if (subscriptionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(activeSnapshot.get().subscriptionsById().get(subscriptionId));
    }

    @Override
    public List<SubscriptionResource> findByEnvironmentAndEventType(String environment, String eventType) {
        if (environment == null || eventType == null) {
            return List.of();
        }
        return activeSnapshot.get().findByEnvironmentAndEventType(environment, eventType);
    }

    @Override
    /**
     * Indicates whether a the cache has an active snapshot and is ready for use.
     *
     * @return {@code true} if an active snapshot with an ID exists
     */
    public boolean isReady() {
        return activeSnapshot.get().snapshotId() != null;
    }

    /**
     * Indicates whether the cache is up to date with the latest activated snapshot.
     *
     * @return {@code true} after successful activation of the latest prepared snapshot
     */
    public boolean isCacheUpToDate() {
        return snapshotUpToDate.get();
    }

}