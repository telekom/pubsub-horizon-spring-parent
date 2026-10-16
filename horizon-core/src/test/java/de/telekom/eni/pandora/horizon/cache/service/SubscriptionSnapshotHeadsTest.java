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
    void snapshotVersionMatchingRemainsPairwiseWithoutWeakeningRecordEquality() {
        var first = IndexedSubscriptionSnapshot.SnapshotVersion.from(head("snapshot", 3L, 1L, "first", 0L));
        var unspecified = IndexedSubscriptionSnapshot.SnapshotVersion.from(head("snapshot", 3L, null, null, 999L));
        var second = IndexedSubscriptionSnapshot.SnapshotVersion.from(head("snapshot", 3L, 2L, "second", 0L));

        assertTrue(first.matches(unspecified));
        assertTrue(unspecified.matches(first));
        assertTrue(unspecified.matches(second));
        assertTrue(second.matches(unspecified));
        assertFalse(first.matches(second));
        assertFalse(first.matches(null));
        assertNotEquals(first, unspecified);
        assertNotEquals(unspecified, second);
        assertNotEquals(first, second);
        assertEquals(3, new HashSet<>(List.of(first, unspecified, second)).size());
    }

    @Test
    void snapshotVersionRecordEqualityIsTransitiveAndMatchingIgnoresCreatedAt() {
        var first = IndexedSubscriptionSnapshot.SnapshotVersion.from(head("snapshot", 3L, 1L, "hash", 0L));
        var second = IndexedSubscriptionSnapshot.SnapshotVersion.from(head("snapshot", 3L, 1L, "hash", 0L));
        var third = IndexedSubscriptionSnapshot.SnapshotVersion.from(head("snapshot", 3L, 1L, "hash", 0L));
        var laterTimestamp = IndexedSubscriptionSnapshot.SnapshotVersion.from(head("snapshot", 3L, 1L, "hash", 999L));

        assertEquals(first, second);
        assertEquals(second, third);
        assertEquals(first, third);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(1, new HashSet<>(List.of(first, second, third)).size());
        assertNotEquals(first, laterTimestamp);
        assertTrue(first.matches(laterTimestamp));
        assertFalse(first.matches(IndexedSubscriptionSnapshot.SnapshotVersion.from(head("other", 3L, 1L, "hash", 0L))));
        assertFalse(first.matches(IndexedSubscriptionSnapshot.SnapshotVersion.from(head("snapshot", 4L, 1L, "hash", 0L))));
        assertFalse(first.matches(IndexedSubscriptionSnapshot.SnapshotVersion.from(head("snapshot", 3L, 2L, "hash", 0L))));
        assertFalse(first.matches(IndexedSubscriptionSnapshot.SnapshotVersion.from(head("snapshot", 3L, 1L, "other", 0L))));
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
