// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.tracing;

import brave.Tracing;
import brave.TracingCustomizer;
import de.telekom.eni.pandora.horizon.tracing.PandoraTracer;
import de.telekom.eni.pandora.horizon.tracing.TracingProperties;
import de.telekom.eni.pandora.horizon.tracing.HorizonTracer;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.tracing.BraveAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration
@EnableConfigurationProperties({org.springframework.boot.actuate.autoconfigure.tracing.TracingProperties.class, TracingProperties.class})
@Import(value = {
        BraveAutoConfiguration.class,
})
public class HorizonTracerAutoConfiguration {

    @ConditionalOnClass(KafkaTemplate.class)
    @Bean
    public HorizonTracer pandoraHorizonTracer(TracingProperties tracingProperties, Environment environment, Tracing tracing, brave.Tracer tracer) {
        return new HorizonTracer(environment, tracing, tracer, tracingProperties);
    }

    @Bean
    @ConditionalOnProperty(value = "pandora.tracing.name")
    TracingCustomizer tracingServiceNameCustomizer(@Value("${pandora.tracing.name:unsetLocalServiceName}") String tracingName) {
        return builder -> builder.localServiceName(tracingName);
    }

    @Bean
    public Filter traceInformationResponseFilter(PandoraTracer tracer) {
        return tracer.createTraceInformationResponseFilter();
    }
}
