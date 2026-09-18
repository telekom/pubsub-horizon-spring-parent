// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.autoconfigure.cache.LocalSubscriptionCacheAutoConfiguration;
import de.telekom.eni.pandora.horizon.autoconfigure.cache.LocalSubscriptionCacheInitializer;
import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import de.telekom.eni.pandora.horizon.mongo.repository.SubscriptionsMongoRepo;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class LocalSubscriptionCacheAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LocalSubscriptionCacheAutoConfiguration.class))
            .withBean("getSubscriptionsRepo", SubscriptionsMongoRepo.class, () -> mock(SubscriptionsMongoRepo.class));

    @Test
    void shouldCreateSnapshotCacheAlongsideExistingJsonCache() {
        contextRunner
                .withBean("subscriptionCache", JsonCacheService.class, () -> mock(JsonCacheService.class))
                .run(context -> {
                    assertEquals(1, context.getBeansOfType(LocalSubscriptionCache.class).size());
                    assertEquals(1, context.getBeansOfType(JsonCacheService.class).size());
                });
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
    void shouldCreateInitializerWhenLocalCacheIsEnabled() {
        contextRunner
                .withBean(CacheProperties.class, CacheProperties::new)
                .withPropertyValues("horizon.cache.local-subscription-cache.enabled=true")
                .run(context -> assertEquals(
                        1, context.getBeansOfType(LocalSubscriptionCacheInitializer.class).size()));
    }

    @Test
    void shouldNotCreateInitializerWhenLocalCacheIsDisabled() {
        contextRunner.run(context -> assertEquals(
                0, context.getBeansOfType(LocalSubscriptionCacheInitializer.class).size()));
    }
}