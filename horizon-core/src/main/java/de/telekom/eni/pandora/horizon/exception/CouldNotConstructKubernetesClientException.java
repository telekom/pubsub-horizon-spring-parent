// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.exception;

import de.telekom.eni.pandora.horizon.common.exception.HorizonException;

public class CouldNotConstructKubernetesClientException extends HorizonException {

    public CouldNotConstructKubernetesClientException(String message, Throwable e) {
        super(message, e);
    }
}
