// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loggers for regular state changes of the local subscription cache.
 *
 * <p>The lowercase names allow switching these events off via environment variables, e.g.
 * {@code LOGGING_LEVEL_DE_TELEKOM_ENI_PANDORA_HORIZON_CACHE_EVENTS_CONNECTION=WARN}. Warnings and errors are logged
 * by the class loggers so that disabling the events cannot hide problems.</p>
 */
public final class LocalSubscriptionCacheEvents {

    /** Parent logger of all regular local subscription cache events. */
    public static final String EVENTS_LOGGER = "de.telekom.eni.pandora.horizon.cache.events";

    /** Snapshot prepared, activated, or confirmed again after a STALE phase. */
    public static final Logger SNAPSHOT = LoggerFactory.getLogger(EVENTS_LOGGER + ".snapshot");

    /** ZooKeeper connection suspended, lost, or reconnected. */
    public static final Logger CONNECTION = LoggerFactory.getLogger(EVENTS_LOGGER + ".connection");

    /** Prevents instantiation of this logger holder. */
    private LocalSubscriptionCacheEvents() {
    }
}
