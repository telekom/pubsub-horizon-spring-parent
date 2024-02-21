// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.kubernetes.resource;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.telekom.jsonfilter.operator.Operator;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubscriptionTrigger {

    enum Type {
        INCLUDE, EXCLUDE
    }

    private Type responseFilterMode;

    private List<String> responseFilter;

    private Map<String, String> selectionFilter;

    private Operator advancedSelectionFilter;
}
