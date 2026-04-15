// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.fallback;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoTimeoutException;
import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.kubernetes.resource.PublisherResource;
import de.telekom.eni.pandora.horizon.mongo.config.MongoProperties;
import de.telekom.eni.pandora.horizon.mongo.model.PublisherMongoDocument;
import de.telekom.eni.pandora.horizon.mongo.repository.PublisherMongoRepo;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@AllArgsConstructor
public class PublisherCacheMongoFallback implements JsonCacheFallback<PublisherResource> {

    private final PublisherMongoRepo mongoRepo;
    private final MongoProperties mongoProperties;

    @Override
    public Optional<PublisherResource> getByKey(String key) {
        try {
            Optional<PublisherMongoDocument> result = mongoRepo.findById(key);
            log.debug("MongoDB fallback getByKey result: {}", result);
            return result.map(doc -> doc);
        } catch (MongoCommandException | MongoTimeoutException e) {
            log.error("MongoDB fallback error occurred executing query: ", e.getCause());
            if (mongoProperties.isRethrowExceptions()) {
                throw new RuntimeException(e.getCause());
            }
        }

        return Optional.empty();
    }

    @Override
    public List<PublisherResource> getQuery(Query query) {
        List<PublisherMongoDocument> docs = new ArrayList<>();

        try {
            String eventType = query.getEventType();
            if (eventType != null) {
                docs = mongoRepo.findByEventType(eventType);
            }
        } catch (MongoCommandException | MongoTimeoutException e) {
            log.error("MongoDB fallback error occurred executing query: ", e.getCause());
            if (mongoProperties.isRethrowExceptions()) {
                throw new RuntimeException(e.getCause());
            }
        }

        log.debug("MongoDB fallback getQuery result: {}", docs);
        return new ArrayList<>(docs);
    }

    @Override
    public List<PublisherResource> getAll() {
        return new ArrayList<>(mongoRepo.findAll());
    }

}
