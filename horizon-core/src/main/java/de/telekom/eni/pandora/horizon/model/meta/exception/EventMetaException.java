// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.model.meta.exception;

import de.telekom.eni.pandora.horizon.common.exception.HorizonException;

public class EventMetaException extends HorizonException {
    public EventMetaException(String message) {
        super(message);
    }
}
