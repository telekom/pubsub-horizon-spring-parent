/*
 * Copyright 2026 Deutsche Telekom IT GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package de.telekom.eni.pandora.horizon.mongo.model;

import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Document;

@Getter
@Setter
@NoArgsConstructor
@Document(collection = "subscriptions.subscriber.horizon.telekom.de.v1-snapshots")
public class SnapshotMongoDocument {

    private String snapshotId;

    private String subscriptionId;

    private SubscriptionResource resource;

}
