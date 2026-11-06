// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.exception;

/** Signals that a subscription snapshot head or its entries are invalid or incomplete. */
public class SubscriptionCacheSnapshotException extends RuntimeException {

    /**
     * Creates a snapshot exception with a detail message.
     *
     * @param message explanation of the snapshot failure
     */
    public SubscriptionCacheSnapshotException(String message) {
        super(message);
    }

    /**
     * Creates a snapshot exception with a detail message and cause.
     *
     * @param message explanation of the snapshot failure
     * @param cause underlying failure
     */
    public SubscriptionCacheSnapshotException(String message, Throwable cause) {
        super(message, cause);
    }
}