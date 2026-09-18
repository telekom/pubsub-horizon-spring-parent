// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;

import java.util.List;
import java.util.Optional;

public class SharedSubscriptionCacheReader implements SubscriptionCacheReader {

    private final JsonCacheService<SubscriptionResource> subscriptionCache;

    public SharedSubscriptionCacheReader(JsonCacheService<SubscriptionResource> subscriptionCache) {
        this.subscriptionCache = subscriptionCache;
    }

    @Override
    public Optional<SubscriptionResource> getById(String subscriptionId) throws JsonCacheException {
        if (subscriptionId == null) {
            return Optional.empty();
        }
        return subscriptionCache.getByKey(subscriptionId);
    }

    @Override
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
    public boolean isReady() {
        return subscriptionCache.isReady();
    }
}
