package de.telekom.eni.pandora.horizon.autoconfigure.metrics;

import de.telekom.eni.pandora.horizon.metrics.HorizonMetricsHelper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.TracingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@EnableConfigurationProperties({TracingProperties.class})
@Import(value = {
        CompositeMeterRegistryAutoConfiguration.class
})
public class HorizonMetricsHelperAutoConfiguration {

    @ConditionalOnClass(MeterRegistry.class)
    @Bean
    public HorizonMetricsHelper horizonMetricsHelper(MeterRegistry meterRegistry) {
        return new HorizonMetricsHelper(meterRegistry);
    }
}
