// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pod-local subscription cache backed by persisted subscription snapshots.
 *
 * <p>Snapshots are loaded into an immutable set of indexes during
 * {@link #prepare(SubscriptionSnapshotHead)}. A prepared snapshot becomes visible
 * to readers only after a successful call to {@link #activate(SubscriptionSnapshotHead)}.</p>
 */
@Slf4j
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
    * @throws SubscriptionCacheSnapshotException if the snapshot cannot be validated
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

        var snapshot = snapshotLoader.load(snapshotHead);
        preparedSnapshot.set(snapshot);
        log.debug("Prepared local subscription snapshot {} with {} subscriptions",
            snapshot.snapshotId(), snapshot.subscriptionsById().size());
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
     * Activation compares the complete immutable head metadata before publishing
     * the prepared snapshot.
     *
     * @param snapshotHead expected prepared snapshot head
     * @throws IllegalArgumentException if the snapshot head is missing a snapshot ID
     * @throws IllegalStateException if the prepared snapshot does not match the requested head, or no snapshot was prepared
    * @throws SubscriptionCacheSnapshotException if the prepared snapshot is empty
     */
    public synchronized void activate(SubscriptionSnapshotHead snapshotHead) {
        if (snapshotHead == null || snapshotHead.getSnapshotId() == null
                || snapshotHead.getSnapshotId().isBlank()) {
            throw new IllegalArgumentException("SnapshotHead must contain a snapshotId");
        }

        var prepared = preparedSnapshot.get();
        if (prepared == null) {
            if (isReady() && Objects.equals(activeSnapshot.get().metadata(),
                    IndexedSubscriptionSnapshot.SnapshotMetadata.from(snapshotHead))) {
                return;
            }
            throw new IllegalStateException("No prepared subscription snapshot available");
        }

        var requestedMetadata = IndexedSubscriptionSnapshot.SnapshotMetadata.from(snapshotHead);
        if (!Objects.equals(prepared.metadata(), requestedMetadata)) {
            throw new IllegalStateException("Prepared subscription snapshot does not match snapshot head to activate");
        }
        if (prepared.isEmpty()) {
            throw new SubscriptionCacheSnapshotException("Cannot activate empty subscription snapshot");
        }
        if (Objects.equals(prepared.metadata(), activeSnapshot.get().metadata())) {
            return;
        }
        activeSnapshot.set(prepared);
        log.debug("Activated local subscription snapshot {} with {} subscriptions",
            prepared.snapshotId(), prepared.subscriptionsById().size());
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
        var snapshot = activeSnapshot.get();
        var result = snapshot.getById(subscriptionId);
        log.debug("Read local subscription snapshot {} by subscription ID: found={}",
            snapshot.snapshotId(), result.isPresent());
        return result;
    }

    @Override
    public List<SubscriptionResource> findByEnvironmentAndEventType(String environment, String eventType) {
        if (environment == null || eventType == null) {
            return List.of();
        }
        var snapshot = activeSnapshot.get();
        var result = snapshot.findByEnvironmentAndEventType(environment, eventType);
        log.debug("Read local subscription snapshot {} by environment and event type: matches={}",
            snapshot.snapshotId(), result.size());
        return result;
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