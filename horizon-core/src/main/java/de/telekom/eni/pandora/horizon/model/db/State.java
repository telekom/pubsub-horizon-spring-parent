// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.model.db;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.telekom.eni.pandora.horizon.model.event.DeliveryType;
import de.telekom.eni.pandora.horizon.model.event.EventMessage;
import de.telekom.eni.pandora.horizon.model.event.Status;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import de.telekom.eni.pandora.horizon.model.meta.EventRetentionTime;
import de.telekom.jsonfilter.operator.EvaluationResult;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.*;

import static java.util.Optional.ofNullable;

@Getter
@Setter
@AllArgsConstructor
@ToString
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public class State implements Serializable {
    private String uuid;
    private Coordinates coordinates;
    private Status status;
    private String environment;
    private DeliveryType deliveryType;
    private String subscriptionId;
    private PartialEvent event;
    private Map<String, String> properties;
    private String multiplexedFrom;
    private EventRetentionTime eventRetentionTime;
    private String topic;
    private Date timestamp;
    private Date modified;
    private StateError error;
    private List<String> appliedScopes;
    private EvaluationResult scopeEvaluationResult;
    private EvaluationResult consumerEvaluationResult;

    public StateError getError() {
        return error;
    }

    public void setError(StateError error) {
        this.error = error;
    }

    public void setCoordinates(int partition, long offset) {
        coordinates = new Coordinates(partition, offset);
    }

    public void setProperty(StateProperty property, String value) {
        properties.put(property.getPropertyName(), value);
    }

    public Optional<String> getProperty(StateProperty property) {
        return ofNullable(properties.get(property.getPropertyName()));
    }


    public static StateBuilder builder(Status status, SubscriptionEventMessage message, EvaluationResult scopeEvaluationResult, EvaluationResult consumerEvaluationResult) {
        return builder(status, message, scopeEvaluationResult, consumerEvaluationResult, Objects.requireNonNullElse(message.getEventRetentionTime(), EventRetentionTime.DEFAULT).getTopic());
    }

    public static StateBuilder builder(Status status, EventMessage message, EvaluationResult scopeEvaluationResult, EvaluationResult consumerEvaluationResult, String kafkaTopic) {
        var event = message.getEvent();

        var builder = new StateBuilder().uuid(message.getUuid())
                .status(status)
                .topic(kafkaTopic)
                .event( new PartialEvent( event.getId(), event.getType()))
                .timestamp(Date.from(Instant.now()))
                .properties(new HashMap<>())
                .scopeEvaluationResult(scopeEvaluationResult)
                .consumerEvaluationResult(consumerEvaluationResult);

        if (message instanceof SubscriptionEventMessage subMessage) {
            var properties = subMessage.getAdditionalFields();
            var newProperties = new HashMap<String, String>();

            builder = builder.deliveryType(subMessage.getDeliveryType())
                    .eventRetentionTime(subMessage.getEventRetentionTime())
                    .appliedScopes(subMessage.getAppliedScopes())
                    .subscriptionId(subMessage.getSubscriptionId());
            properties.forEach((key, value) -> {
                var valStr = String.valueOf(value);
                if (valStr != null && !valStr.trim().equals("")) {
                    newProperties.put(key, valStr);
                }
            });
            builder.properties(newProperties);
        }

        return builder;
    }

}
