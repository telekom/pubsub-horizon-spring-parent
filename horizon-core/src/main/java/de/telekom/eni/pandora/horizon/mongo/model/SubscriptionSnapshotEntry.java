// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.mongo.model;

import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Getter
@Setter
@NoArgsConstructor
@Document
public class SubscriptionSnapshotEntry {

    @Id
    private ObjectId id;

    private String snapshotId;

    private String subscriptionId;

    private SubscriptionResource resource;
}
