// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.Subscription;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResourceSpec;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotEntry;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MongoSubscriptionSnapshotLoaderTest {

    private static final String SNAPSHOT_COLLECTION = "subscription-snapshots";
    private static final String HEAD_COLLECTION = "subscription-head";
    private static final Duration LOAD_TIMEOUT = Duration.ofSeconds(60);

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final MongoSubscriptionSnapshotLoader loader = new MongoSubscriptionSnapshotLoader(
            mongoTemplate, SNAPSHOT_COLLECTION, HEAD_COLLECTION, LOAD_TIMEOUT);

    @Test
    void shouldReadHeadFromConfiguredCollectionWithTimeLimit() {
        var head = snapshotHead("snapshot-1", 1L);
        when(mongoTemplate.findOne(any(Query.class), eq(SubscriptionSnapshotHead.class), eq(HEAD_COLLECTION)))
                .thenReturn(head);

        assertSame(head, loader.readSnapshotHead());

        var queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).findOne(queryCaptor.capture(), eq(SubscriptionSnapshotHead.class), eq(HEAD_COLLECTION));
        assertEquals("head", queryCaptor.getValue().getQueryObject().getString("_id"));
        assertEquals(LOAD_TIMEOUT.toMillis(), queryCaptor.getValue().getMeta().getMaxTimeMsec());
    }

    @Test
    void shouldLoadEntriesBySnapshotIdFromConfiguredCollection() {
        var head = snapshotHead("snapshot-1", 1L);
        var entry = snapshotEntry("snapshot-1", "subscription-1");
        when(mongoTemplate.find(any(Query.class), eq(SubscriptionSnapshotEntry.class), eq(SNAPSHOT_COLLECTION)))
                .thenReturn(List.of(entry));

        var snapshot = loader.load(head);

        var queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(queryCaptor.capture(), eq(SubscriptionSnapshotEntry.class), eq(SNAPSHOT_COLLECTION));
        assertEquals("snapshot-1", queryCaptor.getValue().getQueryObject().getString("snapshotId"));
        assertEquals(LOAD_TIMEOUT.toMillis(), queryCaptor.getValue().getMeta().getMaxTimeMsec());
        assertEquals("snapshot-1", snapshot.snapshotId());
        assertEquals("subscription-1", snapshot.getById("subscription-1").orElseThrow()
                .getSpec().getSubscription().getSubscriptionId());
    }

    @Test
    void shouldRejectDocumentCountMismatch() {
        var head = snapshotHead("snapshot-1", 2L);
        when(mongoTemplate.find(any(Query.class), eq(SubscriptionSnapshotEntry.class), eq(SNAPSHOT_COLLECTION)))
                .thenReturn(List.of(snapshotEntry("snapshot-1", "subscription-1")));

        var exception = assertThrows(SubscriptionCacheSnapshotException.class, () -> loader.load(head));

        assertEquals("Subscription snapshot document count mismatch: expected 2, actual 1", exception.getMessage());
    }

    @Test
    void shouldRejectMissingHead() {
        when(mongoTemplate.findOne(any(Query.class), eq(SubscriptionSnapshotHead.class), eq(HEAD_COLLECTION)))
                .thenReturn(null);

        assertThrows(SubscriptionCacheSnapshotException.class, loader::readSnapshotHead);
    }

    @Test
    void shouldRejectZeroDocumentCount() {
        when(mongoTemplate.findOne(any(Query.class), eq(SubscriptionSnapshotHead.class), eq(HEAD_COLLECTION)))
                .thenReturn(snapshotHead("snapshot-1", 0L));

        assertThrows(SubscriptionCacheSnapshotException.class, loader::readSnapshotHead);
    }

    @Test
    void shouldRejectNonPositiveLoadTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new MongoSubscriptionSnapshotLoader(
                mongoTemplate, SNAPSHOT_COLLECTION, HEAD_COLLECTION, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new MongoSubscriptionSnapshotLoader(
                mongoTemplate, SNAPSHOT_COLLECTION, HEAD_COLLECTION, null));
    }

    @Test
    void shouldRejectInvalidHeadBeforeQueryingEntries() {
        var head = snapshotHead(" ", 1L);

        assertThrows(SubscriptionCacheSnapshotException.class, () -> loader.load(head));
        verify(mongoTemplate, never()).find(any(Query.class), eq(SubscriptionSnapshotEntry.class), eq(SNAPSHOT_COLLECTION));
    }

    private SubscriptionSnapshotHead snapshotHead(String snapshotId, long documentCount) {
        var head = new SubscriptionSnapshotHead();
        head.setSnapshotId(snapshotId);
        head.setDocumentCount(documentCount);
        head.setCreatedAt(new Date(0));
        return head;
    }

    private SubscriptionSnapshotEntry snapshotEntry(String snapshotId, String subscriptionId) {
        var subscription = new Subscription();
        subscription.setSubscriptionId(subscriptionId);
        subscription.setType("event-type");
        var spec = new SubscriptionResourceSpec();
        spec.setEnvironment("production");
        spec.setSubscription(subscription);
        var resource = new SubscriptionResource();
        resource.setSpec(spec);
        var entry = new SubscriptionSnapshotEntry();
        entry.setSnapshotId(snapshotId);
        entry.setSubscriptionId(subscriptionId);
        entry.setResource(resource);
        return entry;
    }
}