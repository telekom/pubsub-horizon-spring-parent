// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.model.meta;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.telekom.eni.pandora.horizon.model.common.Cacheable;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.time.Instant;
import java.util.Date;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class CircuitBreakerMessage extends Cacheable {
    @Serial
    private static final long serialVersionUID = 1000L;

    private String subscriptionId;

    private String subscriberId;

    private Date lastModified;

    private String originMessageId;

    private CircuitBreakerStatus status;

    private Date lastRepublished;

    private int republishingCount;

    public CircuitBreakerMessage(String subscriptionId, String subscriberId, Date lastModified, String originMessageId, CircuitBreakerStatus status, Date lastRepublished, int republishingCount) {
        super(subscriptionId);
        this.subscriptionId = subscriptionId;
        this.subscriberId = subscriberId;
        this.lastModified = lastModified;
        this.originMessageId = originMessageId;
        this.status = status;
        this.lastRepublished = lastRepublished;
        this.republishingCount = republishingCount;
    }

    @Override
    protected String getType() {
        return "circuit-breaker";
    }

    @Override
    public int compareTo(Cacheable cachable) {
        if (cachable instanceof CircuitBreakerMessage circuitBreakerMessage) {
            // -1 -> move to left (beginning), 0 -> keep, 1 -> move to right (end)
            // sorted by timestamp millis, smallest first -> oldest timestamp first
            return getLastModified().compareTo(circuitBreakerMessage.getLastModified());
        }

        return super.compareTo(cachable);
    }
}
