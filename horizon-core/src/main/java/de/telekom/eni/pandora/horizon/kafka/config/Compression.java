// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.kafka.config;

import lombok.Data;

@Data
public class Compression {

    private boolean enabled = false;

    private String type = "none";

}
