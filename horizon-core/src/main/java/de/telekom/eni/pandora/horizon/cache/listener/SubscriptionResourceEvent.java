package de.telekom.eni.pandora.horizon.cache.listener;

import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.HazelcastJsonValue;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;

public class SubscriptionResourceEvent extends AbstractHazelcastJsonEvent<SubscriptionResource> {
    public SubscriptionResourceEvent(SubscriptionResource value, SubscriptionResource oldValue, EntryEvent<String, HazelcastJsonValue> entryEvent) {
        super(value, oldValue, entryEvent);
    }
}
