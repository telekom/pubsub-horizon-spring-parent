package de.telekom.eni.pandora.horizon.autoconfigure.cache;

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
    private final boolean mongoHeadFallbackEnabled;
    private final Object activationLock = new Object();
    private final AtomicLong headReadFailures = new AtomicLong();
    private volatile boolean connected = true;
    private SubscriptionSnapshotHead lastActivated;
    private long consecutiveHeadReadFailures;

    /** Creates a reconciler with MongoDB head fallback enabled. */
    public ZooKeeperSubscriptionHeadReconciler(ZooKeeperSubscriptionSnapshotHeadReader reader,
                                               LocalSubscriptionCache cache) {
        this(reader, cache, true);
    }

    /** Creates a reconciler; without MongoDB head fallback, an undeterminable ZooKeeper head makes the cache STALE. */
    public ZooKeeperSubscriptionHeadReconciler(ZooKeeperSubscriptionSnapshotHeadReader reader,
                                               LocalSubscriptionCache cache, boolean mongoHeadFallbackEnabled) {
        this.reader = reader;
        this.cache = cache;
        this.mongoHeadReconciler = new MongoSubscriptionHeadReconciler(cache);
        this.mongoHeadFallbackEnabled = mongoHeadFallbackEnabled;
    }

    /** Stops ZooKeeper-driven activation; freshness is then decided by {@link #reconcileFromMongoHead()}. */
    public void suspended() {
        synchronized (activationLock) {
            connected = false;
        }
    }

    /** Stops ZooKeeper-driven activation; freshness is then decided by {@link #reconcileFromMongoHead()}. */
    public void lost() {
        suspended();
    }

    /** Resumes ZooKeeper-driven activation and re-reads both heads, forcing re-activation of the current head. */
    public synchronized void reconcileAfterReconnect() {
        synchronized (activationLock) {
            connected = true;
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
            if (!connected) {
                return;
            }
            cache.setActivationHead(head);
        }
        if (cache.isActiveSnapshotUpToDate() && SubscriptionSnapshotHeads.isSameSnapshot(head, lastActivated)) {
            prepared.filter(candidate -> !SubscriptionSnapshotHeads.isSameSnapshot(candidate, head))
                .ifPresent(this::prepareOnly);
            return;
        }

        try {
            cache.prepare(head);
            synchronized (activationLock) {
                if (!connected) {
                    return;
                }
                cache.activate(head);
                lastActivated = head;
            }
        } catch (RuntimeException exception) {
            cache.activationFailed(head);
            cache.discardPreparedSnapshot();
            log.warn("Could not activate subscription snapshot {}", head.getSnapshotId(), exception);
        }
    }

    /**
     * Activates the snapshot referenced by the MongoDB head, which always points to the active snapshot.
     * Used when the ZooKeeper ACTIVATE head cannot be determined. The cache becomes stale if the MongoDB
     * head fallback is disabled, the MongoDB head cannot be read, or its snapshot cannot be loaded.
     */
    public synchronized void reconcileFromMongoHead() {
        if (!mongoHeadFallbackEnabled) {
            synchronized (activationLock) {
                cache.disconnected();
            }
            log.warn("ZooKeeper subscription head unavailable and MongoDB head fallback disabled; local cache is stale");
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