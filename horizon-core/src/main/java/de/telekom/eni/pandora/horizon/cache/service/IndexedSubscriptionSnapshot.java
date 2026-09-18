// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionMongoDocument;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

record IndexedSubscriptionSnapshot(String snapshotId,
                                   Map<String, SubscriptionResource> byId,
                                   Map<QueryKey, List<SubscriptionResource>> byQuery) {

    static IndexedSubscriptionSnapshot empty() {
        return new IndexedSubscriptionSnapshot(null, Map.of(), Map.of());
    }

    static IndexedSubscriptionSnapshot fromSubscriptionDocuments(List<SubscriptionMongoDocument> documents) {
        return fromResources("repository", new ArrayList<>(documents));
    }

    static IndexedSubscriptionSnapshot fromEntries(String snapshotId, List<SubscriptionSnapshotEntry> entries) {
        var resources = new ArrayList<SubscriptionResource>(entries.size());
        for (var entry : entries) {
            if (entry == null || entry.getResource() == null) {
                throw new IllegalStateException("Invalid subscription snapshot entry without resource");
            }
            resources.add(entry.getResource());
        }
        return fromResources(snapshotId, resources);
    }

    Optional<SubscriptionResource> getById(String subscriptionId) {
        return Optional.ofNullable(byId.get(subscriptionId));
    }

    List<SubscriptionResource> getByQuery(String environment, String eventType) {
        if (environment == null || eventType == null) {
            return List.of();
        }
        return byQuery.getOrDefault(new QueryKey(environment, eventType), List.of());
    }

    List<SubscriptionResource> getAll() {
        return new ArrayList<>(byId.values());
    }

    private static IndexedSubscriptionSnapshot fromResources(String snapshotId,
                                                              List<? extends SubscriptionResource> resources) {
        var byId = new HashMap<String, SubscriptionResource>();
        var mutableByQuery = new HashMap<QueryKey, List<SubscriptionResource>>();

        for (var resource : resources) {
            validate(resource);
            var subscription = resource.getSpec().getSubscription();
            var previous = byId.put(subscription.getSubscriptionId(), resource);
            if (previous != null) {
                throw new IllegalStateException("Duplicate subscription id: " + subscription.getSubscriptionId());
            }

            var queryKey = new QueryKey(resource.getSpec().getEnvironment(), subscription.getType());
            mutableByQuery.computeIfAbsent(queryKey, ignored -> new ArrayList<>()).add(resource);
        }

        var byQuery = new HashMap<QueryKey, List<SubscriptionResource>>();
        mutableByQuery.forEach((key, value) -> byQuery.put(key, List.copyOf(value)));
        return new IndexedSubscriptionSnapshot(snapshotId, Map.copyOf(byId), Map.copyOf(byQuery));
    }

    private static void validate(SubscriptionResource resource) {
        if (resource == null || resource.getSpec() == null || resource.getSpec().getSubscription() == null) {
            throw new IllegalStateException("Invalid subscription document without subscription data");
        }
        var subscription = resource.getSpec().getSubscription();
        if (subscription.getSubscriptionId() == null || resource.getSpec().getEnvironment() == null || subscription.getType() == null) {
            throw new IllegalStateException("Invalid subscription document without id, environment or event type");
        }
    }

    private record QueryKey(String environment, String eventType) {
    }
}
