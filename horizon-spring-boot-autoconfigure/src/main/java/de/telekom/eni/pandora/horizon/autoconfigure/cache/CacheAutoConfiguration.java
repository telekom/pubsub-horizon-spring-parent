// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import com.hazelcast.client.config.ConnectionRetryConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import de.telekom.eni.pandora.horizon.cache.service.CacheService;
import de.telekom.eni.pandora.horizon.cache.service.DeDuplicationService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.hazelcast.HazelcastAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.UUID;

@Slf4j
@Configuration
@ConditionalOnProperty(value = "horizon.cache.enabled")
@Import({HazelcastAutoConfiguration.class})
@EnableConfigurationProperties({CacheProperties.class})
public class CacheAutoConfiguration {

    private static final String DEFAULT_HAZELCAST_CLUSTER_NAME = "dev";
    private HazelcastInstance hazelcastInstance;

    @Value("${POD_NAME:horizon}")
    private String podName;

    @PreDestroy()
    public void shutdown() {
        log.info("Shutdown hazelcast client");
        HazelcastClient.shutdown(hazelcastInstance);
    }

    @Primary
    @Bean
    public HazelcastInstance hazelcastInstance(CacheProperties cacheProperties) {
        log.debug("Initializing new hazelcast client");

        ClientConfig config = new ClientConfig();

        // Set cluster name, network configuration and instance name
        config.setClusterName(DEFAULT_HAZELCAST_CLUSTER_NAME);

        if (cacheProperties != null && StringUtils.isNotBlank(cacheProperties.getKubernetesServiceDns())) {
            config.getNetworkConfig().addAddress(cacheProperties.getKubernetesServiceDns());
        } else {
            config.getNetworkConfig().addAddress("localhost:5701");
        }

        if ("horizon".equals(podName)) {
            podName = "horizon-" + UUID.randomUUID().toString();
        }
        config.setInstanceName(podName);

        // Set connection timeout
        config.getNetworkConfig().setConnectionTimeout(5000);  // default 5000ms

        // Set retry configuration
        ConnectionRetryConfig retryConfig = config.getConnectionStrategyConfig().getConnectionRetryConfig();
        retryConfig.setInitialBackoffMillis(1000)   // default 1000ms
                .setMaxBackoffMillis(1000)  // no increasing backoff, default 30000ms
                .setMultiplier(1.0) // no increasing backoff, default 1.05
                .setClusterConnectTimeoutMillis(-1); // Retry indefinitely, default -1

        hazelcastInstance = HazelcastClient.newHazelcastClient(config);

        return hazelcastInstance;
    }

    @Bean
    public CacheService cacheService(HazelcastInstance hazelcastInstance, CacheProperties cacheProperties) {
        return new CacheService(hazelcastInstance, cacheProperties);
    }

    @Bean
    public DeDuplicationService deDuplicationService(HazelcastInstance hazelcastInstance, CacheProperties cacheProperties) {
        return new DeDuplicationService(hazelcastInstance, cacheProperties);
    }
}