package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.cache.service.SubscriptionSnapshotHeads;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reconciles ZooKeeper subscription heads with the pod-local cache.
 *
 * <p>{@code PREPARED} may preload a snapshot but never authorizes activation. A valid {@code ACTIVATE} head is
 * authoritative; an unavailable ACTIVATE head leaves the local cache without a confirmed head.
 * ZooKeeper-driven cache changes are committed through an {@link ActivationGate} so that the owning watcher can reject
 * them once the connection state that produced the head is no longer current.</p>
 */
@Slf4j
public class ZooKeeperSubscriptionHeadReconciler implements Runnable {

    private final ZooKeeperSubscriptionSnapshotHeadReader reader;
    private final LocalSubscriptionCache cache;
    private final AtomicLong headReadFailures = new AtomicLong();
    private volatile ActivationGate activationGate = ActivationGate.OPEN;
    private long consecutiveHeadReadFailures;
    private long consecutiveActivationFailures;

    /** Commits a ZooKeeper-driven cache change only while the state that produced it is still current. */
    @FunctionalInterface
    interface ActivationGate {

        /** Gate for standalone use without a watcher; every commit runs. */
        ActivationGate OPEN = commit -> {
            commit.run();
            return true;
        };

        /**
         * Runs the commit if the triggering ZooKeeper state is still current.
         *
         * @param commit cache change to apply
         * @return {@code true} if the commit ran
         */
        boolean runIfCurrent(Runnable commit);
    }

    /**
    * Creates a reconciler using ZooKeeper as the only head source.
     *
     * @param reader reader for the ZooKeeper PREPARED and ACTIVATE heads
     * @param cache cache whose snapshot is prepared and activated
     */
    public ZooKeeperSubscriptionHeadReconciler(ZooKeeperSubscriptionSnapshotHeadReader reader,
                                               LocalSubscriptionCache cache) {
        this.reader = reader;
        this.cache = cache;
    }

    /**
     * Sets the gate through which ZooKeeper-driven activation is committed.
     *
     * @param activationGate gate supplied by the owning watcher
     */
    void setActivationGate(ActivationGate activationGate) {
        this.activationGate = Objects.requireNonNull(activationGate, "activationGate must not be null");
    }

    /**
     * Reads the current PREPARED and ACTIVATE heads and reconciles the authoritative ACTIVATE head.
     *
     * <p>A PREPARED read failure is logged but does not prevent ACTIVATE reconciliation. If ACTIVATE is missing or cannot
    * be read, the cache loses its confirmed head.</p>
     *
     * <p>Also used after a reconnect: a FRESH snapshot matching ACTIVATE is kept, and a STALE one is reactivated without
     * reloading.</p>
     */
    @Override
    public synchronized void run() {
        Optional<SubscriptionSnapshotHead> prepared = Optional.empty();
        try {
            prepared = reader.readPrepared();
        } catch (RuntimeException exception) {
            if (isInterrupted(exception)) {
                return;
            }
            log.warn("Could not read prepared subscription head; ignoring it", exception);
        }

        reconcileActiveHead(prepared);
    }

    /**
     * Re-reads and reconciles only the current ACTIVATE head, without reading PREPARED.
     *
     * <p>Used by periodic reconciliation while ZooKeeper is connected.</p>
     */
    public synchronized void reconcileActiveHead() {
        reconcileActiveHead(Optional.empty());
    }

    /**
     * Processes a PREPARED watch payload as a preload only; it never activates the snapshot.
     *
     * @param data serialized PREPARED head, or {@code null} when the PREPARED ZNode was removed
     */
    public synchronized void preparePreparedEvent(byte[] data) {
        try {
            var prepared = reader.parsePreparedEvent(data);
            if (prepared.isPresent()) {
                prepareOnly(prepared.orElseThrow());
            } else {
                cache.discardPreparedSnapshot();
            }
        } catch (RuntimeException exception) {
            log.warn("Could not process prepared subscription head event", exception);
        }
    }

    private void reconcileActiveHead(Optional<SubscriptionSnapshotHead> prepared) {
        Optional<SubscriptionSnapshotHead> active;
        try {
            active = reader.readActivate();
        } catch (RuntimeException exception) {
            // Shutdown interrupts the watcher thread; that is not a ZooKeeper failure.
            if (isInterrupted(exception)) {
                return;
            }
            headReadFailed("Could not read activate subscription head", exception);
            activationGate.runIfCurrent(cache::disconnected);
            return;
        }

        if (active.isEmpty()) {
            headReadFailed("ZooKeeper activate subscription head is missing", null);
            activationGate.runIfCurrent(cache::disconnected);
            return;
        }
        if (consecutiveHeadReadFailures > 0) {
            log.info("ZooKeeper activate subscription head readable again after {} failed reads",
                consecutiveHeadReadFailures);
            consecutiveHeadReadFailures = 0;
        }

        var head = active.orElseThrow();
        if (!activationGate.runIfCurrent(() -> cache.setActivationHead(head))) {
            return;
        }
        if (cache.isActiveSnapshotUpToDate() && cache.isActiveSnapshot(head)) {
            activationSucceeded(head);
            prepared.filter(candidate -> !SubscriptionSnapshotHeads.isSameSnapshot(candidate, head))
                .ifPresent(this::prepareOnly);
            return;
        }

        try {
            cache.prepare(head);
            if (!activationGate.runIfCurrent(() -> cache.activate(head))) {
                return;
            }
            activationSucceeded(head);
        } catch (RuntimeException exception) {
            cache.activationFailed(head);
            cache.discardPreparedSnapshot(head);
            consecutiveActivationFailures++;
            if (consecutiveActivationFailures == 1) {
                log.warn("Could not activate subscription snapshot {}", head.getSnapshotId(), exception);
            } else {
                log.warn("Could not activate subscription snapshot {} ({} consecutive failed attempts): {}",
                    head.getSnapshotId(), consecutiveActivationFailures, exception.getMessage());
            }
        }
    }

    private void activationSucceeded(SubscriptionSnapshotHead head) {
        if (consecutiveActivationFailures > 0) {
            log.info("Subscription snapshot {} activation from ZooKeeper head recovered after {} failed attempts",
                head.getSnapshotId(), consecutiveActivationFailures);
            consecutiveActivationFailures = 0;
        }
    }

    /** Marks the cache unconfirmed when the owning watcher disconnects. */
    void disconnected() {
        cache.disconnected();
    }

    /**
     * Returns the cumulative number of missing, invalid, or unreadable ZooKeeper ACTIVATE heads.
     *
     * <p>Interrupted reads are excluded. Failures while loading or activating snapshots are not included.</p>
     *
     * @return cumulative ACTIVATE-head read failure count
     */
    public long headReadFailureCount() {
        return headReadFailures.get();
    }

    private void headReadFailed(String message, RuntimeException exception) {
        headReadFailures.incrementAndGet();
        consecutiveHeadReadFailures++;
        if (consecutiveHeadReadFailures == 1) {
            log.warn(message, exception);
        } else {
            log.warn("{} ({} consecutive failed reads): {}", message, consecutiveHeadReadFailures,
                exception == null ? "missing" : exception.getMessage());
        }
    }

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

    private void prepareOnly(SubscriptionSnapshotHead head) {
        try {
            cache.prepare(head);
        } catch (RuntimeException exception) {
            log.warn("Could not prepare subscription snapshot {}", head.getSnapshotId(), exception);
        }
    }
}