// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static java.util.Optional.empty;
import static java.util.Optional.of;

@Slf4j
public class JsonCacheService<T> {

    private final Class<T> mapClass;

    @Getter
    private IMap<String, HazelcastJsonValue> map;

    private final ObjectMapper mapper;

    private final HazelcastInstance hazelcastInstance;

    private final String cacheMapName;

    public JsonCacheService(Class<T> mapClass, IMap<String, HazelcastJsonValue> map, ObjectMapper mapper, HazelcastInstance hazelcastInstance, String cacheMapName) {
        this.mapClass = mapClass;
        this.map = map;
        this.mapper = mapper;
        this.hazelcastInstance = hazelcastInstance;
        this.cacheMapName = cacheMapName;
    }

    public Optional<T> getByKey(String key) throws JsonCacheException {
        var value = map.get(key);
        if (value != null) {
            try {
                log.debug("Raw JSON value for key {}: {}", key, value.getValue());
                var mappedValue = mapper.readValue(value.getValue(), mapClass);
                return of(mappedValue);
            } catch (JsonProcessingException e) {
                var msg = String.format("Could not map %s from hazelcast map %s to %s", key, map.getName(), mapClass.getName());
                throw new JsonCacheException(msg, e);
            }
        }
        return empty();
    }

    public List<T> getQuery(Query query) throws JsonCacheException {
        IMap<String, HazelcastJsonValue> map = getCacheMap();
        Collection<HazelcastJsonValue> values;

        if (map != null) {
            log.info("Hazelcast map is available...");
            values = map.values(query.toSqlPredicate());
        }
        else {
            log.info("Hazelcast map is not available...");
            return new ArrayList<>();
        }

        return mapAll(values);
    }

    public List<T> getAll() throws JsonCacheException {
        var values = map.values();
        return mapAll(values);
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


    private IMap<String, HazelcastJsonValue> getCacheMap() {
            try {
                map = hazelcastInstance.getMap(cacheMapName);
                HazelcastJsonValue value = map.get("key");
            } catch (Exception e) {
                log.warn("Using MongoDB ... " + e.getMessage());
                map = null; // stay null to retry later
            }
        return map;
    }
}
