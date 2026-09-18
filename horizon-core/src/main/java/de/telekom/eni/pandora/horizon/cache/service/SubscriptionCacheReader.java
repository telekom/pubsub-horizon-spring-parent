// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;

import java.util.List;
import java.util.Optional;

/**
 * Read-only contract for accessing subscription resources from a cache source.
 */
public interface SubscriptionCacheReader {

    /**
     * Looks up a subscription by its ID.
     *
     * @param subscriptionId subscription ID
     * @return the subscription if present
     * @throws JsonCacheException if the underlying cache cannot be read
     */
    Optional<SubscriptionResource> getById(String subscriptionId) throws JsonCacheException;

    /**
     * Looks up subscriptions for an environment and event type.
     *
     * @param environment subscription environment
     * @param eventType subscription event type
     * @return matching subscriptions
     * @throws JsonCacheException if the underlying cache cannot be read
     */
    List<SubscriptionResource> findByEnvironmentAndEventType(String environment, String eventType) throws JsonCacheException;

    /**
     * Indicates whether this reader can currently serve requests.
     *
     * @return {@code true} if the reader is ready
     */
    boolean isReady();
}
