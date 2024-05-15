package de.telekom.eni.pandora.horizon.cache.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.listener.EntryAddedListener;
import com.hazelcast.map.listener.EntryEvictedListener;
import com.hazelcast.map.listener.EntryRemovedListener;
import com.hazelcast.map.listener.EntryUpdatedListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

@Slf4j
public class JsonCacheServiceEntryListener<T> implements EntryAddedListener<String, HazelcastJsonValue>,
        EntryRemovedListener<String, HazelcastJsonValue>,
        EntryUpdatedListener<String, HazelcastJsonValue>,
        EntryEvictedListener<String, HazelcastJsonValue> {

    private final Class<T> mapClass;

    private final ObjectMapper mapper;

    private final ApplicationEventPublisher applicationEventPublisher;

    public JsonCacheServiceEntryListener(Class<T> mapClass, ObjectMapper mapper, ApplicationEventPublisher applicationEventPublisher) {
        this.mapClass = mapClass;
        this.mapper = mapper;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    private Optional<JsonCacheServiceEvent<T>> toJsonCacheServiceEvent(EntryEvent<String, HazelcastJsonValue> entryEvent) {
        try {
            T newValue = null;
            T oldValue = null;

            if (entryEvent.getValue() != null) {
                newValue = mapper.readValue(entryEvent.getValue().getValue(), mapClass);
            }

            if (entryEvent.getOldValue() != null) {
                oldValue = mapper.readValue(entryEvent.getOldValue().getValue(), mapClass);
            }

            return Optional.of(new JsonCacheServiceEvent<>(newValue, oldValue, entryEvent));
        } catch (JsonProcessingException e) {
            log.error(e.getMessage(), e);
        }

        return Optional.empty();
    }

    @Override
    public void entryAdded(EntryEvent<String, HazelcastJsonValue> entryEvent) {
        toJsonCacheServiceEvent(entryEvent).ifPresent(applicationEventPublisher::publishEvent);
    }

    @Override
    public void entryEvicted(EntryEvent<String, HazelcastJsonValue> entryEvent) {
        toJsonCacheServiceEvent(entryEvent).ifPresent(applicationEventPublisher::publishEvent);
    }

    @Override
    public void entryRemoved(EntryEvent<String, HazelcastJsonValue> entryEvent) {
        toJsonCacheServiceEvent(entryEvent).ifPresent(applicationEventPublisher::publishEvent);
    }

    @Override
    public void entryUpdated(EntryEvent<String, HazelcastJsonValue> entryEvent) {
        toJsonCacheServiceEvent(entryEvent).ifPresent(applicationEventPublisher::publishEvent);
    }
}
