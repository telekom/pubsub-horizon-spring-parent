package de.telekom.eni.pandora.horizon.cache.service;

import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.listener.EntryAddedListener;
import com.hazelcast.map.listener.EntryEvictedListener;
import com.hazelcast.map.listener.EntryRemovedListener;
import com.hazelcast.map.listener.EntryUpdatedListener;
import org.springframework.context.ApplicationEventPublisher;

public class JsonCacheServiceEntryListener implements EntryAddedListener<String, HazelcastJsonValue>,
        EntryRemovedListener<String, HazelcastJsonValue>,
        EntryUpdatedListener<String, HazelcastJsonValue>,
        EntryEvictedListener<String, HazelcastJsonValue> {

    private final ApplicationEventPublisher applicationEventPublisher;

    public JsonCacheServiceEntryListener(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void entryAdded(EntryEvent<String, HazelcastJsonValue> entryEvent) {
        applicationEventPublisher.publishEvent(new JsonCacheServiceEvent(entryEvent));
    }

    @Override
    public void entryEvicted(EntryEvent<String, HazelcastJsonValue> entryEvent) {
        applicationEventPublisher.publishEvent(new JsonCacheServiceEvent(entryEvent));
    }

    @Override
    public void entryRemoved(EntryEvent<String, HazelcastJsonValue> entryEvent) {
        applicationEventPublisher.publishEvent(new JsonCacheServiceEvent(entryEvent));
    }

    @Override
    public void entryUpdated(EntryEvent<String, HazelcastJsonValue> entryEvent) {
        applicationEventPublisher.publishEvent(new JsonCacheServiceEvent(entryEvent));
    }
}
