// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.metrics;

import de.telekom.eni.pandora.horizon.model.event.DeliveryType;
import de.telekom.eni.pandora.horizon.model.event.EventMessage;
import de.telekom.eni.pandora.horizon.model.event.PublishedEventMessage;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static de.telekom.eni.pandora.horizon.metrics.HorizonMetricsConstants.*;

@Slf4j
@Getter
@AllArgsConstructor
public class HorizonMetricsHelper {

    private final MeterRegistry registry;

    private Tags buildTagsFromEventMessage(EventMessage eventMessage) {
        return Tags.of(
                TAG_ENVIRONMENT, eventMessage.getEnvironment(),
                TAG_EVENT_TYPE, eventMessage.getEvent().getType()
                );
    }

    public Tags buildTagsFromSubscriptionEventMessage(SubscriptionEventMessage eventMessage) {
        var tags = buildTagsFromEventMessage(eventMessage).and(
                TAG_SUBSCRIPTION_ID, eventMessage.getSubscriptionId(),
                TAG_DELIVERY_TYPE, eventMessage.getDeliveryType().getValue()
        );

        var additionalFields = eventMessage.getAdditionalFields();
        if (additionalFields != null) {
            if (DeliveryType.CALLBACK.getValue().equalsIgnoreCase(eventMessage.getDeliveryType().getValue())) {
                if (additionalFields.containsKey("callback-url")) {
                    tags = tags.and(TAG_CALLBACK_URL, (String)additionalFields.get("callback-url"));
                }
            }
            if (DeliveryType.SERVER_SENT_EVENT.getValue().equalsIgnoreCase(eventMessage.getDeliveryType().getValue())) {
                tags = tags.and(TAG_CALLBACK_URL, "none");
            }

            if (additionalFields.containsKey("subscriber-id")) {
                tags = tags.and(TAG_SUBSCRIBER_ID, (String)additionalFields.get("subscriber-id"));
            } else {
                tags = tags.and(TAG_SUBSCRIBER_ID, "none");
            }

        }

        return tags;
    }

    public Tags buildTagsFromPublishedEventMessage(PublishedEventMessage eventMessage) {
        return buildTagsFromEventMessage(eventMessage);
    }
}
