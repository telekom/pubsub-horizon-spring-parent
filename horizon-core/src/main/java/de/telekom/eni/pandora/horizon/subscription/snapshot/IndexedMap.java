// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.subscription.snapshot;

import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.mongo.model.SnapshotMongoDocument;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IndexedMap {
    private static final IndexedMap EMPTY_INDEXED_MAP = new IndexedMap(List.of(), SnapshotId.EMPTY_SNAPSHOT_ID);

    private final Map<String, SubscriptionResource> subscriptionsById;
    private final Map<String, List<SubscriptionResource>> subscriptionsByEnvironmentAndEventType;
    @Getter
    private final SnapshotId snapshotId;

    public IndexedMap(List<SnapshotMongoDocument> documents, SnapshotId snapshotId) {
        var subscriptionsById = new HashMap<String, SubscriptionResource>(documents.size());
        var subscriptionsByEnvironmentAndEventType = new HashMap<String, List<SubscriptionResource>>();
        this.snapshotId = snapshotId;

        documents.forEach(document -> {
           subscriptionsById.put(document.getSnapshotId(), document.getResource());

           var environmentAndEventTypeKey = createEnvironmentAndEventTypeKey(document);
           subscriptionsByEnvironmentAndEventType.putIfAbsent(environmentAndEventTypeKey, new ArrayList<>());
           subscriptionsByEnvironmentAndEventType.get(environmentAndEventTypeKey).add(document.getResource());
        });

        this.subscriptionsById = Collections.unmodifiableMap(subscriptionsById);
        this.subscriptionsByEnvironmentAndEventType = Collections.unmodifiableMap(subscriptionsByEnvironmentAndEventType);
    }

    public SubscriptionResource get(String subscriptionId) {
        return subscriptionsById.get(subscriptionId);
    }

    public List<SubscriptionResource> get(String environment, String eventType) {
        return subscriptionsByEnvironmentAndEventType.get(createEnvironmentAndEventTypeKey(environment, eventType));
    }

    public static String createEnvironmentAndEventTypeKey(String environment, String eventType) {
        return environment + "#" + eventType;
    }

    public static String createEnvironmentAndEventTypeKey(SnapshotMongoDocument snapshot) {
        var environment = snapshot.getResource().getSpec().getEnvironment();
        var eventType = snapshot.getResource().getSpec().getSubscription().getType();
        return createEnvironmentAndEventTypeKey(environment, eventType);
    }

    public static IndexedMap empty() {
        return EMPTY_INDEXED_MAP;
    }

    public int getSize() {
        return subscriptionsById.size();
    }
}
