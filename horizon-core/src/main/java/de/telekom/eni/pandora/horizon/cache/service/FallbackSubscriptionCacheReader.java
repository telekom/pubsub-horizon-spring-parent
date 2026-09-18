// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

@Slf4j
public class FallbackSubscriptionCacheReader implements SubscriptionCacheReader {

    private final SubscriptionCacheReader primary;
    private final SubscriptionCacheReader fallback;

    public FallbackSubscriptionCacheReader(SubscriptionCacheReader primary, SubscriptionCacheReader fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public Optional<SubscriptionResource> getById(String subscriptionId) throws JsonCacheException {
        if (primary.isReady()) {
            try {
                return primary.getById(subscriptionId);
            } catch (RuntimeException | JsonCacheException exception) {
                log.warn("Primary subscription cache getById failed, using fallback", exception);
            }
        }
        return fallback.getById(subscriptionId);
    }

    @Override
    public List<SubscriptionResource> findByEnvironmentAndEventType(String environment, String eventType)
            throws JsonCacheException {
        if (primary.isReady()) {
            try {
                return primary.findByEnvironmentAndEventType(environment, eventType);
            } catch (RuntimeException | JsonCacheException exception) {
                log.warn("Primary subscription cache query failed, using fallback", exception);
            }
        }
        return fallback.findByEnvironmentAndEventType(environment, eventType);
    }

    @Override
    public boolean isReady() {
        return primary.isReady() || fallback.isReady();
    }
}
