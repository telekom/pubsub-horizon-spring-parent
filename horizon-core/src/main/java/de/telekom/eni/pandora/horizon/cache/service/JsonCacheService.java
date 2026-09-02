// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.client.HazelcastClientOfflineException;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import de.telekom.eni.pandora.horizon.cache.fallback.JsonCacheFallback;
import de.telekom.eni.pandora.horizon.cache.listener.AbstractHazelcastJsonEntryMapEventBroadcaster;
import de.telekom.eni.pandora.horizon.cache.listener.AbstractHazelcastJsonEvent;
import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Slf4j
public class JsonCacheService<T> {

    private final Class<T> mapClass;

    @Setter
    private JsonCacheFallback<T> jsonCacheFallback;

    @Setter
    private AbstractHazelcastJsonEntryMapEventBroadcaster<? extends AbstractHazelcastJsonEvent<T>> jsonEntryMapEventBroadcaster;

    @Getter
    private IMap<String, HazelcastJsonValue> map;

    private final ObjectMapper mapper;

    private final HazelcastInstance hazelcastInstance;

    private final String cacheMapName;

    private boolean listenerAdded = false;

    public JsonCacheService(Class<T> mapClass, IMap<String, HazelcastJsonValue> map, ObjectMapper mapper, HazelcastInstance hazelcastInstance, String cacheMapName) {
        this.mapClass = mapClass;
        this.map = map;
        this.mapper = mapper;
        this.hazelcastInstance = hazelcastInstance;
        this.cacheMapName = cacheMapName;
    }

    public Optional<T> getByKey(final String key) throws JsonCacheException {
        final HazelcastJsonValue value;
        try {
            value = map.get(key);
        } catch (HazelcastClientOfflineException e) {
            log.warn("Hazelcast map is not available" + e.getMessage());
            return Optional.empty();
        }
        if (value == null) {
            return Optional.empty();
        }

        try {
            var mappedValue = mapper.readValue(value.getValue(), mapClass);
            log.debug("Hazelcast getByKey result {}: {}", key, mappedValue);
            return Optional.of(mappedValue);
        } catch (JsonProcessingException e) {
            throw new JsonCacheException(
                    String.format("Could not map %s from hazelcast map %s to %s", key, map.getName(), mapClass.getName()),
                    e
            );
        }
    }

     public List<T> getQuery(Query query) throws JsonCacheException {
        final Collection<HazelcastJsonValue> values;
        try {
            values = map.values(query.toSqlPredicate());
        } catch (HazelcastClientOfflineException e) {
            log.warn("Hazelcast map is not available" + e.getMessage());
            return null;
        }

        List<T> results = mapAll(values);
        log.debug("Hazelcast getQuery result: {}", results);
        return results;
    }

    public List<T> getAll() throws JsonCacheException {
        IMap<String, HazelcastJsonValue> map = getCacheMap();
        Collection<HazelcastJsonValue> values;

        if (map != null) {
            values = map.values();
            return mapAll(values);
        }
        else if (jsonCacheFallback != null) {
            return jsonCacheFallback.getAll();
        }

        return null;
    }

    public void set(String key, Object value) throws JsonCacheException {
        if (map != null) {
            try {
                var jsonValue = mapper.writeValueAsString(value);
                var hazelcastValue = new HazelcastJsonValue(jsonValue);
                map.set(key, hazelcastValue);
            } catch (JsonProcessingException e) {
                var msg = String.format("Could not set value of %s in hazelcast map %s: %s", key, map.getName(), e.getMessage());
                throw new JsonCacheException(msg, e);
            }
        }
    }

    public void remove(String key) {
        if (map != null) {
            map.remove(key);
        }
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


    private IMap<String, HazelcastJsonValue> getCacheMap() {
            try {
                if (map == null) {
                    listenerAdded = false;
                }

                map = hazelcastInstance.getMap(cacheMapName);
                int mapSize = map.size();

                if (!listenerAdded && jsonEntryMapEventBroadcaster != null) {
                    map.addEntryListener(jsonEntryMapEventBroadcaster, true);
                    listenerAdded = true;
                }
            } catch (HazelcastClientOfflineException e) {
                log.warn("Hazelcast map is not available, using MongoDB instead " + e.getMessage());
                map = null; // stay null to retry later
            }
        return map;
    }
}
