// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;

import java.util.List;
import java.util.Optional;

/**
 * Subscription reader backed by the shared Hazelcast/JSON cache.
 *
 * <p>It translates the domain lookup by environment and event type into the
 * underlying cache query.</p>
 */
public class HazelcastCacheReader implements SubscriptionCacheReader {

    private final JsonCacheService<SubscriptionResource> subscriptionCache;

    /**
     * Creates a reader backed by the shared subscription cache service.
     *
     * @param subscriptionCache shared JSON cache service
     */
    public HazelcastCacheReader(JsonCacheService<SubscriptionResource> subscriptionCache) {
        this.subscriptionCache = subscriptionCache;
    }

    @Override
    /**
     * Reads a subscription by ID from the shared cache.
     *
     * @param subscriptionId subscription ID
     * @return the subscription if present
     * @throws JsonCacheException if the shared cache cannot be read
     */
    public Optional<SubscriptionResource> getById(String subscriptionId) throws JsonCacheException {
        if (subscriptionId == null) {
            return Optional.empty();
        }
        return subscriptionCache.getByKey(subscriptionId);
    }

    @Override
    /**
     * Reads subscriptions matching an environment and event type.
     *
     * @param environment subscription environment
     * @param eventType subscription event type
     * @return matching subscriptions
     * @throws JsonCacheException if the shared cache cannot be read
     */
    public List<SubscriptionResource> findByEnvironmentAndEventType(String environment, String eventType)
            throws JsonCacheException {
        if (environment == null || eventType == null) {
            return List.of();
        }
        var query = Query.builder(SubscriptionResource.class)
                .addMatcher("spec.environment", environment)
                .addMatcher("spec.subscription.type", eventType)
                .build();
        return subscriptionCache.getQuery(query);
    }

    @Override
    /**
     * Delegates readiness to the shared cache service.
     *
     * @return {@code true} when the shared cache is available
     */
    public boolean isReady() {
        return subscriptionCache.isReady();
    }
}