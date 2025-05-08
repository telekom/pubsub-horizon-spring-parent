// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import de.telekom.eni.pandora.horizon.cache.listener.SubscriptionResourceEventBroadcaster;
import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.Subscription;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResourceSpec;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionTrigger;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionMongoDocument;
import de.telekom.eni.pandora.horizon.mongo.repository.SubscriptionsMongoRepo;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Slf4j
public class JsonCacheService<T> {

    private final Class<T> mapClass;

    @Getter
    private IMap<String, HazelcastJsonValue> map;

    private final ObjectMapper mapper;

    private final HazelcastInstance hazelcastInstance;

    private final String cacheMapName;

    private final SubscriptionsMongoRepo subscriptionsMongoRepo;

    private final ApplicationEventPublisher applicationEventPublisher;

    private boolean listenerAdded = false;

    public JsonCacheService(Class<T> mapClass, IMap<String, HazelcastJsonValue> map, ObjectMapper mapper, HazelcastInstance hazelcastInstance, String cacheMapName, SubscriptionsMongoRepo subscriptionsMongoRepo, ApplicationEventPublisher applicationEventPublisher) {
        this.mapClass = mapClass;
        this.map = map;
        this.mapper = mapper;
        this.hazelcastInstance = hazelcastInstance;
        this.cacheMapName = cacheMapName;
        this.subscriptionsMongoRepo = subscriptionsMongoRepo;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    public Optional<T> getByKey(String key) throws JsonCacheException {
        IMap<String, HazelcastJsonValue> map = getCacheMap();

        if (map != null) {
            HazelcastJsonValue value = map.get(key);
            if (value != null) {
                try {
                    log.debug("Raw JSON value for key {}: {}", key, value.getValue());
                    T mappedValue = mapper.readValue(value.getValue(), mapClass);
                    return Optional.of(mappedValue);
                } catch (JsonProcessingException e) {
                    String msg = String.format("Could not map %s from hazelcast map %s to %s", key, map.getName(), mapClass.getName());
                    throw new JsonCacheException(msg, e);
                }
            }
        } else {
            log.warn("Hazelcast map not available. Falling back to MongoDB.");
            // Fallback to MongoDB
            List<SubscriptionMongoDocument> docs = List.of(subscriptionsMongoRepo.findById(key).orElse(null));

            if (docs.getFirst() != null) {
                List<T> mapped = mapMongoSubscriptions(docs);
                return Optional.of(mapped.getFirst());
            }
        }

        return Optional.empty();
    }

     public List<T> getQuery(Query query) throws JsonCacheException {
        IMap<String, HazelcastJsonValue> map = getCacheMap();
        Collection<HazelcastJsonValue> values;
        List<T> result;

        if (map != null) {
            values = map.values(query.toSqlPredicate()); //list of subscription resources
            result = mapAll(values);
            log.debug("Hazelcast Query result: {}", result);
        }
        else {
            log.error("Hazelcast map is not available, using MongoDB instead");

            List<SubscriptionMongoDocument> docs = subscriptionsMongoRepo.findByType(query.getEventType());
            log.debug("MongoDB Query raw result: {}", docs);

            result = mapMongoSubscriptions(docs);
            log.debug("MongoDB Query result: {}", result);

        }


        return result;
    }

    public List<T> getAll() throws JsonCacheException {
        IMap<String, HazelcastJsonValue> map = getCacheMap();
        Collection<HazelcastJsonValue> values;
        List<T> result;

        if (map != null) {
            values = map.values();
            result = mapAll(values);
        }
        else {
            log.error("Hazelcast map is not available, using MongoDB instead");
            List<SubscriptionMongoDocument> docs = subscriptionsMongoRepo.findAll();
            result = mapMongoSubscriptions(docs);
        }

        return result;
    }

    public void set(String key, Object value) throws JsonCacheException {
        try {
            var jsonValue = mapper.writeValueAsString(value);
            var hazelcastValue = new HazelcastJsonValue(jsonValue);
            map.set(key, hazelcastValue);
        } catch (JsonProcessingException e) {
            var msg = String.format("Could not set value of %s in hazelcast map %s: %s", key, map.getName(), e.getMessage());
            throw new JsonCacheException(msg, e);
        }
    }

    public void remove(String key) {
        map.remove(key);
    }

    private List<T> mapAll(Collection<HazelcastJsonValue> values) throws JsonCacheException {
        var mappedValues = new ArrayList<T>();

        for (var value : values) {
            try {
                var mappedValue = mapper.readValue(value.getValue(), mapClass);
                mappedValues.add(mappedValue);
            } catch (JsonProcessingException e) {
                var msg = String.format("Could not map json %s from hazelcast map %s to %s", value.getValue(), map.getName(), mapClass.getName());
                throw new JsonCacheException(msg, e);
            }
        }
        return mappedValues;
    }

    public List<T> mapMongoSubscriptions(List<SubscriptionMongoDocument> docs) {
        List<T> mappedValues = new ArrayList<>();

        for (SubscriptionMongoDocument doc : docs) {
            SubscriptionTrigger trigger = new SubscriptionTrigger();
            SubscriptionTrigger publisherTrigger = new SubscriptionTrigger();

            SubscriptionResourceSpec spec = new SubscriptionResourceSpec();
            SubscriptionResource resource = new SubscriptionResource();
            Subscription sub = new Subscription();

            sub.setSubscriptionId(doc.getSpec().getSubscription().getSubscriptionId());
            sub.setSubscriberId(doc.getSpec().getSubscription().getSubscriberId());
            sub.setPublisherId(doc.getSpec().getSubscription().getPublisherId());
            sub.setDeliveryType(doc.getSpec().getSubscription().getDeliveryType());
            sub.setType(doc.getSpec().getSubscription().getType());
            sub.setCallback(doc.getSpec().getSubscription().getCallback());

            spec.setSubscription(sub);

            resource.setSpec(spec);
            mappedValues.add(mapClass.cast(resource));
        }

        return mappedValues;
    }

    private IMap<String, HazelcastJsonValue> getCacheMap() {
            try {
                if (map == null) {
                    listenerAdded = false;
                }

                map = hazelcastInstance.getMap(cacheMapName);
                int mapSize = map.size();

                if (!listenerAdded) {
                    map.addEntryListener(new SubscriptionResourceEventBroadcaster(mapper, applicationEventPublisher), true);
                    listenerAdded = true;
                }
            } catch (Exception e) {
                log.warn("Using MongoDB ... " + e.getMessage());
                map = null; // stay null to retry later
            }
        return map;
    }
}
