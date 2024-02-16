// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure;

import de.telekom.eni.pandora.horizon.autoconfigure.cache.CacheAutoConfiguration;
import de.telekom.eni.pandora.horizon.autoconfigure.kafka.KafkaAutoConfiguration;
import de.telekom.eni.pandora.horizon.autoconfigure.metrics.HorizonMetricsHelperAutoConfiguration;
import de.telekom.eni.pandora.horizon.autoconfigure.mongo.MongoAutoConfiguration;
import de.telekom.eni.pandora.horizon.autoconfigure.tracing.HorizonTracerAutoConfiguration;
import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import de.telekom.eni.pandora.horizon.cache.service.DeDuplicationService;
import org.springframework.boot.actuate.autoconfigure.tracing.TracingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@EnableConfigurationProperties({TracingProperties.class, CacheProperties.class})
@Import(value = {
        HorizonMetricsHelperAutoConfiguration.class,
        HorizonTracerAutoConfiguration.class,
        CacheAutoConfiguration.class,
        MongoAutoConfiguration.class,
        KafkaAutoConfiguration.class
})
public class HorizonAutoConfiguration {

    @ConditionalOnMissingBean(DeDuplicationService.class)
    @Bean
    public DeDuplicationService deDuplicationService(CacheProperties cacheProperties) {
        return new DeDuplicationService(null, cacheProperties);
    }
}
