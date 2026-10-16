// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import de.telekom.eni.pandora.horizon.cache.util.SubscriptionResourceJsonMapper;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.Subscription;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResourceSpec;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MongoSubscriptionSnapshotLoaderTest {

    private static final String SNAPSHOT_COLLECTION = "subscription-snapshots";
    private static final Duration LOAD_TIMEOUT = Duration.ofSeconds(60);

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    @SuppressWarnings("unchecked")
    private final MongoCollection<Document> collection = mock(MongoCollection.class);
    @SuppressWarnings("unchecked")
    private final FindIterable<Document> findIterable = mock(FindIterable.class);
    private final MongoSubscriptionSnapshotLoader loader = new MongoSubscriptionSnapshotLoader(
            mongoTemplate, SNAPSHOT_COLLECTION, LOAD_TIMEOUT);

    @Test
    void shouldLoadEntriesBySnapshotIdWithServerSideTimeout() {
        var head = snapshotHead("snapshot-1", 1L);
        returnEntries(snapshotEntry("snapshot-1", "subscription-1"));

        var snapshot = loader.load(head);

        var filterCaptor = ArgumentCaptor.forClass(Bson.class);
        verify(collection).find(filterCaptor.capture());
        verify(findIterable).maxTime(LOAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(Filters.eq("snapshotId", "snapshot-1").toBsonDocument(), filterCaptor.getValue().toBsonDocument());
        assertEquals("snapshot-1", snapshot.snapshotId());
        assertEquals("subscription-1", snapshot.getById("subscription-1").orElseThrow()
                .getSpec().getSubscription().getSubscriptionId());
    }

    @Test
    void shouldPropagateLoadTimeout() {
        var head = snapshotHead("snapshot-1", 1L);
        returnEntries();
        var timeout = new IllegalStateException("operation timed out");
        doThrow(timeout).when(findIterable).into(any());

        assertSame(timeout, assertThrows(IllegalStateException.class, () -> loader.load(head)));
    }

    @Test
    void shouldRejectDocumentCountMismatch() {
        var head = snapshotHead("snapshot-1", 2L);
        returnEntries(snapshotEntry("snapshot-1", "subscription-1"));

        var exception = assertThrows(SubscriptionCacheSnapshotException.class, () -> loader.load(head));

        assertEquals("Subscription snapshot document count mismatch: expected 2, actual 1", exception.getMessage());
    }

    @Test
    void shouldRejectNonPositiveLoadTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new MongoSubscriptionSnapshotLoader(
                mongoTemplate, SNAPSHOT_COLLECTION, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new MongoSubscriptionSnapshotLoader(
                mongoTemplate, SNAPSHOT_COLLECTION, null));
    }

    @Test
    void shouldRejectInvalidHeadBeforeQueryingEntries() {
        var head = snapshotHead(" ", 1L);

        assertThrows(SubscriptionCacheSnapshotException.class, () -> loader.load(head));
        verify(mongoTemplate, never()).getCollection(anyString());
    }

    private void returnEntries(Document... entries) {
        when(mongoTemplate.getCollection(SNAPSHOT_COLLECTION)).thenReturn(collection);
        when(collection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.maxTime(anyLong(), any(TimeUnit.class))).thenReturn(findIterable);
        when(findIterable.into(any())).thenAnswer(invocation -> {
            Collection<Document> target = invocation.getArgument(0);
            target.addAll(List.of(entries));
            return target;
        });
    }

    private SubscriptionSnapshotHead snapshotHead(String snapshotId, long documentCount) {
        var head = new SubscriptionSnapshotHead();
        head.setSnapshotId(snapshotId);
        head.setDocumentCount(documentCount);
        head.setCreatedAt(new Date(0));
        return head;
    }

    private Document snapshotEntry(String snapshotId, String subscriptionId) {
        var subscription = new Subscription();
        subscription.setSubscriptionId(subscriptionId);
        subscription.setType("event-type");
        var spec = new SubscriptionResourceSpec();
        spec.setEnvironment("production");
        spec.setSubscription(subscription);
        var resource = new SubscriptionResource();
        resource.setSpec(spec);
        try {
            return new Document("snapshotId", snapshotId)
                    .append("subscriptionId", subscriptionId)
                    .append("resource", Document.parse(
                            SubscriptionResourceJsonMapper.createObjectMapper().writeValueAsString(resource)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}