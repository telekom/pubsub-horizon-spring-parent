// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.listener;

import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.HazelcastJsonValue;
import de.telekom.eni.pandora.horizon.model.subscription.SubscriptionResource;

public class SubscriptionResourceEvent extends AbstractHazelcastJsonEvent<SubscriptionResource> {
    public SubscriptionResourceEvent(SubscriptionResource value, SubscriptionResource oldValue, EntryEvent<String, HazelcastJsonValue> entryEvent) {
        super(value, oldValue, entryEvent);
    }
}
