// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;

/** Validation and identity rules shared by all subscription snapshot heads. */
public final class SubscriptionSnapshotHeads {

    /** Prevents instantiation of this utility class. */
    private SubscriptionSnapshotHeads() {
    }

    /**
     * Validates a snapshot head.
     *
     * @param head head to validate
     * @return the same head
     * @throws SubscriptionCacheSnapshotException if snapshotId is blank, documentCount is not positive,
     *                                            or created is missing
     */
    public static SubscriptionSnapshotHead requireValid(SubscriptionSnapshotHead head) {
        if (head == null || head.getSnapshotId() == null || head.getSnapshotId().isBlank()) {
            throw new SubscriptionCacheSnapshotException("Invalid subscription snapshot head: missing snapshotId");
        }
        if (head.getDocumentCount() == null || head.getDocumentCount() <= 0) {
            throw new SubscriptionCacheSnapshotException("Invalid subscription snapshot head " + head.getSnapshotId()
                + ": documentCount must be positive");
        }
        if (head.getCreated() == null) {
            throw new SubscriptionCacheSnapshotException("Invalid subscription snapshot head " + head.getSnapshotId()
                + ": missing created");
        }
        return head;
    }

    /**
     * Indicates whether both heads reference the same snapshot, i.e. have the same snapshotId;
     * documentCount and created are ignored.
     *
     * @param first first head, may be {@code null}
     * @param second second head, may be {@code null}
     * @return {@code true} if both heads reference the same snapshot or both are {@code null}
     */
    public static boolean isSameSnapshot(SubscriptionSnapshotHead first, SubscriptionSnapshotHead second) {
        if (first == null || second == null) {
            return first == second;
        }
        return first.getSnapshotId().equals(second.getSnapshotId());
    }
}
