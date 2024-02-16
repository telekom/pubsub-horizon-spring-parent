// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.kubernetes.resource;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Version;

@Version("v1")
@Kind("Subscription")
@Group("subscriber.horizon.telekom.de")
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubscriptionResource extends CustomResource<SubscriptionResourceSpec, SubscriptionResourceStatus> implements Namespaced { }