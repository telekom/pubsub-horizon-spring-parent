// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheReadException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

@Slf4j
/**
 * Subscription reader that tries a primary source first and falls back to a secondary source
 * when the primary is unavailable or fails during a read.
 */
public class FallbackSubscriptionCacheReader implements SubscriptionCacheReader {

    private final SubscriptionCacheReader primary;
    private final SubscriptionCacheReader fallback;
    private final BooleanSupplier primaryReadAllowed;
    private final Runnable fallbackReadListener;

    /**
     * Creates a primary/fallback reader chain.
     *
     * @param primary source preferred for reads
     * @param fallback source used when the primary is not ready or fails
     */
    public FallbackSubscriptionCacheReader(SubscriptionCacheReader primary, SubscriptionCacheReader fallback) {
        this(primary, fallback, () -> true);
    }

    public FallbackSubscriptionCacheReader(SubscriptionCacheReader primary, SubscriptionCacheReader fallback,
                                           BooleanSupplier primaryReadAllowed) {
        this(primary, fallback, primaryReadAllowed, () -> { });
    }

    /**
     * Creates a primary/fallback reader chain that reports every read served by the fallback.
     *
     * @param primary source preferred for reads
     * @param fallback source used when the primary is not ready or fails
     * @param primaryReadAllowed whether the primary may currently serve reads
     * @param fallbackReadListener invoked before each read that is served by the fallback
     */
    public FallbackSubscriptionCacheReader(SubscriptionCacheReader primary, SubscriptionCacheReader fallback,
                                           BooleanSupplier primaryReadAllowed, Runnable fallbackReadListener) {
        this.primary = primary;
        this.fallback = fallback;
        this.primaryReadAllowed = primaryReadAllowed;
        this.fallbackReadListener = fallbackReadListener;
    }

    @Override
    /**
     * Reads by ID, falling back when the primary cannot serve the request.
     *
     * @param subscriptionId subscription ID
     * @return the subscription if present
     * @throws SubscriptionCacheReadException if both readers fail
     */
    public Optional<SubscriptionResource> getById(String subscriptionId) throws SubscriptionCacheReadException {
        if (isPrimaryReady()) {
            try {
                return primary.getById(subscriptionId);
            } catch (RuntimeException | SubscriptionCacheReadException exception) {
                log.warn("Primary subscription cache getById failed, using fallback", exception);
            }
        }
        fallbackReadListener.run();
        try {
            return fallback.getById(subscriptionId);
        } catch (SubscriptionCacheReadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SubscriptionCacheReadException("Fallback subscription cache getById failed", exception);
        }
    }

    @Override
    /**
     * Reads by environment and event type, falling back when the primary cannot serve the request.
     *
     * @param environment subscription environment
     * @param eventType subscription event type
     * @return matching subscriptions
     * @throws SubscriptionCacheReadException if both readers fail
     */
    public List<SubscriptionResource> findByEnvironmentAndEventType(String environment, String eventType)
            throws SubscriptionCacheReadException {
        if (isPrimaryReady()) {
            try {
                return primary.findByEnvironmentAndEventType(environment, eventType);
            } catch (RuntimeException | SubscriptionCacheReadException exception) {
                log.warn("Primary subscription cache query failed, using fallback", exception);
            }
        }
        fallbackReadListener.run();
        try {
            return fallback.findByEnvironmentAndEventType(environment, eventType);
        } catch (SubscriptionCacheReadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SubscriptionCacheReadException("Fallback subscription cache query failed", exception);
        }
    }

    @Override
    /**
     * Reports readiness when either source can serve requests.
     *
     * @return {@code true} if the primary or fallback is ready
     */
    public boolean isReady() {
        if (isPrimaryReady()) {
            return true;
        }
        try {
            return fallback.isReady();
        } catch (RuntimeException exception) {
            log.warn("Failed to determine fallback subscription cache readiness", exception);
            return false;
        }
    }

    private boolean isPrimaryReady() {
        try {
            return primaryReadAllowed.getAsBoolean() && primary.isReady();
        } catch (RuntimeException exception) {
            log.warn("Failed to determine primary subscription cache readiness, using fallback", exception);
            return false;
        }
    }
}
