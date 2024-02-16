// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.model.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.telekom.eni.pandora.horizon.model.meta.EventRetentionTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.fasterxml.jackson.annotation.JsonInclude.Include;

@Getter
@Setter
@NoArgsConstructor
@JsonInclude(Include.NON_NULL)
public class SubscriptionEventMessage extends EventMessage {

    private DeliveryType deliveryType;

    private String subscriptionId;

    private String multiplexedFrom;

    private EventRetentionTime eventRetentionTime;

    private List<String> appliedScopes;

    public SubscriptionEventMessage(Event event, String environment, DeliveryType deliveryType, String subscriptionId, String multiplexedFrom) {
        this(event, environment, deliveryType, subscriptionId, multiplexedFrom, EventRetentionTime.DEFAULT, new ArrayList<>(), new HashMap<>(), new HashMap<>());
    }

    public SubscriptionEventMessage(Event event, String environment, DeliveryType deliveryType, String subscriptionId, String multiplexedFrom, EventRetentionTime eventRetentionTime) {
        this(event, environment, deliveryType, subscriptionId, multiplexedFrom, eventRetentionTime, new ArrayList<>(), new HashMap<>(), new HashMap<>());
    }

    public SubscriptionEventMessage(Event event, String environment, DeliveryType deliveryType, String subscriptionId, String multiplexedFrom, EventRetentionTime eventRetentionTime, List<String> appliedScopes,
            Map<String, Object> additionalFields, Map<String, List<String>> httpHeaders) {
        super(event, environment, additionalFields, httpHeaders);
        this.subscriptionId = subscriptionId;
        this.deliveryType = deliveryType;
        this.appliedScopes = appliedScopes;
        this.multiplexedFrom = multiplexedFrom;
        this.eventRetentionTime = eventRetentionTime;
    }
}
