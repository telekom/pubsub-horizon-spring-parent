package de.telekom.eni.pandora.horizon.cache.service;

import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.HazelcastJsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class JsonCacheServiceEvent<T> {
    private T newValue;

    private T oldValue;

    private EntryEvent<String, HazelcastJsonValue> entryEvent;
}
