// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.model.meta;

import lombok.Getter;

import java.io.Serial;

@Getter
public enum EventRetentionTime {
    TTL_7_DAYS("subscribed", 604800000),
    TTL_5_DAYS("subscribed_5d", 432000000),
    TTL_3_DAYS("subscribed_3d", 259200000),
    TTL_1_DAY("subscribed_1d", 86400000),
    TTL_1_HOUR("subscribed_1h", 3600000),

    // If you change this, also change toRoverConfigString
    DEFAULT( TTL_7_DAYS.getTopic(), TTL_7_DAYS.getRetentionInMs());

    private final String topic;
    private final long retentionInMs;
    @Serial
    private static final long serialVersionUID = 400L;

    /**
     * Converts any String to a {@link EventRetentionTime},
     * firstly tries to create a {@link EventRetentionTime} by {@link #valueOf(String)}.
     * If that does not work, it tries to check the string against the Strings that can be defined in the rover config.
     * <br><br>
     * If no match can be found, returns {@link #TTL_7_DAYS}
     * @return {@link #TTL_7_DAYS}, {@link #TTL_5_DAYS}, {@link #TTL_3_DAYS}, {@link #TTL_1_DAY}, {@link #TTL_1_HOUR}
     * @see #toRoverConfigString()
     */
    public static EventRetentionTime fromString(String string) {
        try {
            return EventRetentionTime.valueOf(string);
        } catch (Exception exception) {
            return switch (string.toLowerCase()) {
                case "7d" -> TTL_7_DAYS;
                case "5d" -> TTL_5_DAYS;
                case "3d" -> TTL_3_DAYS;
                case "1d" -> TTL_1_DAY;
                case "1h" -> TTL_1_HOUR;
                default -> DEFAULT;
            };
        }
    }

    /**
     * Will convert the {@link EventRetentionTime} into the string that the customer can configure in the subscription in rover.
     * 7days is the default.
     * @return 7_days, 5d, 3d, 1d or 1h
     */
    public String toRoverConfigString() {
        return switch (this) {
            case TTL_7_DAYS -> "7d";
            case TTL_5_DAYS -> "5d";
            case TTL_3_DAYS -> "3d";
            case TTL_1_DAY -> "1d";
            case TTL_1_HOUR -> "1h";
            default -> "7d";
        };
    }

    EventRetentionTime(String topic, long retentionInMs) {
        this.topic = topic;
        this.retentionInMs = retentionInMs;
    }

}
