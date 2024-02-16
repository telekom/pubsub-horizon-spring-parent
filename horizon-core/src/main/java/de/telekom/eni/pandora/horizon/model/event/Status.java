// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.model.event;

public enum Status {
    PROCESSED,
    WAITING,
    FAILED,
    DELIVERING,
    DELIVERED,
    DROPPED,

    DUPLICATE;

    public StatusMessage createStatusMessage(String uuid, String eventId, DeliveryType deliveryType) {
        return new StatusMessage(uuid, eventId, this, deliveryType);
    }

}
