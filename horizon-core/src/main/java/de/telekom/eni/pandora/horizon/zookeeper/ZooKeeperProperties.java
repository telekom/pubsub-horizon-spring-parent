// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.zookeeper;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Getter
@Setter
@Configuration
@ConfigurationProperties("zookeeper")
public class ZooKeeperProperties {

    @Value("${enabled:true}")
    boolean enabled;

    @Value("${uri:127.0.0.1:2181,127.0.0.1:2182,127.0.0.1:2183}")
    String uri;

    @Value("${sessionTimeoutMs:30000}")
    int sessionTimeoutMs;

    @Value("${connectionTimeoutMs:5000}")
    int connectionTimeoutMs;

    ZooKeeperBackOffProperties backOff;

}
