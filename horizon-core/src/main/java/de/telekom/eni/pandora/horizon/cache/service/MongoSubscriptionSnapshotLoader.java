// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.exception.SubscriptionSnapshotException;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotEntry;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Loads versioned subscription snapshots from MongoDB.
 *
 * <p>The snapshot head identifies the active snapshot and declares the expected
 * number of entries. A loaded snapshot is validated before it is converted into
 * an indexed in-memory representation.</p>
 */
public class MongoSubscriptionSnapshotLoader {

    private final MongoTemplate mongoTemplate;
    private final String snapshotCollectionName;
    private final String snapshotHeadCollectionName;

    /**
     * Creates a loader for the configured snapshot collections.
     *
     * @param mongoTemplate MongoDB template for the configuration database
     * @param snapshotCollectionName collection containing snapshot entries
     * @param snapshotHeadCollectionName collection containing the snapshot head
     */
    public MongoSubscriptionSnapshotLoader(MongoTemplate mongoTemplate,
                                           String snapshotCollectionName,
                                           String snapshotHeadCollectionName) {
        this.mongoTemplate = mongoTemplate;
        this.snapshotCollectionName = snapshotCollectionName;
        this.snapshotHeadCollectionName = snapshotHeadCollectionName;
    }

    /**
     * Reads and validates the currently published snapshot head.
     *
     * @return the valid snapshot head
    * @throws SubscriptionSnapshotException if no valid head exists
     */
    public SubscriptionSnapshotHead readSnapshotHead() {
        var snapshotHead = mongoTemplate.findById(
                "head", SubscriptionSnapshotHead.class, snapshotHeadCollectionName);
        validateSnapshotHead(snapshotHead);
        return snapshotHead;
    }

    /**
     * Loads and indexes all entries belonging to the supplied snapshot.
     *
     * @param snapshotHead validated snapshot metadata
     * @return an indexed, immutable snapshot representation
    * @throws IllegalArgumentException if the head is {@code null}
    * @throws SubscriptionSnapshotException if the head is invalid or the entry count differs
    *                                       from the expected document count
     */
    public IndexedSubscriptionSnapshot load(SubscriptionSnapshotHead snapshotHead) {
        if (snapshotHead == null) {
            throw new IllegalArgumentException("SnapshotHead must not be null");
        }
        validateSnapshotHead(snapshotHead);

        var query = Query.query(Criteria.where("snapshotId").is(snapshotHead.getSnapshotId()));
        var entries = mongoTemplate.find(query, SubscriptionSnapshotEntry.class, snapshotCollectionName);
        if (entries.size() != snapshotHead.getDocumentCount()) {
            throw new SubscriptionSnapshotException("Subscription snapshot document count mismatch: expected "
                    + snapshotHead.getDocumentCount() + ", actual " + entries.size());
        }
        return IndexedSubscriptionSnapshot.fromSnapshotEntries(snapshotHead, entries);
    }

    private static void validateSnapshotHead(SubscriptionSnapshotHead snapshotHead) {
        if (snapshotHead == null || snapshotHead.getSnapshotId() == null || snapshotHead.getSnapshotId().isBlank()) {
            throw new SubscriptionSnapshotException("No valid subscription snapshot head available");
        }
        if (snapshotHead.getDocumentCount() == null || snapshotHead.getDocumentCount() < 0) {
            throw new SubscriptionSnapshotException("Invalid documentCount in subscription snapshot head");
        }
    }
}