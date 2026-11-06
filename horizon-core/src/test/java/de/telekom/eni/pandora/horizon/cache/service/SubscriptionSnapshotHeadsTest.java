// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionSnapshotHeadsTest {

    @Test
    void sameSnapshotComparesOnlySnapshotId() {
        var base = head("snapshot", 3L, 0L);

        assertTrue(SubscriptionSnapshotHeads.isSameSnapshot(base, head("snapshot", 4L, 999L)));
        assertFalse(SubscriptionSnapshotHeads.isSameSnapshot(base, head("other", 3L, 0L)));
        assertFalse(SubscriptionSnapshotHeads.isSameSnapshot(base, null));
        assertTrue(SubscriptionSnapshotHeads.isSameSnapshot(null, null));
    }

    @Test
    void snapshotVersionRecordEqualityIsTransitiveAndMatchingComparesOnlySnapshotId() {
        var first = IndexedSubscriptionSnapshot.SnapshotVersion.from(head("snapshot", 3L, 0L));
        var second = IndexedSubscriptionSnapshot.SnapshotVersion.from(head("snapshot", 3L, 0L));
        var third = IndexedSubscriptionSnapshot.SnapshotVersion.from(head("snapshot", 3L, 0L));
        var changedMetadata = IndexedSubscriptionSnapshot.SnapshotVersion.from(head("snapshot", 4L, 999L));

        assertEquals(first, second);
        assertEquals(second, third);
        assertEquals(first, third);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(1, new HashSet<>(List.of(first, second, third)).size());
        assertNotEquals(first, changedMetadata);
        assertTrue(first.matches(changedMetadata));
        assertFalse(first.matches(IndexedSubscriptionSnapshot.SnapshotVersion.from(head("other", 3L, 0L))));
        assertFalse(first.matches(null));
    }

    @Test
    void requireValidRejectsMissingIdNonPositiveCountAndMissingCreated() {
        var valid = head("snapshot", 1L, 0L);
        assertSame(valid, SubscriptionSnapshotHeads.requireValid(valid));

        assertThrows(SubscriptionCacheSnapshotException.class, () -> SubscriptionSnapshotHeads.requireValid(null));
        assertThrows(SubscriptionCacheSnapshotException.class,
            () -> SubscriptionSnapshotHeads.requireValid(head(" ", 1L, 0L)));
        assertThrows(SubscriptionCacheSnapshotException.class,
            () -> SubscriptionSnapshotHeads.requireValid(head("snapshot", 0L, 0L)));
        assertThrows(SubscriptionCacheSnapshotException.class,
            () -> SubscriptionSnapshotHeads.requireValid(head("snapshot", null, 0L)));
        var withoutCreatedAt = head("snapshot", 1L, 0L);
        withoutCreatedAt.setCreated(null);
        assertThrows(SubscriptionCacheSnapshotException.class,
            () -> SubscriptionSnapshotHeads.requireValid(withoutCreatedAt));
    }

    private static SubscriptionSnapshotHead head(String snapshotId, Long documentCount, long createdAtMillis) {
        var head = new SubscriptionSnapshotHead();
        head.setSnapshotId(snapshotId);
        head.setDocumentCount(documentCount);
        head.setCreated(new Date(createdAtMillis));
        return head;
    }
}
