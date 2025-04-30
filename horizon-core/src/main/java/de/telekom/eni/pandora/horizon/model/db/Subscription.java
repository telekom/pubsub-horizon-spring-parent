// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.model.db;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.telekom.eni.pandora.horizon.model.event.DeliveryType;
import de.telekom.eni.pandora.horizon.model.event.EventMessage;
import de.telekom.eni.pandora.horizon.model.event.Status;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import de.telekom.eni.pandora.horizon.model.meta.EventRetentionTime;
import de.telekom.jsonfilter.operator.EvaluationResult;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.*;

import static java.util.Optional.ofNullable;

@Getter
@Setter
@ToString
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Subscription implements Serializable {
    private String uuid;
    private String subscriptionId;
    private String deliveryType;
    private String publisherId;
    private String subscriberId;
    private String type;
    private String callback;

}
