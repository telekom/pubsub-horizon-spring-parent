// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.cache.service.IndexedSubscriptionSnapshot.SnapshotVersion;
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
    private final AtomicReference<ActiveState> activeState = new AtomicReference<>(
        new ActiveState(IndexedSubscriptionSnapshot.empty(), null, Status.NOT_INITIALIZED, false));
    private final AtomicReference<IndexedSubscriptionSnapshot> preparedSnapshot = new AtomicReference<>();

    public enum Status {
        NOT_INITIALIZED, FRESH, STALE
    }

    private record ActiveState(IndexedSubscriptionSnapshot snapshot, SnapshotVersion authorizedVersion,
                               Status status, boolean authorizationRequired) {

        private ActiveState activated(IndexedSubscriptionSnapshot nextSnapshot) {
            var nextHead = nextSnapshot.version();
            var existingHead = snapshot.version();
            var usable = !authorizationRequired || nextHead.equals(authorizedVersion)
                || status == Status.FRESH && nextHead.equals(existingHead);
            return new ActiveState(nextSnapshot, authorizedVersion,
                usable ? Status.FRESH : Status.STALE, authorizationRequired);
        }
    }

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

        var requestedVersion = SnapshotVersion.from(snapshotHead);
        var prepared = preparedSnapshot.get();
        if (prepared != null && Objects.equals(requestedVersion, prepared.version())) {
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
            if (isReady() && Objects.equals(activeState.get().snapshot().version(),
                    SnapshotVersion.from(snapshotHead))) {
                activeState.updateAndGet(previous -> previous.activated(previous.snapshot()));
                return;
            }
            throw new IllegalStateException("No prepared subscription snapshot available");
        }

        var requestedVersion = SnapshotVersion.from(snapshotHead);
        if (!Objects.equals(prepared.version(), requestedVersion)) {
            throw new IllegalStateException("Prepared subscription snapshot does not match snapshot head to activate");
        }
        if (prepared.isEmpty()) {
            throw new SubscriptionCacheSnapshotException("Cannot activate empty subscription snapshot");
        }
        if (Objects.equals(prepared.version(), activeState.get().snapshot().version())) {
            activeState.updateAndGet(previous -> previous.activated(previous.snapshot()));
            return;
        }
        activeState.updateAndGet(previous -> previous.activated(prepared));
        log.debug("Activated local subscription snapshot {} with {} subscriptions",
            prepared.snapshotId(), prepared.subscriptionsById().size());
    }

    public void authorize(SubscriptionSnapshotHead head) {
        var authorizedVersion = completeVersion(head);
        activeState.updateAndGet(previous -> new ActiveState(
            previous.snapshot(), authorizedVersion, previous.status(), true));
    }

    public void activationFailed(SubscriptionSnapshotHead head) {
        var failedHead = completeVersion(head);
        activeState.updateAndGet(previous -> !failedHead.equals(previous.authorizedVersion())
            ? previous
            : new ActiveState(previous.snapshot(), previous.authorizedVersion(),
                previous.snapshot().snapshotId() == null ? Status.NOT_INITIALIZED : Status.STALE, true));
    }

    public void disconnected() {
        activeState.updateAndGet(previous -> new ActiveState(previous.snapshot(), null,
            previous.snapshot().snapshotId() == null ? Status.NOT_INITIALIZED : Status.STALE, true));
    }

    public Status status() {
        return activeState.get().status();
    }

    public boolean isFresh() {
        return status() == Status.FRESH;
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
            && !Objects.equals(prepared.version(), activeState.get().snapshot().version());
    }

    private static SnapshotVersion completeVersion(SubscriptionSnapshotHead head) {
        return SnapshotVersion.from(Objects.requireNonNull(head, "head must not be null")).requireComplete();
    }

    @Override
    public Optional<SubscriptionResource> getById(String subscriptionId) {
        if (subscriptionId == null) {
            return Optional.empty();
        }
        var snapshot = activeState.get().snapshot();
        var result = snapshot.getById(subscriptionId);
        log.debug("Read local subscription snapshot {} by subscription ID {}: found={}",
            snapshot.snapshotId(), subscriptionId, result.isPresent());
        return result;
    }

    @Override
    public List<SubscriptionResource> findByEnvironmentAndEventType(String environment, String eventType) {
        if (environment == null || eventType == null) {
            return List.of();
        }
        var snapshot = activeState.get().snapshot();
        var result = snapshot.findByEnvironmentAndEventType(environment, eventType);
        log.debug("Read local subscription snapshot {} by environment {} and event type {}: matches={}",
            snapshot.snapshotId(), environment, eventType, result.size());
        return result;
    }

    /**
        * Indicates whether the cache has an active snapshot and is ready for use.
     *
     * @return {@code true} if an active snapshot with an ID exists
     */
        @Override
    public boolean isReady() {
        return activeState.get().snapshot().snapshotId() != null;
    }

}