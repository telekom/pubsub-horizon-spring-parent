// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.cache.service.SubscriptionSnapshotHeads;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reconciles ZooKeeper subscription heads with the pod-local cache.
 *
 * <p>{@code PREPARED} may preload a snapshot but never authorizes activation. A valid {@code ACTIVATED} head is
 * authoritative; an unavailable ACTIVATED head leaves the local cache without a confirmed head.
 * ZooKeeper-driven cache changes are committed through a {@link CacheCommitGate} so that the owning watcher can reject
 * them once the connection state that produced the head is no longer current.</p>
 *
 * <p>Not thread-safe: the owning watcher invokes all methods serially on its single worker thread, except
 * {@link #onConnectionLost()}, which only touches the thread-safe cache.</p>
 */
@Slf4j
public class ZooKeeperSubscriptionHeadReconciler {

    private final ZooKeeperSubscriptionSnapshotHeadReader reader;
    private final LocalSubscriptionCache cache;
    private final AtomicLong activatedHeadReadFailures = new AtomicLong();
    private long consecutiveHeadReadFailures;
    private long consecutiveActivationFailures;

    /** Commits a ZooKeeper-driven cache change only while the decision that produced it is still valid. */
    @FunctionalInterface
    interface CacheCommitGate {

        /** Gate for tests and standalone use without a watcher; every commit runs. */
        CacheCommitGate OPEN = commit -> {
            commit.run();
            return true;
        };

        /**
         * Runs the commit if the triggering ZooKeeper decision is still valid.
         *
         * @param commit cache change to apply
         * @return {@code true} if the commit ran
         */
        boolean commitIfStillValid(Runnable commit);
    }

    /**
     * Creates a reconciler using ZooKeeper as the only head source.
     *
     * @param reader reader for the ZooKeeper PREPARED and ACTIVATED heads
     * @param cache cache whose snapshot is prepared and activated
     */
    public ZooKeeperSubscriptionHeadReconciler(ZooKeeperSubscriptionSnapshotHeadReader reader,
                                               LocalSubscriptionCache cache) {
        this.reader = reader;
        this.cache = cache;
    }

    /**
     * Reads the current PREPARED and ACTIVATED heads and reconciles the authoritative ACTIVATED head.
     *
     * <p>A PREPARED read failure is logged but does not prevent ACTIVATED reconciliation. If ACTIVATED is missing or
     * cannot be read, the cache loses its confirmed head.</p>
     *
     * <p>Also used after a reconnect: a FRESH snapshot matching ACTIVATED is kept, and a STALE one is reactivated
     * without reloading.</p>
     *
     * @param gate gate through which cache changes of this run are committed
     */
    public void reconcilePreparedAndActivatedHeads(CacheCommitGate gate) {
        Optional<SubscriptionSnapshotHead> prepared = Optional.empty();
        try {
            prepared = reader.readPreparedHead();
        } catch (RuntimeException exception) {
            if (isInterrupted(exception)) {
                return;
            }
            log.warn("Could not read PREPARED subscription head; ignoring it: {}", describe(exception));
        }

        reconcileActivatedHead(prepared, gate);
    }

    /**
     * Re-reads and reconciles only the current ACTIVATED head, without reading PREPARED.
     *
     * <p>Used by periodic reconciliation while ZooKeeper is connected.</p>
     *
     * @param gate gate through which cache changes of this run are committed
     */
    public void reconcileActivatedHead(CacheCommitGate gate) {
        reconcileActivatedHead(Optional.empty(), gate);
    }

    /**
     * Aligns the prepared snapshot with a PREPARED watch payload: preloads it, or discards the prepared snapshot when
     * the PREPARED ZNode was removed. Never activates a snapshot.
     *
     * @param data serialized PREPARED head, or {@code null} when the PREPARED ZNode was removed
     */
    public void reconcilePreparedHead(byte[] data) {
        try {
            var prepared = reader.parsePreparedHead(data);
            if (prepared.isPresent()) {
                prepareOnly(prepared.orElseThrow());
            } else {
                cache.discardPreparedSnapshot();
            }
        } catch (RuntimeException exception) {
            log.warn("Could not process PREPARED subscription head event", exception);
        }
    }

    /** Reconciles the authoritative active head and, when distinct, preloads the prepared head. */
    private void reconcileActivatedHead(Optional<SubscriptionSnapshotHead> prepared, CacheCommitGate gate) {
        Optional<SubscriptionSnapshotHead> active;
        try {
            active = reader.readActivatedHead();
        } catch (RuntimeException | Error exception) {
            if (exception instanceof Error error) {
                // Recorded so the cache does not stay FRESH unconfirmed; the watcher logs the error.
                countHeadReadFailure();
                gate.commitIfStillValid(cache::clearExpectedHead);
                throw error;
            }
            // Shutdown interrupts the watcher thread; that is not a ZooKeeper failure.
            if (isInterrupted(exception)) {
                return;
            }
            // An unreadable ACTIVATED head cannot confirm the current cache state.
            headReadFailed("Could not read ACTIVATED subscription head; local cache is no longer confirmed",
                (RuntimeException) exception);
            gate.commitIfStillValid(cache::clearExpectedHead);
            return;
        }

        if (active.isEmpty()) {
            // A missing head has the same effect as an unreadable one: clear the confirmed cache head.
            headReadFailed("ZooKeeper ACTIVATED subscription head is missing; local cache is no longer confirmed", null);
            gate.commitIfStillValid(cache::clearExpectedHead);
            return;
        }

        var head = active.orElseThrow();
        if (consecutiveHeadReadFailures > 0) {
            log.info("ZooKeeper ACTIVATED subscription head {} readable again after {} failed reads",
                head.getSnapshotId(), consecutiveHeadReadFailures);
            consecutiveHeadReadFailures = 0;
        }

        // Reject this decision if the watcher has moved to a newer ZooKeeper connection epoch.
        if (!gate.commitIfStillValid(() -> cache.setExpectedHead(head))) {
            return;
        }
        if (cache.isFresh() && cache.isActiveSnapshot(head)) {
            // The active snapshot already matches; only preload a distinct prepared snapshot, if available.
            activationSucceeded(head);
            prepared.filter(candidate -> !SubscriptionSnapshotHeads.isSameSnapshot(candidate, head))
                .ifPresent(this::prepareOnly);
            return;
        }

        // Prepare and activate the subscription snapshot based on the ZooKeeper head.
        try {
            cache.prepare(head);
            // Activation must also pass the gate because the connection may have changed during preparation.
            if (!gate.commitIfStillValid(() -> cache.activate(head))) {
                return;
            }
            activationSucceeded(head);
        } catch (RuntimeException | Error exception) {
            // Shutdown interrupts a running load; that neither counts as a failure nor makes the cache stale.
            if (exception instanceof RuntimeException && isInterrupted(exception)) {
                log.debug("Loading subscription snapshot {} was interrupted", head.getSnapshotId());
                return;
            }
            // Discard a failed preparation so a later reconciliation can retry from a clean state.
            cache.recordActivationFailure(head);
            cache.discardPreparedSnapshot(head);
            consecutiveActivationFailures++;
            // Recorded above so the cache does not stay FRESH behind the head; the watcher logs the error.
            if (exception instanceof Error error) {
                throw error;
            }
            if (consecutiveActivationFailures == 1) {
                log.warn("Could not activate subscription snapshot {}", head.getSnapshotId(), exception);
            } else {
                log.warn("Could not activate subscription snapshot {} ({} consecutive failed attempts): {}",
                    head.getSnapshotId(), consecutiveActivationFailures, describe(exception));
            }
        }
    }

    /** Resets the consecutive activation failure count after a successful activation. */
    private void activationSucceeded(SubscriptionSnapshotHead head) {
        if (consecutiveActivationFailures > 0) {
            log.info("Subscription snapshot activation recovered after {} failed attempts; active snapshot {}",
                consecutiveActivationFailures, head.getSnapshotId());
            consecutiveActivationFailures = 0;
        }
    }

    /** Marks the cache unconfirmed when the owning watcher loses its ZooKeeper connection. */
    void onConnectionLost() {
        cache.clearExpectedHead();
    }

    /**
     * Returns the cumulative number of missing, invalid, or unreadable ZooKeeper ACTIVATED heads.
     *
     * <p>Interrupted reads are excluded. Failures while loading or activating snapshots are not included.</p>
     *
     * @return cumulative ACTIVATED-head read failure count
     */
    public long getActivatedHeadReadFailureCount() {
        return activatedHeadReadFailures.get();
    }

    /** Counts and logs a missing or unreadable ACTIVATED head. */
    private void headReadFailed(String message, RuntimeException exception) {
        countHeadReadFailure();
        if (consecutiveHeadReadFailures == 1) {
            log.warn(message, exception);
        } else {
            log.warn("{} ({} consecutive failed reads): {}", message, consecutiveHeadReadFailures,
                exception == null ? "missing" : describe(exception));
        }
    }

    /** Counts a missing or unreadable ACTIVATED head. */
    private void countHeadReadFailure() {
        activatedHeadReadFailures.incrementAndGet();
        consecutiveHeadReadFailures++;
    }

    /** Returns the exception message, extended by the root cause when it differs. */
    private static String describe(Throwable exception) {
        var root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == exception ? exception.getMessage()
            : exception.getMessage() + " (cause: " + root.getClass().getSimpleName() + ": " + root.getMessage() + ")";
    }

    /** Detects interruption in the current thread or exception cause chain. */
    static boolean isInterrupted(Throwable exception) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        for (var cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    /** Prepares the supplied snapshot without activating it. */
    private void prepareOnly(SubscriptionSnapshotHead head) {
        try {
            cache.prepare(head);
        } catch (RuntimeException exception) {
            log.warn("Could not preload PREPARED subscription snapshot {}", head.getSnapshotId(), exception);
        }
    }

}