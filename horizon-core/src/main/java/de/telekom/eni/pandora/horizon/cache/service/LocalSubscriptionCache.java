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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
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
    private final Duration staleCacheReadGracePeriod;
    private final Clock clock;
    private final CompletableFuture<Void> firstFreshSnapshot = new CompletableFuture<>();
    private final AtomicReference<CacheState> cacheState = new AtomicReference<>(
        new CacheState(IndexedSubscriptionSnapshot.empty(), null, null, Status.UNINITIALIZED, false, null));
    private volatile Instant activatedAt;
    private final AtomicLong snapshotLoads = new AtomicLong();
    private final AtomicLong snapshotLoadNanos = new AtomicLong();
    private volatile long lastSnapshotLoadNanos;
    private final AtomicLong activationFailures = new AtomicLong();

    public enum Status {
        UNINITIALIZED, FRESH, STALE
    }

    /**
     * Point-in-time view of the cache state for health reporting; absent values are {@code null}.
     *
     * @param status freshness status
     * @param localReadsAllowed whether reads may currently be served locally
     * @param activeSnapshotId ID of the active snapshot
     * @param subscriptionCount number of subscriptions in the active snapshot
     * @param activatedAt last successful activation of the active snapshot
     * @param expectedSnapshotId snapshot ID of the expected (activation) head
     * @param pendingSnapshotId ID of a prepared snapshot that is not yet active
     * @param staleSince time since when the cache is stale
     */
    public record Diagnostics(Status status, boolean localReadsAllowed, String activeSnapshotId, int subscriptionCount,
                              Instant activatedAt, String expectedSnapshotId, String pendingSnapshotId,
                              Instant staleSince) {
    }

    private record CacheState(IndexedSubscriptionSnapshot activeSnapshot,
                              IndexedSubscriptionSnapshot preparedSnapshot,
                              SnapshotVersion activationHeadVersion,
                              Status status, boolean activationHeadMustMatch,
                              Instant staleSince) {

        private CacheState withPreparedSnapshot(IndexedSubscriptionSnapshot nextPreparedSnapshot) {
            return new CacheState(activeSnapshot, nextPreparedSnapshot, activationHeadVersion, status,
                activationHeadMustMatch, staleSince);
        }

        private CacheState withoutPreparedSnapshot() {
            return withPreparedSnapshot(null);
        }

        private CacheState withActivatedSnapshot(IndexedSubscriptionSnapshot nextSnapshot, Instant now) {
            var nextVersion = nextSnapshot.version();
            var activeVersion = activeSnapshot.version();
            var freshAfterActivation = !activationHeadMustMatch || nextVersion.matches(activationHeadVersion)
                || status == Status.FRESH && nextVersion.matches(activeVersion);
            var nextStatus = freshAfterActivation ? Status.FRESH : Status.STALE;
            return new CacheState(nextSnapshot, preparedSnapshot, activationHeadVersion,
                nextStatus, activationHeadMustMatch,
                staleSinceFor(nextStatus, staleSince, now));
        }

        private CacheState withActivationHeadVersion(SnapshotVersion nextActivationHeadVersion) {
            return new CacheState(activeSnapshot, preparedSnapshot, nextActivationHeadVersion, status, true,
                staleSince);
        }

        private CacheState withActivationFailure(SnapshotVersion failedVersion, Instant now) {
            return !failedVersion.matches(activationHeadVersion)
                ? this
                : withStaleStatus(now);
        }

        private CacheState clearActivationHeadVersion(Instant now) {
            return new CacheState(activeSnapshot, preparedSnapshot, null, statusWhenNotFresh(), true,
                staleSinceFor(statusWhenNotFresh(), staleSince, now));
        }

        private CacheState withStaleStatus(Instant now) {
            var nextStatus = statusWhenNotFresh();
            return new CacheState(activeSnapshot, preparedSnapshot, activationHeadVersion, nextStatus,
                activationHeadMustMatch, staleSinceFor(nextStatus, staleSince, now));
        }

        private static Instant staleSinceFor(Status nextStatus, Instant currentStaleSince, Instant now) {
            if (nextStatus != Status.STALE) {
                return null;
            }
            return currentStaleSince == null ? now : currentStaleSince;
        }

        private boolean hasPendingSnapshot() {
            return preparedSnapshot != null
                && !preparedSnapshot.version().matches(activeSnapshot.version());
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
        this(snapshotLoader, Duration.ZERO);
    }

    /** Creates a cache whose stale snapshot may serve reads for the given grace period. */
    public LocalSubscriptionCache(MongoSubscriptionSnapshotLoader snapshotLoader, Duration staleCacheReadGracePeriod) {
        this(snapshotLoader, staleCacheReadGracePeriod, Clock.systemUTC());
    }

    LocalSubscriptionCache(MongoSubscriptionSnapshotLoader snapshotLoader, Duration staleCacheReadGracePeriod, Clock clock) {
        this.snapshotLoader = Objects.requireNonNull(snapshotLoader, "snapshotLoader must not be null");
        this.staleCacheReadGracePeriod = Objects.requireNonNull(staleCacheReadGracePeriod,
            "staleCacheReadGracePeriod must not be null");
        if (staleCacheReadGracePeriod.isNegative()) {
            throw new IllegalArgumentException("staleCacheReadGracePeriod must not be negative");
        }
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Loads and prepares the persisted snapshot identified by the supplied head.
     * Preparation is skipped when all head metadata matches the active or the already
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
        var currentState = cacheState.get();
        var prepared = currentState.preparedSnapshot();
        if (prepared != null && requestedVersion.matches(prepared.version())) {
            return;
        }
        // Keeps a pending preload of a newer snapshot when the active one is requested again, e.g. after a reconnect.
        if (currentState.hasActiveSnapshot() && requestedVersion.matches(currentState.activeSnapshot().version())) {
            return;
        }

        var loadStart = System.nanoTime();
        var snapshot = snapshotLoader.load(snapshotHead);
        var loadNanos = System.nanoTime() - loadStart;
        snapshotLoads.incrementAndGet();
        snapshotLoadNanos.addAndGet(loadNanos);
        lastSnapshotLoadNanos = loadNanos;
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

    /** Number of successfully loaded snapshots. */
    public long snapshotLoadCount() {
        return snapshotLoads.get();
    }

    /** Total time spent loading snapshots successfully, in nanoseconds. */
    public long snapshotLoadTotalNanos() {
        return snapshotLoadNanos.get();
    }

    /** Duration of the last successful snapshot load, in nanoseconds; 0 if none. */
    public long lastSnapshotLoadNanos() {
        return lastSnapshotLoadNanos;
    }

    /** Number of failed snapshot activations, including failed snapshot loads. */
    public long activationFailureCount() {
        return activationFailures.get();
    }

    /**
     * Atomically publishes the prepared snapshot when all snapshot version fields
     * match the expected head. An already active matching snapshot is reused without
     * touching a pending prepared snapshot.
     *
     * @param snapshotHead expected prepared snapshot head
     * @throws IllegalArgumentException if the snapshot head is missing a snapshot ID
     * @throws IllegalStateException if the prepared snapshot does not match the requested head, or no snapshot was prepared
     * @throws SubscriptionCacheSnapshotException if the prepared snapshot is empty
     */
    public synchronized void activate(SubscriptionSnapshotHead snapshotHead) {
        requireSnapshotId(snapshotHead);

        var requestedVersion = SnapshotVersion.from(snapshotHead);
        var currentState = cacheState.get();
        if (currentState.hasActiveSnapshot() && requestedVersion.matches(currentState.activeSnapshot().version())) {
            var now = clock.instant();
            cacheState.updateAndGet(previous -> previous.withActivatedSnapshot(previous.activeSnapshot(), now));
            activatedAt = now;
            completeFirstFreshSnapshot();
            return;
        }

        var prepared = currentState.preparedSnapshot();
        if (prepared == null) {
            throw new IllegalStateException("No prepared subscription snapshot available");
        }
        if (!requestedVersion.matches(prepared.version())) {
            throw new IllegalStateException("Prepared subscription snapshot does not match snapshot head to activate");
        }
        if (prepared.isEmpty()) {
            throw new SubscriptionCacheSnapshotException("Cannot activate empty subscription snapshot");
        }
        var now = clock.instant();
        cacheState.updateAndGet(previous -> previous.withActivatedSnapshot(prepared, now));
        activatedAt = now;
        completeFirstFreshSnapshot();
        log.debug("Activated local subscription snapshot {} with {} subscriptions",
            prepared.snapshotId(), prepared.subscriptionsById().size());
    }

    /** Completes when the cache becomes FRESH for the first time. */
    public CompletionStage<Void> firstFreshSnapshot() {
        return firstFreshSnapshot.minimalCompletionStage();
    }

    private void completeFirstFreshSnapshot() {
        if (status() == Status.FRESH) {
            firstFreshSnapshot.complete(null);
        }
    }

    /** Sets the expected active head; the cache is FRESH only while its active snapshot matches it. */
    public void setActivationHead(SubscriptionSnapshotHead head) {
        var activationHeadVersion = completeVersion(head);
        cacheState.updateAndGet(previous -> previous.withActivationHeadVersion(activationHeadVersion));
    }

    /** Marks the cache STALE if the failed head is still the expected head. */
    public void activationFailed(SubscriptionSnapshotHead head) {
        activationFailures.incrementAndGet();
        var failedVersion = completeVersion(head);
        var now = clock.instant();
        cacheState.updateAndGet(previous -> previous.withActivationFailure(failedVersion, now));
    }

    /** Clears the expected head because no head source is available; an active snapshot becomes STALE. */
    public void disconnected() {
        var now = clock.instant();
        cacheState.updateAndGet(previous -> previous.clearActivationHeadVersion(now));
    }

    /** Marks an initialized cache STALE without clearing the expected head. */
    public void suspended() {
        var now = clock.instant();
        cacheState.updateAndGet(previous -> previous.withStaleStatus(now));
    }

    /**
     * Returns a consistent snapshot of the cache state for health reporting.
     *
     * @return current diagnostics
     */
    public Diagnostics diagnostics() {
        var state = cacheState.get();
        var active = state.activeSnapshot();
        var expectedVersion = state.activationHeadVersion();
        return new Diagnostics(
            state.status(),
            canServeLocalReads(state, clock.instant()),
            active.snapshotId(),
            active.subscriptionsById().size(),
            state.hasActiveSnapshot() ? activatedAt : null,
            expectedVersion == null ? null : expectedVersion.snapshotId(),
            state.hasPendingSnapshot() ? state.preparedSnapshot().snapshotId() : null,
            state.staleSince());
    }

    /** Returns the current freshness status of the local snapshot. */
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
     * Indicates whether the active snapshot was loaded from the supplied head.
     *
     * @param head head to compare with the active snapshot
     * @return {@code true} if the head references the active snapshot (see {@link SubscriptionSnapshotHeads#isSameSnapshot})
     */
    public boolean isActiveSnapshot(SubscriptionSnapshotHead head) {
        var state = cacheState.get();
        return head != null && state.hasActiveSnapshot()
            && state.activeSnapshot().version().matches(SnapshotVersion.from(head));
    }

    /** Indicates whether an expected head is known and the active snapshot does not reference it. */
    public boolean isBehindActivationHead() {
        var state = cacheState.get();
        return state.activationHeadVersion() != null
            && !state.activationHeadVersion().matches(state.activeSnapshot().version());
    }

    /**
     * Indicates whether the local snapshot may currently serve reads.
     *
     * @return {@code true} if local reads are allowed
     */
    public boolean canServeLocalReads() {
        return canServeLocalReads(clock.instant());
    }

    boolean canServeLocalReads(Instant now) {
        return canServeLocalReads(cacheState.get(), now);
    }

    private boolean canServeLocalReads(CacheState state, Instant now) {
        if (!state.hasActiveSnapshot()) {
            return false;
        }
        if (state.status() == Status.FRESH) {
            return true;
        }
        return state.status() == Status.STALE
            && state.staleSince() != null
            && !staleCacheReadGracePeriod.isZero()
            && Duration.between(state.staleSince(), now).compareTo(staleCacheReadGracePeriod) < 0;
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
        return SnapshotVersion.from(SubscriptionSnapshotHeads.requireValid(head));
    }

    @Override
    public Optional<SubscriptionResource> getById(String subscriptionId) {
        if (subscriptionId == null) {
            return Optional.empty();
        }
        var snapshot = cacheState.get().activeSnapshot();
        var result = snapshot.getById(subscriptionId);
        return result;
    }

    @Override
    public List<SubscriptionResource> findByEnvironmentAndEventType(String environment, String eventType) {
        if (environment == null || eventType == null) {
            return List.of();
        }
        var snapshot = cacheState.get().activeSnapshot();
        var result = snapshot.findByEnvironmentAndEventType(environment, eventType);
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