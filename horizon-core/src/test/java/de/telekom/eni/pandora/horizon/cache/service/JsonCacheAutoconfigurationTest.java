// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import de.telekom.eni.pandora.horizon.autoconfigure.cache.JsonCacheAutoconfiguration;
import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import de.telekom.eni.pandora.horizon.mongo.config.MongoProperties;
import de.telekom.eni.pandora.horizon.mongo.repository.SubscriptionsMongoRepo;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JsonCacheAutoconfigurationTest {

    @Test
    void shouldUseHazelcastReaderWhenLocalCacheIsDisabled() {
        contextRunner(new CacheProperties()).run(context ->
                assertInstanceOf(HazelcastCacheReader.class, context.getBean(SubscriptionCacheReader.class)));
    }

    @Test
    void shouldUseLocalCacheWithHazelcastFallback() {
        var cacheProperties = new CacheProperties();
        cacheProperties.getLocalSubscriptionCache().setEnabled(true);
        var localCache = mock(LocalSubscriptionCache.class);

        contextRunner(cacheProperties)
                .withBean(LocalSubscriptionCache.class, () -> localCache)
                .run(context -> assertInstanceOf(
                        FallbackSubscriptionCacheReader.class,
                        context.getBean(SubscriptionCacheReader.class)));
    }

    @Test
    void shouldUseLocalCacheExclusivelyWhenFallbackIsDisabled() {
        var cacheProperties = new CacheProperties();
        cacheProperties.getLocalSubscriptionCache().setEnabled(true);
        cacheProperties.getLocalSubscriptionCache().setFallbackMode(
                CacheProperties.LocalSubscriptionCacheFallback.NONE);
        var localCache = mock(LocalSubscriptionCache.class);

        contextRunner(cacheProperties)
                .withBean(LocalSubscriptionCache.class, () -> localCache)
                .run(context -> assertSame(localCache, context.getBean(SubscriptionCacheReader.class)));
    }

    @SuppressWarnings("unchecked")
    private ApplicationContextRunner contextRunner(CacheProperties cacheProperties) {
        var hazelcastInstance = mock(HazelcastInstance.class);
        IMap<String, HazelcastJsonValue> map = mock(IMap.class);
        when(hazelcastInstance.<String, HazelcastJsonValue>getMap(anyString())).thenReturn(map);

        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JsonCacheAutoconfiguration.class))
                .withPropertyValues("horizon.cache.enabled=true")
                .withBean(HazelcastInstance.class, () -> hazelcastInstance)
                .withBean(SubscriptionsMongoRepo.class, () -> mock(SubscriptionsMongoRepo.class))
                .withBean(MongoProperties.class, MongoProperties::new)
                .withBean(CacheProperties.class, () -> cacheProperties);
    }
}