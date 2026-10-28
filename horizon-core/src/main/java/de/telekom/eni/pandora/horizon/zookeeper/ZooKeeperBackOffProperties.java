// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.zookeeper;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;

@Getter
@Setter
public class ZooKeeperBackOffProperties {

    @Value("${baseSleepMs:1000}")
    int baseSleepMs;

    @Value("${maxRetries:3}")
    int maxRetries;

}
