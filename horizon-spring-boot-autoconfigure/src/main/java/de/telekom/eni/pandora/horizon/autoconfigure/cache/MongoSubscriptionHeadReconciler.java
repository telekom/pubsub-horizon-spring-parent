// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Activates the snapshot referenced by the MongoDB head, which always points to the active snapshot.
 *
 * <p>If the MongoDB head cannot be read or its snapshot cannot be loaded, an active snapshot becomes stale;
 * an uninitialized cache stays uninitialized.</p>
 */
@Slf4j
public class MongoSubscriptionHeadReconciler {

    private final LocalSubscriptionCache cache;
    private long consecutiveHeadReadFailures;

    public MongoSubscriptionHeadReconciler(LocalSubscriptionCache cache) {
        this.cache = cache;
    }

    /**
     * Reads the MongoDB head and activates its snapshot; an already active matching snapshot is not reloaded.
     *
     * @return the activated head, or empty if reconciliation failed
     */
    public synchronized Optional<SubscriptionSnapshotHead> reconcile() {
        SubscriptionSnapshotHead head;
        try {
            head = cache.readSnapshotHead();
            cache.setActivationHead(head);
        } catch (RuntimeException exception) {
            if (ZooKeeperSubscriptionHeadReconciler.isInterrupted(exception)) {
                return Optional.empty();
            }
            cache.disconnected();
            consecutiveHeadReadFailures++;
            if (consecutiveHeadReadFailures == 1) {
                log.warn("Could not read MongoDB subscription head; local cache is stale", exception);
            } else {
                log.warn("Could not read MongoDB subscription head; local cache is stale ({} consecutive failed reads): {}",
                    consecutiveHeadReadFailures, exception.getMessage());
            }
            return Optional.empty();
        }
        if (consecutiveHeadReadFailures > 0) {
            log.info("MongoDB subscription head readable again after {} failed reads", consecutiveHeadReadFailures);
            consecutiveHeadReadFailures = 0;
        }
        try {
            var alreadyActive = cache.isActiveSnapshot(head);
            if (!alreadyActive) {
                cache.prepare(head);
            }
            cache.activate(head);
            if (!alreadyActive) {
                log.info("Activated subscription snapshot {} from MongoDB head", head.getSnapshotId());
            }
            return Optional.of(head);
        } catch (RuntimeException exception) {
            cache.activationFailed(head);
            log.warn("Could not activate subscription snapshot {} from MongoDB head", head.getSnapshotId(), exception);
            return Optional.empty();
        }
    }
}
