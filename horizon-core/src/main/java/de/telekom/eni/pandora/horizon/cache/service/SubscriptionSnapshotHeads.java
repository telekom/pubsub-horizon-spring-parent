// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;

import java.util.Objects;

/** Validation and identity rules shared by all subscription snapshot head sources (ZooKeeper and MongoDB). */
public final class SubscriptionSnapshotHeads {

    private SubscriptionSnapshotHeads() {
    }

    /**
     * Validates a snapshot head.
     *
     * @param head head to validate
     * @return the same head
     * @throws SubscriptionCacheSnapshotException if snapshotId is blank, documentCount is not positive,
     *                                            or createdAt is missing
     */
    public static SubscriptionSnapshotHead requireValid(SubscriptionSnapshotHead head) {
        if (head == null || head.getSnapshotId() == null || head.getSnapshotId().isBlank()) {
            throw new SubscriptionCacheSnapshotException("Invalid subscription snapshot head: missing snapshotId");
        }
        if (head.getDocumentCount() == null || head.getDocumentCount() <= 0) {
            throw new SubscriptionCacheSnapshotException("Invalid subscription snapshot head " + head.getSnapshotId()
                + ": documentCount must be positive");
        }
        if (head.getCreatedAt() == null) {
            throw new SubscriptionCacheSnapshotException("Invalid subscription snapshot head " + head.getSnapshotId()
                + ": missing createdAt");
        }
        return head;
    }

    /**
     * Indicates whether both heads reference the same snapshot: snapshotId and documentCount must match,
     * revision and sourceHash only when set on both heads; createdAt is ignored.
     *
     * @param first first head, may be {@code null}
     * @param second second head, may be {@code null}
     * @return {@code true} if both heads reference the same snapshot or both are {@code null}
     */
    public static boolean isSameSnapshot(SubscriptionSnapshotHead first, SubscriptionSnapshotHead second) {
        if (first == null || second == null) {
            return first == second;
        }
        return isSameSnapshot(first.getSnapshotId(), first.getDocumentCount(), first.getRevision(),
            first.getSourceHash(), second.getSnapshotId(), second.getDocumentCount(), second.getRevision(),
            second.getSourceHash());
    }

    static boolean isSameSnapshot(String snapshotId, Long documentCount, Long revision, String sourceHash,
                                  String otherSnapshotId, Long otherDocumentCount, Long otherRevision,
                                  String otherSourceHash) {
        return Objects.equals(snapshotId, otherSnapshotId)
            && Objects.equals(documentCount, otherDocumentCount)
            && equalIfBothSet(revision, otherRevision)
            && equalIfBothSet(sourceHash, otherSourceHash);
    }

    private static boolean equalIfBothSet(Object value, Object otherValue) {
        return value == null || otherValue == null || value.equals(otherValue);
    }
}
