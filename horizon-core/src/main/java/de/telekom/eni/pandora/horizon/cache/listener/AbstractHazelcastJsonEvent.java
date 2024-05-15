package de.telekom.eni.pandora.horizon.cache.listener;

import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.HazelcastJsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public abstract class AbstractHazelcastJsonEvent<T> {
    private T value;

    private T oldValue;

    private EntryEvent<String, HazelcastJsonValue> entryEvent;
}
