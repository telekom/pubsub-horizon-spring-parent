// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotEntry;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

public class MongoSubscriptionSnapshotLoader {

    private final MongoTemplate mongoTemplate;
    private final String snapshotCollectionName;
    private final String snapshotHeadCollectionName;

    public MongoSubscriptionSnapshotLoader(MongoTemplate mongoTemplate,
                                           String snapshotCollectionName,
                                           String snapshotHeadCollectionName) {
        this.mongoTemplate = mongoTemplate;
        this.snapshotCollectionName = snapshotCollectionName;
        this.snapshotHeadCollectionName = snapshotHeadCollectionName;
    }

    public SubscriptionSnapshotHead readSnapshotHead() {
        var snapshotHead = mongoTemplate.findById(
                "head", SubscriptionSnapshotHead.class, snapshotHeadCollectionName);
        validateSnapshotHead(snapshotHead);
        return snapshotHead;
    }

    public IndexedSubscriptionSnapshot load(SubscriptionSnapshotHead snapshotHead) {
        validateSnapshotHead(snapshotHead);

        var query = Query.query(Criteria.where("snapshotId").is(snapshotHead.getSnapshotId()));
        var entries = mongoTemplate.find(query, SubscriptionSnapshotEntry.class, snapshotCollectionName);
        if (entries.size() != snapshotHead.getDocumentCount()) {
            throw new IllegalStateException("Subscription snapshot document count mismatch: expected "
                    + snapshotHead.getDocumentCount() + ", actual " + entries.size());
        }
        return IndexedSubscriptionSnapshot.fromEntries(snapshotHead.getSnapshotId(), entries);
    }

    private static void validateSnapshotHead(SubscriptionSnapshotHead snapshotHead) {
        if (snapshotHead == null || snapshotHead.getSnapshotId() == null || snapshotHead.getSnapshotId().isBlank()) {
            throw new IllegalStateException("No valid subscription snapshot head available");
        }
        if (snapshotHead.getDocumentCount() == null || snapshotHead.getDocumentCount() < 0) {
            throw new IllegalStateException("Invalid documentCount in subscription snapshot head");
        }
    }
}