// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.exception;

public class SubscriptionCacheSnapshotException extends RuntimeException {

    public SubscriptionCacheSnapshotException(String message) {
        super(message);
    }

    public SubscriptionCacheSnapshotException(String message, Throwable cause) {
        super(message, cause);
    }
}