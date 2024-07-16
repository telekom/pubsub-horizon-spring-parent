// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.model.meta;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.telekom.eni.pandora.horizon.model.common.Cacheable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serial;
import java.time.Instant;
import java.util.Date;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class CircuitBreakerMessage {

    private String subscriptionId;

    private String eventType;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'hh:mm:ss.SSSXX", timezone = "UTC")
    private Date lastModified;

    private String originMessageId;

    private CircuitBreakerStatus status;

    private String environment;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'hh:mm:ss.SSSXX", timezone = "UTC")
    private Date lastRepublished;

    private int republishingCount;

    public CircuitBreakerMessage() {
        super();
    }

    public CircuitBreakerMessage(String subscriptionId, String eventType, Date lastModified, String originMessageId, CircuitBreakerStatus status, String environment, Date lastRepublished, int republishingCount) {
        this.subscriptionId = subscriptionId;
        this.eventType = eventType;
        this.lastModified = lastModified;
        this.originMessageId = originMessageId;
        this.status = status;
        this.environment = environment;
        this.lastRepublished = lastRepublished;
        this.republishingCount = republishingCount;
    }

}
