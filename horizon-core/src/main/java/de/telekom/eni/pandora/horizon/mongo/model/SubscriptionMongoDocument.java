// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.mongo.model;

import de.telekom.eni.pandora.horizon.model.db.*;
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

@Document(collection = "eventSubscriptions")
public class SubscriptionMongoDocument extends Subscription {
    @MongoId
    private String id;



    public SubscriptionMongoDocument(String uuid,String subscriptionId, String deliveryType, String publisherId, String subscriberId, String type, String callback){
        super(uuid, subscriptionId, deliveryType, publisherId, subscriberId, type, callback);
    };

    public SubscriptionMongoDocument() {
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
