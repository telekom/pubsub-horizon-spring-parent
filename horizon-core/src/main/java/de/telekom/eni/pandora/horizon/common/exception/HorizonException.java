// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.common.exception;

public abstract class HorizonException extends Exception {

    protected HorizonException(String message, Throwable e) {
        super(message, e);
    }

    protected HorizonException(String message) {
        super(message);
    }
}
