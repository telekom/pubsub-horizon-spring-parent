package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import de.telekom.eni.pandora.horizon.cache.config.CacheProperties.MongoHeadFallbackMode;
import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.cache.service.SubscriptionSnapshotHeads;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Activates subscription snapshots from the ZooKeeper heads, with optional MongoDB head fallback. */
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

    /** Creates a reconciler with MongoDB head fallback until the first fresh snapshot. */
    public ZooKeeperSubscriptionHeadReconciler(ZooKeeperSubscriptionSnapshotHeadReader reader,
                                               LocalSubscriptionCache cache) {
        this(reader, cache, MongoHeadFallbackMode.STARTUP_ONLY);
    }

    /** Creates a reconciler with the configured MongoDB head authority policy. */
    public ZooKeeperSubscriptionHeadReconciler(ZooKeeperSubscriptionSnapshotHeadReader reader,
                                               LocalSubscriptionCache cache, MongoHeadFallbackMode mongoHeadFallbackMode) {
        this.reader = reader;
        this.cache = cache;
        this.mongoHeadReconciler = new MongoSubscriptionHeadReconciler(cache);
        this.mongoHeadFallbackMode = java.util.Objects.requireNonNull(mongoHeadFallbackMode);
    }

    /** Stops ZooKeeper-driven activation; freshness is then decided by {@link #reconcileFromMongoHead()}. */
    public void suspended() {
        synchronized (activationLock) {
            zooKeeperActivationAllowed = false;
        }
    }

    /** Stops ZooKeeper-driven activation; freshness is then decided by {@link #reconcileFromMongoHead()}. */
    public void lost() {
        suspended();
    }

    /** Resumes ZooKeeper-driven activation and re-reads both heads, forcing re-activation of the current head. */
    public synchronized void reconcileAfterReconnect() {
        synchronized (activationLock) {
            zooKeeperActivationAllowed = true;
            lastActivated = null;
        }
        run();
    }

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

    public synchronized void reconcileActiveHead() {
        reconcileActiveHead(Optional.empty());
    }

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
     * Activates the snapshot referenced by the MongoDB head, which always points to the active snapshot.
    * Used when the ZooKeeper ACTIVATE head cannot be determined and the configured policy permits fallback.
    * A denied or failed fallback leaves an active cache STALE; without a snapshot it remains UNINITIALIZED.
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

    /** Number of reconciliations whose ZooKeeper activate head was unreadable or missing. */
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