// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.model.meta;

import lombok.Getter;

/**
 * Enum for Horizon component ids.
 * These ids are used to identify the different components of the Horizon system within kafka as they are used to define
 * the kafka consumer and producer groups. They are crucial for the correct calculation of the end-to-end latency metrics
 * between starlight and comet as they are also used as identifier in an event's kafka header.
 *
 * @since 3.0.0
 */
@Getter
public enum HorizonComponentId {
    // aka polaris
    PLUNGER("plunger", "plunger"),
    // aka starlight
    PRODUCER("producer", "producer"),
    // aka galaxy
    MULTIPLEXER("multiplexer", "multiplexer"),
    // aka comet
    DUDE("dude", "dude"),
    VOYAGER("voyager", "voyager"),
    // aka pulsar
    TASSE("tasse", "tasse"),
    UNSET("unset", "unset");

    private final String clientId;
    private final String groupId;

    HorizonComponentId(String clientId, String groupId) {
        this.clientId = clientId;
        this.groupId = groupId;
    }

    public static HorizonComponentId fromGroupId(String groupId) {
        try {
            return HorizonComponentId.valueOf(groupId);
        } catch (Exception exception) {
            return switch (groupId.toLowerCase()) {
                case "plunger", "polaris" -> PLUNGER;
                case "multiplexer", "galaxy" -> MULTIPLEXER;
                case "dude", "comet" -> DUDE;
                case "producer", "starlight" -> PRODUCER;
                case "voyager" -> VOYAGER;
                case "tasse", "pulsar" -> TASSE;
                default -> UNSET;
            };
        }
    }


}
