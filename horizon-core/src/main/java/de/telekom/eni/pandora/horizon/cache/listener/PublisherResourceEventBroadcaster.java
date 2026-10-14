// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.HazelcastJsonValue;
import de.telekom.eni.pandora.horizon.kubernetes.resource.PublisherResource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

@Slf4j
public class PublisherResourceEventBroadcaster extends AbstractHazelcastJsonEntryMapEventBroadcaster<PublisherResourceEvent> {

    public PublisherResourceEventBroadcaster(ObjectMapper mapper, ApplicationEventPublisher applicationEventPublisher) {
        super(mapper, applicationEventPublisher);
    }

    @Override
    protected Optional<PublisherResourceEvent> map(EntryEvent<String, HazelcastJsonValue> entryEvent) {
        try {
            PublisherResource newValue = null;
            PublisherResource oldValue = null;

            if (entryEvent.getValue() != null) {
                newValue = mapper.readValue(entryEvent.getValue().getValue(), PublisherResource.class);
            }

            if (entryEvent.getOldValue() != null) {
                oldValue = mapper.readValue(entryEvent.getOldValue().getValue(), PublisherResource.class);
            }


            return Optional.of(new PublisherResourceEvent(newValue, oldValue, entryEvent));
        } catch (JsonProcessingException e) {
            log.error(e.getMessage(), e);
        }

        return Optional.empty();
    }
}
