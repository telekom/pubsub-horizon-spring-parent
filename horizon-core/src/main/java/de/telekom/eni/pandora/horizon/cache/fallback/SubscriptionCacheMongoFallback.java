// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.fallback;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoTimeoutException;
import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.cache.util.SubscriptionResourceJsonMapper;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.mongo.config.MongoProperties;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionMongoDocument;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;

import java.util.List;
import java.util.Optional;

/**
 * Reads subscriptions from MongoDB when Hazelcast is unavailable; documents are mapped with the Hazelcast Jackson
 * configuration (see {@link SubscriptionResourceJsonMapper}).
 */
@Slf4j
public class SubscriptionCacheMongoFallback implements JsonCacheFallback<SubscriptionResource> {

    private static final String READINESS_CHECK_ID = "__horizon_cache_readiness__";

    static final String COLLECTION_NAME = SubscriptionMongoDocument.class
        .getAnnotation(org.springframework.data.mongodb.core.mapping.Document.class).collection();

    private final MongoTemplate mongoTemplate;
    private final MongoProperties mongoProperties;
    private final SubscriptionResourceJsonMapper jsonMapper;

    public SubscriptionCacheMongoFallback(MongoTemplate mongoTemplate, MongoProperties mongoProperties,
                                          SubscriptionResourceJsonMapper jsonMapper) {
        this.mongoTemplate = mongoTemplate;
        this.mongoProperties = mongoProperties;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public boolean isReady() {
        try {
            mongoTemplate.exists(byId(READINESS_CHECK_ID), COLLECTION_NAME);
            return true;
        } catch (RuntimeException exception) {
            log.warn("MongoDB subscription cache fallback is not available: {}", exception.getMessage());
            return false;
        }
    }

    @Override
    public Optional<SubscriptionResource> getByKey(String key) {
        List<Document> docs = List.of();

        try {
            docs = mongoTemplate.find(byId(key), Document.class, COLLECTION_NAME);
        } catch (MongoCommandException | MongoTimeoutException e) {
            log.error("MongoDB fallback error occurred executing query: ", e.getCause());
            if (mongoProperties.isRethrowExceptions()) {
                throw new RuntimeException(e.getCause());
            }
        }

        if (!docs.isEmpty() && docs.getFirst() != null) {
            var result = Optional.of(jsonMapper.fromDocument(docs.getFirst()));
            log.debug("MongoDB fallback getByKey result: {}", result);
            return result;
        }

        return Optional.empty();
    }

    @Override
    public List<SubscriptionResource> getQuery(Query query) {
        var criteria = Criteria.where("spec.subscription.type").is(query.getEventType());
        if (query.getEnvironment() != null) {
            criteria = criteria.and("spec.environment").is(query.getEnvironment());
        }
        var docs = mongoTemplate.find(org.springframework.data.mongodb.core.query.Query.query(criteria),
            Document.class, COLLECTION_NAME);
        var result = mapDocuments(docs);
        log.debug("MongoDB fallback getQuery result: {}", result);
        return result;
    }

    @Override
    public List<SubscriptionResource> getAll() {
        return mapDocuments(mongoTemplate.findAll(Document.class, COLLECTION_NAME));
    }

    private List<SubscriptionResource> mapDocuments(List<Document> docs) {
        return docs.stream().map(jsonMapper::fromDocument).toList();
    }

    private static org.springframework.data.mongodb.core.query.Query byId(String id) {
        return org.springframework.data.mongodb.core.query.Query.query(Criteria.where("_id").is(id));
    }
}
