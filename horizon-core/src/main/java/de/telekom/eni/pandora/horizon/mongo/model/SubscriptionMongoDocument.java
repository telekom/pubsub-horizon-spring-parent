// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.mongo.model;

import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoId;

@Getter
@Setter
@NoArgsConstructor
@Document(collection = "subscriptions.subscriber.horizon.telekom.de.v1")
public class SubscriptionMongoDocument extends SubscriptionResource {

    @MongoId
    private String id;

}
