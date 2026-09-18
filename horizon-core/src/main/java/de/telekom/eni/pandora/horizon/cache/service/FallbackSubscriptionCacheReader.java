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
/**
 * Subscription reader that tries a primary source first and falls back to a secondary source
 * when the primary is unavailable or fails during a read.
 */
public class FallbackSubscriptionCacheReader implements SubscriptionCacheReader {

    private final SubscriptionCacheReader primary;
    private final SubscriptionCacheReader fallback;

    /**
     * Creates a primary/fallback reader chain.
     *
     * @param primary source preferred for reads
     * @param fallback source used when the primary is not ready or fails
     */
    public FallbackSubscriptionCacheReader(SubscriptionCacheReader primary, SubscriptionCacheReader fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    /**
     * Reads by ID, falling back when the primary cannot serve the request.
     *
     * @param subscriptionId subscription ID
     * @return the subscription if present
     * @throws JsonCacheException if both readers fail
     */
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
    /**
     * Reads by environment and event type, falling back when the primary cannot serve the request.
     *
     * @param environment subscription environment
     * @param eventType subscription event type
     * @return matching subscriptions
     * @throws JsonCacheException if both readers fail
     */
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
    /**
     * Reports readiness when either source can serve requests.
     *
     * @return {@code true} if the primary or fallback is ready
     */
    public boolean isReady() {
        return primary.isReady() || fallback.isReady();
    }
}
