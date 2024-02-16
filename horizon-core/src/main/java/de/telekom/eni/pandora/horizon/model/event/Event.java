// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.model.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.telekom.eni.pandora.horizon.model.event.validation.EventDataConstraint;
import de.telekom.eni.pandora.horizon.model.event.validation.RegexPattern;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;

@Getter
@Setter
@NoArgsConstructor
@EventDataConstraint
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Event implements Serializable {

    @Serial
    private static final long serialVersionUID = 700L;

    @JsonProperty(required = true, value = "id")
    @NotNull(message = "Field id must not be null")
    @Pattern(message = "Field id must be a valid UUID", regexp = RegexPattern.UUID)
    @NotBlank(message = "Field id must not be empty and must not only consist of spaces")
    private String id;

    @JsonProperty(required = true, value = "type")
    @NotNull(message = "Field type must not be null")
    @NotBlank(message = "Field type must not be empty and must not only consist of spaces")
    @Pattern(message="Field type must contain only of a-z, A-Z, 0-9, '.', '-'", regexp = RegexPattern.EVENT_TYPE)
    private String type;

    @JsonProperty(required = true, value = "source")
    @NotNull(message = "Field source must not be null")
    @NotBlank(message = "Field source must not be empty and must not only consist of spaces")
    private String source;

    @JsonProperty(required = true, value = "specversion")
    @NotEmpty(message = "Field specversion must not be empty")
    @NotBlank(message = "Field specversion must not be empty and must not only consist of spaces")
    private String specVersion;

    @JsonProperty(value = "datacontenttype")
    private String dataContentType;

    @JsonProperty(value = "dataref")
    private String dataRef;

    @Pattern(message = "Field time must be a valid IS0 8601 timestamp", regexp = RegexPattern.ISO8601_TIME)
    private String time;

    @JsonProperty(value = "data")
    private Object data;
}
