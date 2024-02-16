// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.spi.properties.ClusterProperty;
import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import de.telekom.eni.pandora.horizon.cache.service.CacheService;
import de.telekom.eni.pandora.horizon.cache.service.DeDuplicationService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.hazelcast.HazelcastAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Slf4j
@Configuration
@ConditionalOnProperty(value = "horizon.cache.enabled")
@Import({HazelcastAutoConfiguration.class})
@EnableConfigurationProperties({CacheProperties.class})
public class CacheAutoConfiguration {

    private static final String DEFAULT_HAZELCAST_INSTANCE_NAME = "horizon";

    @PreDestroy()
    public void shutdown() {
        log.info("Shutdown all hazelcast instances");
        Hazelcast.shutdownAll(); // Because we set SHUTDOWNHOOK_ENABLED to false, we need to explicitly call shutdown
    }

    @Bean
    public HazelcastInstance hazelcastInstance(CacheProperties cacheProperties) {
        log.debug("Initialized new hazelcast instance");
        var config = new Config();

        config.setProperty(ClusterProperty.SHUTDOWNHOOK_ENABLED.getName(), "false");
        config.setProperty(ClusterProperty.SHUTDOWNHOOK_POLICY.getName(), "GRACEFUL");
        config.setInstanceName(DEFAULT_HAZELCAST_INSTANCE_NAME);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);

        if(cacheProperties != null && StringUtils.isNotBlank(cacheProperties.getKubernetesServiceDns()) ) {
            var kubernetesConfig = config.getNetworkConfig().getJoin().getKubernetesConfig().setEnabled(true);

            kubernetesConfig.setProperty("service-dns", cacheProperties.getKubernetesServiceDns());
            kubernetesConfig.setProperty("service-port", "5701");
        } else {
            config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true).addMember("localhost");
        }

        return Hazelcast.getOrCreateHazelcastInstance(config);
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
