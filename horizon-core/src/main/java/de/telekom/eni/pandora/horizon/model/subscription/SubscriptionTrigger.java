// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.model.subscription;

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

    public enum ResponseFilterMode {
        INCLUDE, EXCLUDE
    }

    private ResponseFilterMode responseFilterMode;

    private List<String> responseFilter;

    private Map<String, String> selectionFilter;

    private Operator advancedSelectionFilter;
}
