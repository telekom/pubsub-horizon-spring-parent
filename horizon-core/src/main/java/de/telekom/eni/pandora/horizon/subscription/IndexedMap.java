// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.subscription;

import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.mongo.model.SnapshotMongoDocument;
import lombok.Getter;
import org.bson.types.ObjectId;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IndexedMap {

    private final Map<String, SubscriptionResource> subscriptionsById;

    private final Map<String, List<SubscriptionResource>> subscriptionsByEnvironmentAndEventType;

    @Getter
    private final long version;

    @Getter
    @Nullable
    private final ObjectId snapshotId;

    public IndexedMap(List<SnapshotMongoDocument> documents, long version, @Nullable ObjectId snapshotId) {
        this.subscriptionsById = new HashMap<>(documents.size());
        this.subscriptionsByEnvironmentAndEventType = new HashMap<>();
        this.version = version;
        this.snapshotId = snapshotId;

        documents.forEach(document -> {
           subscriptionsById.put(document.getSnapshotId(), document.getResource());

           var environmentAndEventTypeKey = createEnvironmentAndEventTypeKey(document);
           subscriptionsByEnvironmentAndEventType.putIfAbsent(environmentAndEventTypeKey, new ArrayList<>());
           subscriptionsByEnvironmentAndEventType.get(environmentAndEventTypeKey).add(document.getResource());
        });
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
        return new IndexedMap(List.of(), -1L, null);
    }

}
