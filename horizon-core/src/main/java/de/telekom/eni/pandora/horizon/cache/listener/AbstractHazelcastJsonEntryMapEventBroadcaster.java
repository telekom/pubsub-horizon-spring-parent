package de.telekom.eni.pandora.horizon.cache.listener;

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
public abstract class AbstractHazelcastJsonEntryMapEventBroadcaster<T extends AbstractHazelcastJsonEvent<?>> implements EntryAddedListener<String, HazelcastJsonValue>,
        EntryRemovedListener<String, HazelcastJsonValue>,
        EntryUpdatedListener<String, HazelcastJsonValue>,
        EntryEvictedListener<String, HazelcastJsonValue> {

    protected final ObjectMapper mapper;

    private final ApplicationEventPublisher applicationEventPublisher;

    public AbstractHazelcastJsonEntryMapEventBroadcaster(ObjectMapper mapper, ApplicationEventPublisher applicationEventPublisher) {
        this.mapper = mapper;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    protected abstract Optional<T> map(EntryEvent<String, HazelcastJsonValue> entryEvent);

    @Override
    public void entryAdded(EntryEvent<String, HazelcastJsonValue> entryEvent) {
        map(entryEvent).ifPresent(applicationEventPublisher::publishEvent);
    }

    @Override
    public void entryEvicted(EntryEvent<String, HazelcastJsonValue> entryEvent) {
        map(entryEvent).ifPresent(applicationEventPublisher::publishEvent);
    }

    @Override
    public void entryRemoved(EntryEvent<String, HazelcastJsonValue> entryEvent) {
        map(entryEvent).ifPresent(applicationEventPublisher::publishEvent);
    }

    @Override
    public void entryUpdated(EntryEvent<String, HazelcastJsonValue> entryEvent) {
        map(entryEvent).ifPresent(applicationEventPublisher::publishEvent);
    }
}
