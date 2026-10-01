package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Optional;

@Slf4j
public class ZooKeeperSubscriptionHeadReconciler implements Runnable {

    private record Head(String snapshotId, Long documentCount, Long revision, String sourceHash, Instant createdAt) {
        private static Head from(SubscriptionSnapshotHead head) {
            return new Head(head.getSnapshotId(), head.getDocumentCount(), head.getRevision(),
                head.getSourceHash(), head.getCreatedAt().toInstant());
        }
    }

    private final ZooKeeperSubscriptionSnapshotHeadReader reader;
    private final LocalSubscriptionCache cache;
    private final Object activationLock = new Object();
    private volatile boolean connected = true;
    private Head lastActivated;

    public ZooKeeperSubscriptionHeadReconciler(ZooKeeperSubscriptionSnapshotHeadReader reader,
                                               LocalSubscriptionCache cache) {
        this.reader = reader;
        this.cache = cache;
    }

    public void suspended() {
        connected = false;
        synchronized (activationLock) {
            cache.suspended();
        }
    }

    public void lost() {
        connected = false;
        synchronized (activationLock) {
            cache.disconnected();
        }
    }

    public synchronized void reconcileAfterReconnect() {
        synchronized (activationLock) {
            connected = true;
            cache.disconnected();
            cache.discardPreparedSnapshot();
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
            log.warn("Could not read prepared subscription head; ignoring it", exception);
        }

        reconcileActiveHead(prepared);
    }

    public synchronized void reconcileActiveHead() {
        reconcileActiveHead(Optional.empty());
    }

    private void reconcileActiveHead(Optional<SubscriptionSnapshotHead> prepared) {
        Optional<SubscriptionSnapshotHead> active;
        try {
            active = reader.readActivate();
        } catch (RuntimeException exception) {
            disconnectIfConnected();
            log.warn("Could not read activate subscription head; using shared cache", exception);
            return;
        }

        if (active.isEmpty()) {
            disconnectIfConnected();
            prepared.ifPresent(this::prepareOnly);
            return;
        }

        var head = active.orElseThrow();
        var metadata = Head.from(head);
        synchronized (activationLock) {
            if (!connected) {
                return;
            }
            cache.setActivationHead(head);
        }
        if (cache.isActiveSnapshotUpToDate() && metadata.equals(lastActivated)) {
            prepared.filter(candidate -> !Head.from(candidate).equals(metadata)).ifPresent(this::prepareOnly);
            return;
        }

        try {
            cache.prepare(head);
            synchronized (activationLock) {
                if (!connected) {
                    return;
                }
                cache.activate(head);
                lastActivated = metadata;
            }
        } catch (RuntimeException exception) {
            cache.activationFailed(head);
            cache.discardPreparedSnapshot();
            log.warn("Could not activate subscription snapshot {}", head.getSnapshotId(), exception);
        }
    }

    private void prepareOnly(SubscriptionSnapshotHead head) {
        try {
            cache.prepare(head);
        } catch (RuntimeException exception) {
            log.warn("Could not prepare subscription snapshot {}", head.getSnapshotId(), exception);
        }
    }

    private void disconnectIfConnected() {
        synchronized (activationLock) {
            if (connected) {
                cache.disconnected();
            }
        }
    }
}