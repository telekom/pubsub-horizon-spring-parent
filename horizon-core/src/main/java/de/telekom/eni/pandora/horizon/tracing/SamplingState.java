// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.tracing;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum SamplingState {
    DEBUG("d"),
    SAMPLED("1"),
    NOT_SAMPLED("0");

    public final String value;

    public static SamplingState findByValue(String value) {
        SamplingState result = null;
        for (SamplingState samplingState : values()) {
            if (samplingState.getValue().equalsIgnoreCase(value)) {
                result = samplingState;
                break;
            }
        }
        return result;
    }
}
