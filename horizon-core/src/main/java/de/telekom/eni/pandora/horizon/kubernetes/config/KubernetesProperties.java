// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.kubernetes.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("horizon.kubernetes")
public class KubernetesProperties {

    @Value("${rover.token:}")
    private String roverToken;

    @Value("${kubeConfigPath:}")
    private String kubeConfigPath;

    @Value("${requestTimeoutMs:120000}")
    private int requestTimeoutInMs;

    @Value("${connectionTimeoutMs:120000}")
    private int connectionTimeoutMs;
}
