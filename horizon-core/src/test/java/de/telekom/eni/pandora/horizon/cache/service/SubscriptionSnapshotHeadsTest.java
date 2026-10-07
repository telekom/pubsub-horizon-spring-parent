// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionSnapshotHeadsTest {

    @Test
    void sameSnapshotRequiresIdAndCountAndComparesOptionalFieldsOnlyWhenBothSet() {
        var base = head("snapshot", 3L, 1L, "hash", 0L);

        assertTrue(SubscriptionSnapshotHeads.isSameSnapshot(base, head("snapshot", 3L, 1L, "hash", 999L)));
        assertTrue(SubscriptionSnapshotHeads.isSameSnapshot(base, head("snapshot", 3L, null, null, 0L)));
        assertFalse(SubscriptionSnapshotHeads.isSameSnapshot(base, head("other", 3L, 1L, "hash", 0L)));
        assertFalse(SubscriptionSnapshotHeads.isSameSnapshot(base, head("snapshot", 4L, 1L, "hash", 0L)));
        assertFalse(SubscriptionSnapshotHeads.isSameSnapshot(base, head("snapshot", 3L, 2L, "hash", 0L)));
        assertFalse(SubscriptionSnapshotHeads.isSameSnapshot(base, head("snapshot", 3L, 1L, "other", 0L)));
        assertFalse(SubscriptionSnapshotHeads.isSameSnapshot(base, null));
        assertTrue(SubscriptionSnapshotHeads.isSameSnapshot(null, null));
    }

    @Test
    void requireValidRejectsMissingIdNonPositiveCountAndMissingCreatedAt() {
        var valid = head("snapshot", 1L, null, null, 0L);
        assertSame(valid, SubscriptionSnapshotHeads.requireValid(valid));

        assertThrows(SubscriptionCacheSnapshotException.class, () -> SubscriptionSnapshotHeads.requireValid(null));
        assertThrows(SubscriptionCacheSnapshotException.class,
            () -> SubscriptionSnapshotHeads.requireValid(head(" ", 1L, null, null, 0L)));
        assertThrows(SubscriptionCacheSnapshotException.class,
            () -> SubscriptionSnapshotHeads.requireValid(head("snapshot", 0L, null, null, 0L)));
        assertThrows(SubscriptionCacheSnapshotException.class,
            () -> SubscriptionSnapshotHeads.requireValid(head("snapshot", null, null, null, 0L)));
        var withoutCreatedAt = head("snapshot", 1L, null, null, 0L);
        withoutCreatedAt.setCreatedAt(null);
        assertThrows(SubscriptionCacheSnapshotException.class,
            () -> SubscriptionSnapshotHeads.requireValid(withoutCreatedAt));
    }

    private static SubscriptionSnapshotHead head(String snapshotId, Long documentCount, Long revision,
                                                 String sourceHash, long createdAtMillis) {
        var head = new SubscriptionSnapshotHead();
        head.setSnapshotId(snapshotId);
        head.setDocumentCount(documentCount);
        head.setRevision(revision);
        head.setSourceHash(sourceHash);
        head.setCreatedAt(new Date(createdAtMillis));
        return head;
    }
}
