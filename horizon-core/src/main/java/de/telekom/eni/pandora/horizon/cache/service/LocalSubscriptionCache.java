// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.exception.SubscriptionSnapshotException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pod-local subscription cache backed by persisted subscription snapshots.
 *
 * <p>Snapshots are loaded into an immutable set of indexes during
 * {@link #prepare(SubscriptionSnapshotHead)}. A prepared snapshot becomes visible
 * to readers only after a successful call to {@link #activate(String)}.</p>
 */
public class LocalSubscriptionCache implements SubscriptionCacheReader {

    private final MongoSubscriptionSnapshotLoader snapshotLoader;
    private final AtomicReference<IndexedSubscriptionSnapshot> activeSnapshot = new AtomicReference<>(IndexedSubscriptionSnapshot.empty());
    private final AtomicReference<IndexedSubscriptionSnapshot> preparedSnapshot = new AtomicReference<>();

    /**
     * Creates a cache backed by persisted subscription snapshots.
     *
     * @param snapshotLoader loader for snapshot metadata and entries
     */
    public LocalSubscriptionCache(MongoSubscriptionSnapshotLoader snapshotLoader) {
        this.snapshotLoader = Objects.requireNonNull(snapshotLoader, "snapshotLoader must not be null");
    }

    /**
    * Loads and prepares the persisted snapshot identified by the supplied head.
    * Preparation is skipped only when all head metadata matches the already
    * prepared snapshot. The active snapshot remains unchanged.
     *
     * @param snapshotHead metadata identifying the snapshot to load
    * @throws IllegalArgumentException if the head has no snapshot ID
    * @throws SubscriptionSnapshotException if the snapshot cannot be validated
     */
    public synchronized void prepare(SubscriptionSnapshotHead snapshotHead) {
        if (snapshotHead == null || snapshotHead.getSnapshotId() == null
                || snapshotHead.getSnapshotId().isBlank()) {
            throw new IllegalArgumentException("SnapshotHead must contain a snapshotId");
        }

        var requestedMetadata = IndexedSubscriptionSnapshot.SnapshotMetadata.from(snapshotHead);
        var prepared = preparedSnapshot.get();
        if (prepared != null && Objects.equals(requestedMetadata, prepared.metadata())) {
            return;
        }

        preparedSnapshot.set(snapshotLoader.load(snapshotHead));
    }

    /**
     * Reads the currently published snapshot metadata.
     *
     * @return the current snapshot head
     */
    public SubscriptionSnapshotHead readSnapshotHead() {
        return snapshotLoader.readSnapshotHead();
    }

    /**
    * Atomically publishes the prepared snapshot when it matches the expected snapshot ID.
    * Reusing an ID with changed head metadata is supported because activation compares
    * the complete immutable metadata before treating the operation as idempotent.
     *
     * @param snapshotId expected prepared snapshot ID
     * @throws IllegalArgumentException if the snapshot ID is blank
     * @throws IllegalStateException if the prepared snapshot does not match the requested ID, or no snapshot was prepared
    * @throws SubscriptionSnapshotException if the prepared snapshot is empty
     */
    public synchronized void activate(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("SnapshotId must not be blank");
        }

        var prepared = preparedSnapshot.get();
        if (prepared == null) {
            if (isReady() && Objects.equals(activeSnapshot.get().snapshotId(), snapshotId)) {
                return;
            }
            throw new IllegalStateException("No prepared subscription snapshot available");
        }

        if (!Objects.equals(prepared.snapshotId(), snapshotId)) {
            throw new IllegalStateException("Prepared subscription snapshot does not match snapshotId to activate");
        }
        if (prepared.isEmpty()) {
            throw new SubscriptionSnapshotException("Cannot activate empty subscription snapshot");
        }
        if (Objects.equals(prepared.metadata(), activeSnapshot.get().metadata())) {
            return;
        }
        activeSnapshot.set(prepared);
    }

    /**
     * Discards the currently prepared snapshot without changing the active snapshot.
     */
    public synchronized void discardPreparedSnapshot() {
        preparedSnapshot.set(null);
    }

    /**
     * Indicates whether a prepared snapshot differs from the active snapshot.
     *
     * @return {@code true} if activation is still pending
     */
    public boolean hasPendingSnapshot() {
        var prepared = preparedSnapshot.get();
        return prepared != null
            && !Objects.equals(prepared.metadata(), activeSnapshot.get().metadata());
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

    /**
        * Indicates whether the cache has an active snapshot and is ready for use.
     *
     * @return {@code true} if an active snapshot with an ID exists
     */
        @Override
    public boolean isReady() {
        return activeSnapshot.get().snapshotId() != null;
    }

}