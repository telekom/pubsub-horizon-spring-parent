// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.zookeeper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class ZooKeeperPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Configuration
    @EnableConfigurationProperties(ZooKeeperProperties.class)
    static class TestConfig {
    }

    @Test
    @DisplayName("Verify configuration properties binding in Spring context with custom properties")
    void testConfigurationPropertiesBinding() {
        contextRunner
                .withPropertyValues(
                        "zookeeper.enabled=false",
                        "zookeeper.uri=192.168.1.100:2181",
                        "zookeeper.session-timeout-ms=45000",
                        "zookeeper.connection-timeout-ms=8000",
                        "zookeeper.back-off.base-sleep-ms=3000",
                        "zookeeper.back-off.max-retries=7"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ZooKeeperProperties.class);
                    var properties = context.getBean(ZooKeeperProperties.class);

                    assertFalse(properties.isEnabled());
                    assertEquals("192.168.1.100:2181", properties.getUri());
                    assertEquals(45000, properties.getSessionTimeoutMs());
                    assertEquals(8000, properties.getConnectionTimeoutMs());
                    assertNotNull(properties.getBackOff());
                    assertEquals(3000, properties.getBackOff().getBaseSleepMs());
                    assertEquals(7, properties.getBackOff().getMaxRetries());
                });
    }

    @Test
    @DisplayName("Verify configuration properties binding with defaults in Spring context")
    void testConfigurationPropertiesBindingWithDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ZooKeeperProperties.class);
            var properties = context.getBean(ZooKeeperProperties.class);

            assertTrue(properties.isEnabled());
            assertEquals("127.0.0.1:2181,127.0.0.1:2182,127.0.0.1:2183", properties.getUri());
            assertEquals(30000, properties.getSessionTimeoutMs());
            assertEquals(5000, properties.getConnectionTimeoutMs());
        });
    }
}
