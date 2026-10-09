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
 * <p>Snapshots are loaded into structurally immutable lookup indexes during
 * {@link #prepare(SubscriptionSnapshotHead)}. The contained {@link SubscriptionResource} objects and their nested
 * models remain mutable and are shared with readers; consumers must treat them as read-only. A prepared snapshot
 * becomes visible to readers only after a successful call to {@link #activate(SubscriptionSnapshotHead)}.</p>
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

    /** Freshness state of the active local snapshot. */
    public enum Status {
        UNINITIALIZED,
        FRESH,
        STALE
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
     * @param staleSince instant when the cache most recently became stale; {@code null} unless status is STALE
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
     * @throws NullPointerException if {@code snapshotLoader} is {@code null}
     */
    public LocalSubscriptionCache(MongoSubscriptionSnapshotLoader snapshotLoader) {
        this(snapshotLoader, Duration.ZERO);
    }

    /**
     * Creates a cache whose stale snapshot may serve reads for the given grace period.
     *
     * @param snapshotLoader loader for snapshot metadata and entries
     * @param staleCacheReadGracePeriod duration for which stale local data may serve reads; zero disables stale reads
     * @throws NullPointerException if the loader or grace period is {@code null}
     * @throws IllegalArgumentException if the grace period is negative
     */
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
     * Loads and prepares the persisted snapshot identified by the supplied head without changing the active snapshot.
     * Preparation is skipped when the supplied version matches the active or already prepared version according to
     * {@link SubscriptionSnapshotHeads#isSameSnapshot(SubscriptionSnapshotHead, SubscriptionSnapshotHead)}. The
     * active snapshot and its current freshness status are retained while a newer head is being loaded.
     *
     * @param snapshotHead metadata identifying the snapshot to load
     * @throws IllegalArgumentException if the head is {@code null} or has no non-blank snapshot ID
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
     * Returns the number of snapshots successfully loaded and prepared by this cache.
     *
     * @return successful snapshot load count
     */
    public long snapshotLoadCount() {
        return snapshotLoads.get();
    }

    /**
     * Returns the cumulative time spent loading snapshots successfully.
     *
     * @return total successful snapshot-load time in nanoseconds
     */
    public long snapshotLoadTotalNanos() {
        return snapshotLoadNanos.get();
    }

    /**
     * Returns the duration of the most recent successful snapshot load.
     *
     * @return last successful load duration in nanoseconds, or {@code 0} if no load has succeeded
     */
    public long lastSnapshotLoadNanos() {
        return lastSnapshotLoadNanos;
    }

    /**
     * Returns the number of failed activation attempts, including failures while loading the requested snapshot.
     *
     * @return failed activation count
     */
    public long activationFailureCount() {
        return activationFailures.get();
    }

    /**
     * Atomically publishes a prepared snapshot when its identity matches the supplied head. Identity always compares
     * {@code snapshotId} and {@code documentCount}; {@code revision} and {@code sourceHash} are compared only when set
     * on both versions, and {@code createdAt} is not part of identity. An already active matching snapshot is reused
     * without touching a pending prepared snapshot.
     *
     * @param snapshotHead validated head authorizing the snapshot to activate; it must have a non-blank snapshot ID
     * @throws IllegalArgumentException if the snapshot head is {@code null} or has no non-blank snapshot ID
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

    /**
     * Returns a stage that completes when this cache first reaches FRESH.
     *
     * <p>The stage stays completed after later transitions to STALE. It represents a first-fresh milestone, not the
     * current cache status or completion of the initial head read.</p>
     *
     * @return stage completed by the first FRESH activation
     */
    public CompletionStage<Void> firstFreshSnapshot() {
        return firstFreshSnapshot.minimalCompletionStage();
    }

    private void completeFirstFreshSnapshot() {
        if (status() == Status.FRESH) {
            firstFreshSnapshot.complete(null);
        }
    }

    /**
     * Sets the complete head version against which a subsequent activation is evaluated.
     *
     * <p>This method does not load or activate a snapshot and does not immediately change the current status. In
     * particular, an existing FRESH snapshot remains FRESH while a newer expected version is being prepared.</p>
     *
     * @param head expected active snapshot head
     * @throws SubscriptionCacheSnapshotException if the head is invalid
     */
    public void setActivationHead(SubscriptionSnapshotHead head) {
        var activationHeadVersion = completeVersion(head);
        cacheState.updateAndGet(previous -> previous.withActivationHeadVersion(activationHeadVersion));
    }

    /**
     * Records a failed activation attempt and marks the cache STALE if this head is still expected.
     *
     * @param head head whose activation failed
     * @throws SubscriptionCacheSnapshotException if the head is invalid
     */
    public void activationFailed(SubscriptionSnapshotHead head) {
        activationFailures.incrementAndGet();
        var failedVersion = completeVersion(head);
        var now = clock.instant();
        cacheState.updateAndGet(previous -> previous.withActivationFailure(failedVersion, now));
    }

    /**
     * Clears the expected head because no head source is available.
     *
     * <p>An existing active snapshot becomes STALE; a cache without an active snapshot remains UNINITIALIZED.</p>
     */
    public void disconnected() {
        var now = clock.instant();
        cacheState.updateAndGet(previous -> previous.clearActivationHeadVersion(now));
    }

    /**
     * Marks an active cache STALE without clearing the expected head; an uninitialized cache remains UNINITIALIZED.
     */
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

    /**
     * Returns the current freshness status of the local snapshot.
     *
     * @return current cache status
     */
    public Status status() {
        return cacheState.get().status();
    }

    /**
     * Indicates whether the cache's current status is FRESH.
     *
     * <p>This reports the status flag, not a direct comparison with the latest expected head. The cache may remain FRESH
     * while a newer head is being prepared.</p>
     *
     * @return {@code true} if the current cache status is FRESH
     */
    public boolean isActiveSnapshotUpToDate() {
        return status() == Status.FRESH;
    }

    /**
     * Indicates whether the active snapshot has the same identity as the supplied head, regardless of freshness.
     *
     * @param head head to compare with the active snapshot
     * @return {@code true} if the head references the active snapshot (see {@link SubscriptionSnapshotHeads#isSameSnapshot})
     */
    public boolean isActiveSnapshot(SubscriptionSnapshotHead head) {
        var state = cacheState.get();
        return head != null && state.hasActiveSnapshot()
            && state.activeSnapshot().version().matches(SnapshotVersion.from(head));
    }

    /**
     * Indicates whether an expected head is known and the active snapshot has a different identity.
     *
     * @return {@code true} if an expected head is known and the active snapshot does not match it
     */
    public boolean isBehindActivationHead() {
        var state = cacheState.get();
        return state.activationHeadVersion() != null
            && !state.activationHeadVersion().matches(state.activeSnapshot().version());
    }

    /**
     * Indicates whether the local snapshot may currently serve reads.
     *
     * <p>Reads are allowed for a FRESH snapshot, or for a STALE snapshot while its configured grace period has not
     * expired. UNINITIALIZED caches cannot serve local reads.</p>
     *
     * @return {@code true} if local reads are allowed at the current time
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
     * Discards the prepared snapshot only if it has the identity of the supplied head.
     *
     * <p>A prepared snapshot of a different version, e.g. a PREPARED preload, is kept.</p>
     *
     * @param head head whose prepared snapshot should be discarded
     * @throws IllegalArgumentException if the head is {@code null} or has no non-blank snapshot ID
     */
    public synchronized void discardPreparedSnapshot(SubscriptionSnapshotHead head) {
        requireSnapshotId(head);
        var version = SnapshotVersion.from(head);
        cacheState.updateAndGet(previous -> previous.preparedSnapshot() != null
            && version.matches(previous.preparedSnapshot().version())
            ? previous.withoutPreparedSnapshot()
            : previous);
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

    /**
     * Looks up a subscription in the active local snapshot.
     *
     * @param subscriptionId subscription identifier; {@code null} returns empty
     * @return the matching shared resource, or empty when absent; callers must not mutate the resource
     */
    @Override
    public Optional<SubscriptionResource> getById(String subscriptionId) {
        if (subscriptionId == null) {
            return Optional.empty();
        }
        var snapshot = cacheState.get().activeSnapshot();
        var result = snapshot.getById(subscriptionId);
        return result;
    }

    /**
     * Looks up subscriptions in the active local snapshot by environment and event type.
     *
     * @param environment subscription environment
     * @param eventType subscription event type
     * @return matching shared resources, or an empty list when either argument is {@code null} or no entries match;
     *         callers must not mutate the resources
     */
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