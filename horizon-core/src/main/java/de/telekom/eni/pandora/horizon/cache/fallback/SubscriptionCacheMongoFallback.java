// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.fallback;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoTimeoutException;
import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.mongo.config.MongoProperties;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionMongoDocument;
import de.telekom.eni.pandora.horizon.mongo.repository.SubscriptionsMongoRepo;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@AllArgsConstructor
public class SubscriptionCacheMongoFallback implements JsonCacheFallback<SubscriptionResource> {

    private final SubscriptionsMongoRepo subscriptionsMongoRepo;
    private final MongoProperties mongoProperties;

    @Override
    public Optional<SubscriptionResource> getByKey(String key) {
        Optional<SubscriptionResource> result;
        List<SubscriptionMongoDocument> docs = new ArrayList<>();

        try {
            docs = subscriptionsMongoRepo.findBySubscriptionId(key);
        } catch (MongoCommandException | MongoTimeoutException e) {
            log.error("MongoDB fallback error occurred executing query: ", e.getCause());
            if (mongoProperties.isRethrowExceptions()) {
                throw new RuntimeException(e.getCause());
            }
        }

        if (!docs.isEmpty() && docs.getFirst() != null) {
            List<SubscriptionResource> mapped = mapMongoSubscriptions(docs);
            result = Optional.of(mapped.getFirst());
            log.debug("MongoDB fallback getByKey result: {}", result);
            return result;
        }

        return Optional.empty();
    }

    @Override
    public List<SubscriptionResource> getQuery(Query query) {
        List<SubscriptionMongoDocument> docs = query.getEnvironment() == null
            ? subscriptionsMongoRepo.findByType(query.getEventType())
            : subscriptionsMongoRepo.findByTypeAndEnvironment(query.getEventType(), query.getEnvironment());
        List<SubscriptionResource> result = mapMongoSubscriptions(docs);
        log.debug("MongoDB fallback getQuery result: {}", result);
        return result;
    }

    @Override
    public List<SubscriptionResource> getAll() {
        List<SubscriptionMongoDocument> docs = subscriptionsMongoRepo.findAll();
        return mapMongoSubscriptions(docs);
    }

    public List<SubscriptionResource> mapMongoSubscriptions(List<SubscriptionMongoDocument> docs) {
        List<SubscriptionResource> mappedValues = new ArrayList<>(docs.size());
        mappedValues.addAll(docs);
        return mappedValues;
    }

}
