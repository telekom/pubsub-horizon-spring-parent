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
    private final AtomicReference<CacheState> cacheState = new AtomicReference<>(
        new CacheState(IndexedSubscriptionSnapshot.empty(), null, null, Status.UNINITIALIZED, false, false));

    public enum Status {
        UNINITIALIZED, FRESH, STALE
    }

    private record CacheState(IndexedSubscriptionSnapshot activeSnapshot,
                              IndexedSubscriptionSnapshot preparedSnapshot,
                              SnapshotVersion activationHeadVersion,
                              Status status, boolean activationHeadMustMatch,
                              boolean localReadsAllowedDuringSuspension) {

        private CacheState withPreparedSnapshot(IndexedSubscriptionSnapshot nextPreparedSnapshot) {
            return new CacheState(activeSnapshot, nextPreparedSnapshot, activationHeadVersion, status,
                activationHeadMustMatch, localReadsAllowedDuringSuspension);
        }

        private CacheState withoutPreparedSnapshot() {
            return withPreparedSnapshot(null);
        }

        private CacheState withActivatedSnapshot(IndexedSubscriptionSnapshot nextSnapshot) {
            var nextVersion = nextSnapshot.version();
            var activeVersion = activeSnapshot.version();
            var freshAfterActivation = !activationHeadMustMatch || nextVersion.equals(activationHeadVersion)
                || status == Status.FRESH && nextVersion.equals(activeVersion);
            return new CacheState(nextSnapshot, preparedSnapshot, activationHeadVersion,
                freshAfterActivation ? Status.FRESH : Status.STALE, activationHeadMustMatch,
                freshAfterActivation ? false : localReadsAllowedDuringSuspension);
        }

        private CacheState withActivationHeadVersion(SnapshotVersion nextActivationHeadVersion) {
            return new CacheState(activeSnapshot, preparedSnapshot, nextActivationHeadVersion, status, true,
                localReadsAllowedDuringSuspension);
        }

        private CacheState withActivationFailure(SnapshotVersion failedVersion) {
            return !failedVersion.equals(activationHeadVersion)
                ? this
                : new CacheState(activeSnapshot, preparedSnapshot, activationHeadVersion, statusWhenNotFresh(), true,
                    localReadsAllowedDuringSuspension);
        }

        private CacheState clearActivationHeadVersion() {
            return new CacheState(activeSnapshot, preparedSnapshot, null, statusWhenNotFresh(), true, false);
        }

        private CacheState withSuspendedConnection() {
            var canContinueServing = localReadsAllowedDuringSuspension
                || status == Status.FRESH && hasActiveSnapshot();
            return new CacheState(activeSnapshot, preparedSnapshot, activationHeadVersion, statusWhenNotFresh(),
                activationHeadMustMatch, canContinueServing);
        }

        private boolean hasPendingSnapshot() {
            return preparedSnapshot != null
                && !Objects.equals(preparedSnapshot.version(), activeSnapshot.version());
        }

        private Status statusWhenNotFresh() {
            return hasActiveSnapshot() ? Status.STALE : Status.UNINITIALIZED;
        }

        private boolean hasActiveSnapshot() {
            return activeSnapshot.snapshotId() != null;
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
     * prepared snapshot. The active snapshot remains unchanged, including its
    * freshness while a newer activation-head snapshot is being loaded.
     *
     * @param snapshotHead metadata identifying the snapshot to load
     * @throws IllegalArgumentException if the head has no snapshot ID
     * @throws SubscriptionCacheSnapshotException if the snapshot cannot be validated
     */
    public synchronized void prepare(SubscriptionSnapshotHead snapshotHead) {
        requireSnapshotId(snapshotHead);

        var requestedVersion = SnapshotVersion.from(snapshotHead);
        var prepared = cacheState.get().preparedSnapshot();
        if (prepared != null && Objects.equals(requestedVersion, prepared.version())) {
            return;
        }

        var snapshot = snapshotLoader.load(snapshotHead);
        cacheState.updateAndGet(previous -> previous.withPreparedSnapshot(snapshot));
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
     * Atomically publishes the prepared snapshot when all snapshot version fields
     * match the expected head. An already active matching snapshot can be reused.
     *
     * @param snapshotHead expected prepared snapshot head
     * @throws IllegalArgumentException if the snapshot head is missing a snapshot ID
     * @throws IllegalStateException if the prepared snapshot does not match the requested head, or no snapshot was prepared
     * @throws SubscriptionCacheSnapshotException if the prepared snapshot is empty
     */
    public synchronized void activate(SubscriptionSnapshotHead snapshotHead) {
        requireSnapshotId(snapshotHead);

        var currentState = cacheState.get();
        var prepared = currentState.preparedSnapshot();
        if (prepared == null) {
            if (currentState.hasActiveSnapshot() && Objects.equals(currentState.activeSnapshot().version(),
                    SnapshotVersion.from(snapshotHead))) {
                cacheState.updateAndGet(previous -> previous.withActivatedSnapshot(previous.activeSnapshot()));
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
        if (Objects.equals(prepared.version(), currentState.activeSnapshot().version())) {
            cacheState.updateAndGet(previous -> previous.withActivatedSnapshot(previous.activeSnapshot()));
            return;
        }
        cacheState.updateAndGet(previous -> previous.withActivatedSnapshot(prepared));
        log.debug("Activated local subscription snapshot {} with {} subscriptions",
            prepared.snapshotId(), prepared.subscriptionsById().size());
    }

    public void setActivationHead(SubscriptionSnapshotHead head) {
        var activationHeadVersion = completeVersion(head);
        cacheState.updateAndGet(previous -> previous.withActivationHeadVersion(activationHeadVersion));
    }

    public void activationFailed(SubscriptionSnapshotHead head) {
        var failedVersion = completeVersion(head);
        cacheState.updateAndGet(previous -> previous.withActivationFailure(failedVersion));
    }

    public void disconnected() {
        cacheState.updateAndGet(CacheState::clearActivationHeadVersion);
    }

    public void suspended() {
        cacheState.updateAndGet(CacheState::withSuspendedConnection);
    }

    public Status status() {
        return cacheState.get().status();
    }

    /**
     * Indicates whether the active snapshot matches the current activation head.
     *
     * @return {@code true} if the active snapshot is current
     */
    public boolean isActiveSnapshotUpToDate() {
        return status() == Status.FRESH;
    }

    /**
     * Indicates whether the local snapshot may currently serve reads.
     *
     * @return {@code true} if local reads are allowed
     */
    public boolean canServeLocalReads() {
        var state = cacheState.get();
        return state.hasActiveSnapshot()
            && (state.status() == Status.FRESH || state.localReadsAllowedDuringSuspension());
    }

    /**
     * Discards the currently prepared snapshot without changing the active snapshot.
     */
    public synchronized void discardPreparedSnapshot() {
        cacheState.updateAndGet(CacheState::withoutPreparedSnapshot);
    }

    /**
     * Indicates whether a prepared snapshot differs from the active snapshot.
     *
     * @return {@code true} if activation is still pending
     */
    public boolean hasPendingSnapshot() {
        return cacheState.get().hasPendingSnapshot();
    }

    private static void requireSnapshotId(SubscriptionSnapshotHead snapshotHead) {
        if (snapshotHead == null || snapshotHead.getSnapshotId() == null
                || snapshotHead.getSnapshotId().isBlank()) {
            throw new IllegalArgumentException("SnapshotHead must contain a snapshotId");
        }
    }

    private static SnapshotVersion completeVersion(SubscriptionSnapshotHead head) {
        return SnapshotVersion.from(Objects.requireNonNull(head, "head must not be null")).requireComplete();
    }

    @Override
    public Optional<SubscriptionResource> getById(String subscriptionId) {
        if (subscriptionId == null) {
            return Optional.empty();
        }
        var snapshot = cacheState.get().activeSnapshot();
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
        var snapshot = cacheState.get().activeSnapshot();
        var result = snapshot.findByEnvironmentAndEventType(environment, eventType);
        log.debug("Read local subscription snapshot {} by environment {} and event type {}: matches={}",
            snapshot.snapshotId(), environment, eventType, result.size());
        return result;
    }

    /**
     * Indicates whether an active snapshot has been loaded, independently of freshness.
     *
     * @return {@code true} if an active snapshot with an ID exists
     */
    public boolean isInitialized() {
        return cacheState.get().hasActiveSnapshot();
    }

    /**
     * Returns the ID of the active local snapshot, if one has been loaded.
     *
     * @return the active local snapshot ID
     */
    public Optional<String> localSnapshotId() {
        return Optional.ofNullable(cacheState.get().activeSnapshot().snapshotId());
    }

    /**
     * Indicates whether this reader can currently serve requests.
     *
     * @return {@code true} if local reads are allowed
     */
    @Override
    public boolean isReady() {
        return canServeLocalReads();
    }

}