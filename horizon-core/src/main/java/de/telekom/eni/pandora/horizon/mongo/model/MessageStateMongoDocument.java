// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.mongo.model;

import de.telekom.eni.pandora.horizon.model.db.Coordinates;
import de.telekom.eni.pandora.horizon.model.db.PartialEvent;
import de.telekom.eni.pandora.horizon.model.db.State;
import de.telekom.eni.pandora.horizon.model.db.StateError;
import de.telekom.eni.pandora.horizon.model.event.DeliveryType;
import de.telekom.eni.pandora.horizon.model.event.Status;
import de.telekom.eni.pandora.horizon.model.meta.EventRetentionTime;
import de.telekom.jsonfilter.operator.EvaluationResult;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoId;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Document(collection = "status")
public class MessageStateMongoDocument extends State {
    @MongoId
    private String id;

    public MessageStateMongoDocument(String uuid, Coordinates coordinates, Status status, String environment, DeliveryType deliveryType, String subscriptionId, PartialEvent partialEvent, Map<String, String> properties, String multiplexedFrom, EventRetentionTime eventRetentionTime, Date timestamp, Date modified, StateError error, List<String> appliedScopes, EvaluationResult scopeEvaluationResult, EvaluationResult consumerEvaluationResult) {
        super(uuid, coordinates, status, environment, deliveryType, subscriptionId, partialEvent, properties, multiplexedFrom, eventRetentionTime, Objects.requireNonNullElse(eventRetentionTime, EventRetentionTime.DEFAULT).getTopic(), timestamp, modified, error, appliedScopes, scopeEvaluationResult, consumerEvaluationResult);
    }

    public MessageStateMongoDocument() {
    }

    @Override
    public String getUuid() {
        return Objects.requireNonNullElse(super.getUuid(), this.id);
    }

    @Override
    public void setUuid(String uuid) {
        this.id = uuid;
        super.setUuid(uuid);
    }



}
