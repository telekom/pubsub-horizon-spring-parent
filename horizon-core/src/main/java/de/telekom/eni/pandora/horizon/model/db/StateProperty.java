// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.model.db;

import lombok.Getter;

//@TODO Currently unused. Might be used in HorizonMessage later.
public enum StateProperty {
    TRACE_ID("X-B3-TraceId"),
    SUBSCRIBER_ID("subscriber-id");

    @Getter
    private final String propertyName;

    StateProperty() {
        this.propertyName = name().toLowerCase()
                                  .replace('_', '-');
    }

    StateProperty(String propertyName) {
        this.propertyName = propertyName;
    }

    public static StateProperty fromString(String propertyName) {
        switch (propertyName.toUpperCase()) {
            case "X-B3-TRACEID" -> {
                return StateProperty.TRACE_ID;
            }
            case "SUBSCRIBER-ID" -> {
                return StateProperty.SUBSCRIBER_ID;
            }
        }

        return null;
    }

    @Override
    public String toString() {
        return propertyName;
    }

}
