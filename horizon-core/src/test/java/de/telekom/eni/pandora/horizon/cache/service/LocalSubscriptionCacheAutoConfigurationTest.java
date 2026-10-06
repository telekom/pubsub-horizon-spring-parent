// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.autoconfigure.cache.LocalSubscriptionCacheAutoConfiguration;
import de.telekom.eni.pandora.horizon.autoconfigure.cache.LocalSubscriptionCacheHealthIndicator;
import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class LocalSubscriptionCacheAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LocalSubscriptionCacheAutoConfiguration.class))
            .withBean("mongoConfigTemplate", MongoTemplate.class, () -> mock(MongoTemplate.class));

    @Test
    void shouldRequireZooKeeperConnectStringWhenLocalCacheIsEnabled() {
        contextRunner
                .withBean(CacheProperties.class, CacheProperties::new)
                .withPropertyValues("horizon.cache.local-subscription-cache.enabled=true")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .rootCause().hasMessageContaining("ZooKeeper connect string"));
    }

    @Test
    void shouldUseApplicationProvidedSnapshotCache() {
        var customCache = mock(LocalSubscriptionCache.class);

        contextRunner
                .withBean(LocalSubscriptionCache.class, () -> customCache)
                .run(context -> {
                    assertEquals(1, context.getBeansOfType(LocalSubscriptionCache.class).size());
                    assertSame(customCache, context.getBean(LocalSubscriptionCache.class));
                });
    }

    @Test
    void shouldNotCreateInitializerWhenLocalCacheIsDisabled() {
        contextRunner.run(context -> assertEquals(
                0, context.getBeansOfType(LocalSubscriptionCacheHealthIndicator.class).size()));
    }
}