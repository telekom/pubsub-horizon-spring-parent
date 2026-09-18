// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;

import java.util.List;
import java.util.Optional;

public interface SubscriptionCacheReader {

    Optional<SubscriptionResource> getById(String subscriptionId) throws JsonCacheException;

    List<SubscriptionResource> findByEnvironmentAndEventType(String environment, String eventType) throws JsonCacheException;

    boolean isReady();
}
