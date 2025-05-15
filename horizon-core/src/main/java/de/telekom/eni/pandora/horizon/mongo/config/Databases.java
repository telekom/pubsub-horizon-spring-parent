// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0
package de.telekom.eni.pandora.horizon.mongo.config;

import lombok.Data;

@Data
public class Databases {

    private String runTimeDatabase = "horizon";
    private String configTimeDatabase = "horizon-config";

}