// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import de.telekom.eni.pandora.horizon.cache.fallback.SubscriptionCacheMongoFallback;
import de.telekom.eni.pandora.horizon.cache.listener.SubscriptionResourceEventBroadcaster;
import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import de.telekom.eni.pandora.horizon.cache.service.JsonCacheService;
import de.telekom.eni.pandora.horizon.cache.service.FallbackSubscriptionCacheReader;
import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.cache.service.SharedSubscriptionCacheReader;
import de.telekom.eni.pandora.horizon.cache.service.SubscriptionCacheReader;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.model.meta.CircuitBreakerMessage;
import de.telekom.eni.pandora.horizon.mongo.config.MongoProperties;
import de.telekom.eni.pandora.horizon.mongo.repository.SubscriptionsMongoRepo;
import de.telekom.jsonfilter.operator.Operator;
import de.telekom.jsonfilter.serde.OperatorDeserializer;
import de.telekom.jsonfilter.serde.OperatorSerializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Slf4j
@Configuration
@ConditionalOnProperty(value = "horizon.cache.enabled")
public class JsonCacheAutoconfiguration {

    private static final String SUBSCRIPTION_RESOURCE_V1 = "subscriptions.subscriber.horizon.telekom.de.v1";

    private static final String CIRCUITBREAKER_MAP = "circuit-breakers";

    @Bean
    public JsonCacheService<SubscriptionResource> subscriptionCache(HazelcastInstance hazelcastInstance, ApplicationEventPublisher applicationEventPublisher, SubscriptionsMongoRepo subscriptionsMongoRepo, MongoProperties mongoProperties) {
        var module = new SimpleModule();
        module.addSerializer(Operator.class, new OperatorSerializer());
        module.addDeserializer(Operator.class, new OperatorDeserializer());

        var mapper = new ObjectMapper();
        mapper.registerModule(module);

        IMap<String, HazelcastJsonValue> map = null;

        try {
            map = hazelcastInstance.getMap(SUBSCRIPTION_RESOURCE_V1);
            map.addEntryListener(new SubscriptionResourceEventBroadcaster(mapper, applicationEventPublisher), true);
        }
        catch (Exception e) {
            log.error("Hazelcast map {} is not available", SUBSCRIPTION_RESOURCE_V1);
        }

        var svc = new JsonCacheService<>(SubscriptionResource.class, map, mapper, hazelcastInstance,SUBSCRIPTION_RESOURCE_V1);
        svc.setJsonCacheFallback(new SubscriptionCacheMongoFallback(subscriptionsMongoRepo, mongoProperties));
        svc.setJsonEntryMapEventBroadcaster(new SubscriptionResourceEventBroadcaster(mapper, applicationEventPublisher));
        return svc;
    }

    @Bean(name = "subscriptionCacheReader")
    @Primary
    @ConditionalOnProperty(value = "horizon.cache.enabled")
    public SubscriptionCacheReader subscriptionCacheWithLocalPrimary(
            ObjectProvider<LocalSubscriptionCache> localSubscriptionCacheProvider,
            @org.springframework.beans.factory.annotation.Qualifier("subscriptionCache") JsonCacheService<SubscriptionResource> subscriptionCache,
            CacheProperties cacheProperties) {
        var localSubscriptionCache = localSubscriptionCacheProvider.getIfAvailable();
        var localCacheProperties = cacheProperties.getLocalSubscriptionCache();
        var sharedSubscriptionCacheReader = new SharedSubscriptionCacheReader(subscriptionCache);
        if (!localCacheProperties.isEnabled()) {
            return sharedSubscriptionCacheReader;
        }
        if (localCacheProperties.getFallbackMode() == CacheProperties.LocalSubscriptionCacheFallback.NONE) {
            if (localSubscriptionCache == null) {
                throw new IllegalStateException("LocalSubscriptionCache is required when horizon.cache.local-subscription-cache is true");
            }
            return localSubscriptionCache;
        }
        if (localSubscriptionCache == null) {
            return sharedSubscriptionCacheReader;
        }
        return new FallbackSubscriptionCacheReader(localSubscriptionCache, sharedSubscriptionCacheReader);
    }

    @Bean
    public JsonCacheService<CircuitBreakerMessage> circuitBreakerCache(HazelcastInstance hazelcastInstance) {
        IMap<String, HazelcastJsonValue> map = null;

        try {
            map = hazelcastInstance.getMap(CIRCUITBREAKER_MAP);
        }
        catch (Exception e) {
            log.error("Hazelcast map {} is not available", CIRCUITBREAKER_MAP);
        }

        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        return new JsonCacheService<>(CircuitBreakerMessage.class, map, mapper, hazelcastInstance, CIRCUITBREAKER_MAP);
    }

}
