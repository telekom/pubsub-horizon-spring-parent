// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotEntry;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable lookup indexes for one complete subscription snapshot.
 *
 * <p>The snapshot supports direct lookup by subscription ID and lookup by
 * environment plus event type. Duplicate IDs and invalid subscription data
 * are rejected while the snapshot is built.</p>
 */
record IndexedSubscriptionSnapshot(SnapshotMetadata metadata,
                                   Map<String, SubscriptionResource> subscriptionsById,
                                   Map<EnvironmentEventTypeKey, List<SubscriptionResource>> subscriptionsByEnvironmentAndEventType) {

    /**
     * Creates the initial empty snapshot.
     *
     * @return an empty snapshot that has not been activated
     */
    static IndexedSubscriptionSnapshot empty() {
        return new IndexedSubscriptionSnapshot(null, Map.of(), Map.of());
    }

    /**
     * Builds an indexed snapshot from persisted snapshot entries.
     *
     * @param snapshotHead metadata of the persisted snapshot
     * @param entries persisted subscription entries
     * @return an indexed snapshot
     */
    static IndexedSubscriptionSnapshot fromSnapshotEntries(SubscriptionSnapshotHead snapshotHead,
                                                             List<SubscriptionSnapshotEntry> entries) {
        var resources = new ArrayList<SubscriptionResource>(entries.size());
        for (var entry : entries) {
            if (entry == null || entry.getResource() == null) {
                throw new SubscriptionCacheSnapshotException("Invalid subscription snapshot entry without resource");
            }
            resources.add(entry.getResource());
        }
        return fromSubscriptionResources(SnapshotMetadata.from(snapshotHead), resources);
    }

    String snapshotId() {
        return metadata == null ? null : metadata.snapshotId();
    }

    /**
     * Looks up one subscription by its subscription ID.
     *
     * @param subscriptionId subscription ID
     * @return the matching subscription, if present
     */
    Optional<SubscriptionResource> getById(String subscriptionId) {
        return Optional.ofNullable(subscriptionsById.get(subscriptionId));
    }

    /**
     * Looks up subscriptions for one environment and event type.
     *
     * @param environment subscription environment
     * @param eventType subscription event type
     * @return matching subscriptions, or an empty list
     */
    List<SubscriptionResource> findByEnvironmentAndEventType(String environment, String eventType) {
        if (environment == null || eventType == null) {
            return List.of();
        }
        return subscriptionsByEnvironmentAndEventType.getOrDefault(
            new EnvironmentEventTypeKey(environment, eventType), List.of());
    }

    /**
     * Returns a copy of all indexed subscriptions.
     *
     * @return all subscriptions in this snapshot
     */
    List<SubscriptionResource> getAll() {
        return new ArrayList<>(subscriptionsById.values());
    }

    /**
     * Indicates whether this snapshot contains no subscriptions.
     *
     * @return {@code true} if no subscription is indexed
     */
    boolean isEmpty() {
        return subscriptionsById.isEmpty();
    }

        private static IndexedSubscriptionSnapshot fromSubscriptionResources(
            SnapshotMetadata metadata,
            List<? extends SubscriptionResource> resources) {
        var subscriptionsById = new HashMap<String, SubscriptionResource>();
        var subscriptionsByEnvironmentAndEventType = new HashMap<EnvironmentEventTypeKey, List<SubscriptionResource>>();

        for (var resource : resources) {
            validate(resource);
            var subscription = resource.getSpec().getSubscription();
            var previous = subscriptionsById.putIfAbsent(subscription.getSubscriptionId(), resource);
            if (previous != null) {
                throw new SubscriptionCacheSnapshotException("Duplicate subscription id: " + subscription.getSubscriptionId());
            }

            var lookupKey = new EnvironmentEventTypeKey(resource.getSpec().getEnvironment(), subscription.getType());
            subscriptionsByEnvironmentAndEventType.computeIfAbsent(lookupKey, ignored -> new ArrayList<>()).add(resource);
        }

        var immutableSubscriptionsByEnvironmentAndEventType = new HashMap<EnvironmentEventTypeKey, List<SubscriptionResource>>();
        subscriptionsByEnvironmentAndEventType.forEach((key, value) ->
            immutableSubscriptionsByEnvironmentAndEventType.put(key, List.copyOf(value)));
        return new IndexedSubscriptionSnapshot(
            metadata,
            Map.copyOf(subscriptionsById),
            Map.copyOf(immutableSubscriptionsByEnvironmentAndEventType));
    }

    private static void validate(SubscriptionResource resource) {
        if (resource == null || resource.getSpec() == null || resource.getSpec().getSubscription() == null) {
            throw new SubscriptionCacheSnapshotException("Invalid subscription document without subscription data");
        }
        var subscription = resource.getSpec().getSubscription();
        if (subscription.getSubscriptionId() == null || resource.getSpec().getEnvironment() == null || subscription.getType() == null) {
            throw new SubscriptionCacheSnapshotException("Invalid subscription document without id, environment or event type");
        }
    }

    private record EnvironmentEventTypeKey(String environment, String eventType) {
    }

    record SnapshotMetadata(String id,
                            String snapshotId,
                            Long documentCount,
                            Long revision,
                            String sourceHash,
                            Instant createdAt) {

        static SnapshotMetadata from(SubscriptionSnapshotHead snapshotHead) {
            return new SnapshotMetadata(
                snapshotHead.getId(),
                snapshotHead.getSnapshotId(),
                snapshotHead.getDocumentCount(),
                snapshotHead.getRevision(),
                snapshotHead.getSourceHash(),
                snapshotHead.getCreatedAt() == null ? null : snapshotHead.getCreatedAt().toInstant());
        }
    }
}
