// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure;

import com.fasterxml.jackson.databind.module.SimpleModule;
import de.telekom.eni.pandora.horizon.exception.CouldNotConstructKubernetesClientException;
import de.telekom.eni.pandora.horizon.kubernetes.KubernetesClientBuilder;
import de.telekom.eni.pandora.horizon.kubernetes.config.KubernetesProperties;
import de.telekom.jsonfilter.operator.Operator;
import de.telekom.jsonfilter.serde.OperatorDeserializer;
import de.telekom.jsonfilter.serde.OperatorSerializer;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(value = "horizon.kubernetes.enabled")
@EnableConfigurationProperties({KubernetesProperties.class})
@Slf4j
public class KubernetesClientConfiguration {

    @Bean
    public KubernetesClient kubernetesClient(KubernetesProperties kubernetesProperties) throws CouldNotConstructKubernetesClientException {
        var builder = new KubernetesClientBuilder(kubernetesProperties);

        KubernetesClient client;

        var roverToken = kubernetesProperties.getRoverToken();
        var kubeConfigPath = kubernetesProperties.getKubeConfigPath();

        if (!StringUtils.isBlank(roverToken)) {
            client = builder.createClientFromRoverToken(roverToken);
            log.info("Using configured rover token for configuring the Kubernetes client");
        } else if (!StringUtils.isBlank(kubeConfigPath)) {
            client = builder.createClientFromeKubeconfigFile(kubeConfigPath);
            log.info("Using configured Kubernetes config file ({}) for configuring the Kubernetes client", kubeConfigPath);
        } else {
            client = builder.createDefaultClient();
            log.info("Using default Kubernetes config");
        }

        log.info("Using cluster {}", client.getConfiguration().getMasterUrl());

        // used for serialize/deserialize filter operators in subscriptions
        SimpleModule operatorModule = new SimpleModule();
        operatorModule.addDeserializer(Operator.class, new OperatorDeserializer());
        operatorModule.addSerializer(Operator.class, new OperatorSerializer());
        Serialization.jsonMapper().registerModule(operatorModule);

        return client;
    }
}