package de.telekom.eni.pandora.horizon.cache.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import de.telekom.eni.pandora.horizon.cache.util.Query;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static java.util.Optional.empty;
import static java.util.Optional.of;

@Slf4j
public class JsonCacheService<T> {

    private final Class<T> mapClass;

    @Getter
    private final IMap<String, HazelcastJsonValue> map;

    private final ObjectMapper mapper;

    public JsonCacheService(Class<T> mapClass, IMap<String, HazelcastJsonValue> map, ObjectMapper mapper) {
        this.mapClass = mapClass;
        this.map = map;
        this.mapper = mapper;
    }

    public Optional<T> getByKey(String key) {
        var value = map.get(key);
        if (value != null) {
            try {
                var mappedValue = mapper.readValue(value.getValue(), mapClass);
                return of(mappedValue);
            } catch (JsonProcessingException e) {
                log.error("Could not map {} from hazelcast map {} to {}", key, map.getName(), mapClass.getName());
            }
        }
        return empty();
    }

    public List<T> getQuery(Query query) {
        var values = map.values(query.toSqlPredicate());
        return values.stream().map(
                value -> {
                    try {
                        return mapper.readValue(value.getValue(), mapClass);
                    } catch (JsonProcessingException e) {
                        log.error("Could not map json {} from hazelcast map {} to {}", value.getValue(), map.getName(), mapClass.getName());
                    }
                    return null;
                }
        ).filter(Objects::nonNull).toList();
    }

    public void set(String key, Object value) {
        try {
            var jsonValue = mapper.writeValueAsString(value);
            var hazelcastValue = new HazelcastJsonValue(jsonValue);
            map.set(key, hazelcastValue);
        } catch (JsonProcessingException e) {
            log.error("Could not set value of {} in hazelcast map {}: {}", key, map.getName(), e.getMessage());
        }
    }

    public void remove(String key) {
        map.remove(key);
    }

}
