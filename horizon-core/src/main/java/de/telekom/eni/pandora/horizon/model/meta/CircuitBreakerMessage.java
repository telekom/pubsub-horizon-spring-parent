// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.model.meta;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.time.temporal.ChronoUnit;
import java.util.Date;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class CircuitBreakerMessage {

    private String subscriptionId;

    private String eventType;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssX", timezone = "UTC")
    private Date lastModified;

    private String originMessageId;

    private CircuitBreakerStatus status;

    private String environment;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssX", timezone = "UTC")
    private Date lastOpened;

    private int loopCounter;

    public CircuitBreakerMessage() {
        super();
    }

    public CircuitBreakerMessage(String subscriptionId, String eventType, Date lastModified, String originMessageId, CircuitBreakerStatus status, String environment, Date lastOpened, int loopCounter) {
        this.subscriptionId = subscriptionId;
        this.eventType = eventType;
        this.lastModified = lastModified != null ? Date.from(lastModified.toInstant().truncatedTo(ChronoUnit.SECONDS)) : null;
        this.originMessageId = originMessageId;
        this.status = status;
        this.environment = environment;
        this.lastOpened = lastOpened != null ? Date.from(lastOpened.toInstant().truncatedTo(ChronoUnit.SECONDS)) : null;
        this.loopCounter = loopCounter;
    }

}
