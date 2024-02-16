package de.telekom.eni.pandora.horizon.model.db;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Coordinates(
        @JsonProperty("partition")
        int partition,
        @JsonProperty("offset")
        long offset
) {}
