// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.cache.util.SubscriptionResourceJsonMapper;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotEntry;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;

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
    private final Duration loadTimeout;
    private final SubscriptionResourceJsonMapper jsonMapper = new SubscriptionResourceJsonMapper();

    /**
     * Creates a loader for the configured snapshot collections.
     *
     * @param mongoTemplate MongoDB template for the configuration database
     * @param snapshotCollectionName collection containing snapshot entries
    * @param loadTimeout server-side time limit for each snapshot load
     */
    public MongoSubscriptionSnapshotLoader(MongoTemplate mongoTemplate,
                                           String snapshotCollectionName,
                                           Duration loadTimeout) {
        if (loadTimeout == null || loadTimeout.isNegative() || loadTimeout.toMillis() == 0) {
            throw new IllegalArgumentException("MongoDB load timeout must be at least 1ms");
        }
        this.mongoTemplate = mongoTemplate;
        this.snapshotCollectionName = snapshotCollectionName;
        this.loadTimeout = loadTimeout;
    }

    /**
     * Loads and indexes all entries belonging to the supplied snapshot.
     *
     * @param snapshotHead validated snapshot metadata
     * @return an indexed, immutable snapshot representation
    * @throws IllegalArgumentException if the head is {@code null}
    * @throws SubscriptionCacheSnapshotException if the head is invalid or the entry count differs
    *                                            from the expected document count
     */
    public IndexedSubscriptionSnapshot load(SubscriptionSnapshotHead snapshotHead) {
        if (snapshotHead == null) {
            throw new IllegalArgumentException("SnapshotHead must not be null");
        }
        SubscriptionSnapshotHeads.requireValid(snapshotHead);

        var query = Query.query(Criteria.where("snapshotId").is(snapshotHead.getSnapshotId())).maxTime(loadTimeout);
        var documents = mongoTemplate.find(query, Document.class, snapshotCollectionName);
        if (documents.size() != snapshotHead.getDocumentCount()) {
            throw new SubscriptionCacheSnapshotException("Subscription snapshot document count mismatch: expected "
                    + snapshotHead.getDocumentCount() + ", actual " + documents.size());
        }
        var entries = documents.stream().map(this::toEntry).toList();
        return IndexedSubscriptionSnapshot.fromSnapshotEntries(snapshotHead, entries);
    }

    // Same mapping as for subscriptions read from Hazelcast, not Spring Data entity mapping.
    private SubscriptionSnapshotEntry toEntry(Document document) {
        try {
            var entry = new SubscriptionSnapshotEntry();
            entry.setSnapshotId(document.getString("snapshotId"));
            entry.setSubscriptionId(document.getString("subscriptionId"));
            var resource = document.get("resource", Document.class);
            entry.setResource(resource == null ? null : jsonMapper.fromDocument(resource));
            return entry;
        } catch (RuntimeException exception) {
            throw new SubscriptionCacheSnapshotException("Invalid subscription snapshot entry "
                + document.get("_id"), exception);
        }
    }
}