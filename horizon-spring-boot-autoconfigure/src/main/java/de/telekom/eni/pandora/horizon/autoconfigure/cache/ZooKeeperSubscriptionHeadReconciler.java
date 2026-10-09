package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import de.telekom.eni.pandora.horizon.cache.config.CacheProperties.MongoHeadFallbackMode;
import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.cache.service.SubscriptionSnapshotHeads;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reconciles ZooKeeper subscription heads with the pod-local cache.
 *
 * <p>{@code PREPARED} may preload a snapshot but never authorizes activation. A valid {@code ACTIVATE} head is
 * authoritative; the configured MongoDB-head policy is used only when that ZooKeeper head cannot be determined.</p>
 */
@Slf4j
public class ZooKeeperSubscriptionHeadReconciler implements Runnable {

    private final ZooKeeperSubscriptionSnapshotHeadReader reader;
    private final LocalSubscriptionCache cache;
    private final MongoSubscriptionHeadReconciler mongoHeadReconciler;
    private final MongoHeadFallbackMode mongoHeadFallbackMode;
    private final Object activationLock = new Object();
    private final AtomicLong headReadFailures = new AtomicLong();
    private volatile boolean zooKeeperActivationAllowed = true;
    private SubscriptionSnapshotHead lastActivated;
    private long consecutiveHeadReadFailures;
    private long consecutiveActivationFailures;

    /**
     * Creates a reconciler using {@link MongoHeadFallbackMode#STARTUP_ONLY}.
     *
     * @param reader reader for the ZooKeeper PREPARED and ACTIVATE heads
     * @param cache cache whose snapshot is prepared and activated
     */
    public ZooKeeperSubscriptionHeadReconciler(ZooKeeperSubscriptionSnapshotHeadReader reader,
                                               LocalSubscriptionCache cache) {
        this(reader, cache, MongoHeadFallbackMode.STARTUP_ONLY);
    }

    /**
     * Creates a reconciler with the configured MongoDB-head fallback policy.
     *
     * @param reader reader for the ZooKeeper PREPARED and ACTIVATE heads
     * @param cache cache whose snapshot is prepared and activated
     * @param mongoHeadFallbackMode policy controlling when the MongoDB head may replace an unavailable ACTIVATE head
     */
    public ZooKeeperSubscriptionHeadReconciler(ZooKeeperSubscriptionSnapshotHeadReader reader,
                                               LocalSubscriptionCache cache, MongoHeadFallbackMode mongoHeadFallbackMode) {
        this.reader = reader;
        this.cache = cache;
        this.mongoHeadReconciler = new MongoSubscriptionHeadReconciler(cache);
        this.mongoHeadFallbackMode = java.util.Objects.requireNonNull(mongoHeadFallbackMode);
    }

    /**
     * Blocks ZooKeeper-driven activation after Curator reports {@code SUSPENDED}.
     *
     * <p>This does not itself change cache freshness or guarantee MongoDB fallback; the configured policy is applied
     * by a subsequent call to {@link #reconcileFromMongoHead()}.</p>
     */
    public void suspended() {
        synchronized (activationLock) {
            zooKeeperActivationAllowed = false;
        }
    }

    /**
     * Blocks ZooKeeper-driven activation after Curator reports {@code LOST}.
     *
     * <p>This has the same activation-gating effect as {@link #suspended()}.</p>
     */
    public void lost() {
        suspended();
    }

    /**
     * Resumes ZooKeeper-driven activation and reconciles the current PREPARED and ACTIVATE heads.
     *
     * <p>The previous activation optimization is cleared so the current ACTIVATE head is reconciled again. This does not
     * force a MongoDB load when a matching prepared or active snapshot can be reused.</p>
     */
    public synchronized void reconcileAfterReconnect() {
        synchronized (activationLock) {
            zooKeeperActivationAllowed = true;
            lastActivated = null;
        }
        run();
    }

    /**
     * Reads the current PREPARED and ACTIVATE heads and reconciles the authoritative ACTIVATE head.
     *
     * <p>A PREPARED read failure is logged but does not prevent ACTIVATE reconciliation. If ACTIVATE is missing or cannot
     * be read, the configured MongoDB-head fallback policy is applied.</p>
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
            reconcileFromMongoHead();
            return;
        }

        if (active.isEmpty()) {
            headReadFailed("ZooKeeper activate subscription head is missing", null);
            reconcileFromMongoHead();
            return;
        }
        if (consecutiveHeadReadFailures > 0) {
            log.info("ZooKeeper activate subscription head readable again after {} failed reads",
                consecutiveHeadReadFailures);
            consecutiveHeadReadFailures = 0;
        }

        var head = active.orElseThrow();
        synchronized (activationLock) {
            if (!zooKeeperActivationAllowed) {
                return;
            }
            cache.setActivationHead(head);
        }
        if (cache.isActiveSnapshotUpToDate() && SubscriptionSnapshotHeads.isSameSnapshot(head, lastActivated)) {
            activationSucceeded(head);
            prepared.filter(candidate -> !SubscriptionSnapshotHeads.isSameSnapshot(candidate, head))
                .ifPresent(this::prepareOnly);
            return;
        }

        try {
            cache.prepare(head);
            synchronized (activationLock) {
                if (!zooKeeperActivationAllowed) {
                    return;
                }
                cache.activate(head);
                lastActivated = head;
            }
            activationSucceeded(head);
        } catch (RuntimeException exception) {
            cache.activationFailed(head);
            cache.discardPreparedSnapshot();
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

    /**
     * Attempts reconciliation from the MongoDB head when the configured policy allows it.
     *
     * <p>{@link MongoHeadFallbackMode#ALWAYS} permits every fallback attempt. {@link MongoHeadFallbackMode#NEVER}
     * denies all attempts. {@link MongoHeadFallbackMode#STARTUP_ONLY} permits attempts until the cache has reached
     * FRESH for the first time. A denied or failed fallback leaves an active cache STALE; without an active snapshot it
     * remains UNINITIALIZED.</p>
     */
    public synchronized void reconcileFromMongoHead() {
        boolean fallbackAllowed = switch (mongoHeadFallbackMode) {
            case ALWAYS -> true;
            case NEVER -> false;
            case STARTUP_ONLY -> !cache.hasFirstFreshSnapshot();
        };
        if (!fallbackAllowed) {
            synchronized (activationLock) {
                cache.disconnected();
            }
            log.warn("ZooKeeper subscription head unavailable and MongoDB head fallback denied by mode {}; local cache has no confirmed head",
                mongoHeadFallbackMode);
            return;
        }
        mongoHeadReconciler.reconcile().ifPresent(head -> lastActivated = head);
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