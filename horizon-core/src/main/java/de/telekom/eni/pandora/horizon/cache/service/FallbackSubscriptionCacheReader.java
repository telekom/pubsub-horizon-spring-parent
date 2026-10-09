// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheReadException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Subscription reader that tries a primary source first and falls back to a secondary source
 * when the primary is unavailable or fails during a read.
 */
@Slf4j
public class FallbackSubscriptionCacheReader implements SubscriptionCacheReader {

    private final SubscriptionCacheReader primary;
    private final SubscriptionCacheReader fallback;
    private final BooleanSupplier primaryReadAllowed;
    private final Runnable fallbackReadListener;
    private final AtomicBoolean primaryDegraded = new AtomicBoolean();

    /**
     * Creates a primary/fallback reader chain.
     *
     * @param primary source preferred for reads
     * @param fallback source used when the primary is not ready or fails
     */
    public FallbackSubscriptionCacheReader(SubscriptionCacheReader primary, SubscriptionCacheReader fallback) {
        this(primary, fallback, () -> true);
    }

    /**
     * Creates a primary/fallback chain with a condition controlling whether the primary may serve reads.
     *
     * @param primary source preferred for reads
     * @param fallback source used when the primary is disallowed, not ready, or fails
     * @param primaryReadAllowed condition that must permit the primary before it is queried
     */
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

    /**
     * Reads by ID, falling back when the primary cannot serve the request.
     *
     * @param subscriptionId subscription ID
     * @return the subscription if present
     * @throws SubscriptionCacheReadException if both readers fail
     */
    @Override
    public Optional<SubscriptionResource> getById(String subscriptionId) throws SubscriptionCacheReadException {
        if (isPrimaryReady()) {
            try {
                var result = primary.getById(subscriptionId);
                primaryReadSucceeded();
                return result;
            } catch (RuntimeException | SubscriptionCacheReadException exception) {
                primaryReadFailed("getById", exception);
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

    /**
     * Reads by environment and event type, falling back when the primary cannot serve the request.
     *
     * @param environment subscription environment
     * @param eventType subscription event type
     * @return matching subscriptions
     * @throws SubscriptionCacheReadException if both readers fail
     */
    @Override
    public List<SubscriptionResource> findByEnvironmentAndEventType(String environment, String eventType)
            throws SubscriptionCacheReadException {
        if (isPrimaryReady()) {
            try {
                var result = primary.findByEnvironmentAndEventType(environment, eventType);
                primaryReadSucceeded();
                return result;
            } catch (RuntimeException | SubscriptionCacheReadException exception) {
                primaryReadFailed("query", exception);
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

    /**
     * Reports readiness when either source can serve requests.
     *
     * @return {@code true} if the primary or fallback is ready
     */
    @Override
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

    private void primaryReadFailed(String operation, Exception exception) {
        if (primaryDegraded.compareAndSet(false, true)) {
            log.warn("Primary subscription cache {} failed, using fallback until the primary recovers",
                operation, exception);
        } else {
            log.debug("Primary subscription cache {} failed, using fallback", operation, exception);
        }
    }

    private void primaryReadSucceeded() {
        // get() first keeps the healthy hot path free of CAS writes.
        if (primaryDegraded.get() && primaryDegraded.compareAndSet(true, false)) {
            log.info("Primary subscription cache recovered, reads are served by the primary again");
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
