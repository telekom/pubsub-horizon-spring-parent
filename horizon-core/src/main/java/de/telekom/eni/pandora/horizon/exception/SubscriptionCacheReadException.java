// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.exception;

/** Signals that a subscription cache could not serve a read request. */
public class SubscriptionCacheReadException extends Exception {

    /**
     * Creates a cache read exception with a detail message.
     *
     * @param message explanation of the read failure
     */
    public SubscriptionCacheReadException(String message) {
        super(message);
    }

    /**
     * Creates a cache read exception with a detail message and cause.
     *
     * @param message explanation of the read failure
     * @param cause underlying failure
     */
    public SubscriptionCacheReadException(String message, Throwable cause) {
        super(message, cause);
    }
}