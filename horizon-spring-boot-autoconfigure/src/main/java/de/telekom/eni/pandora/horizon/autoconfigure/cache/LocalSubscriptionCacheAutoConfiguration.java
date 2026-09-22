// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.cache.service.MongoSubscriptionSnapshotLoader;
import de.telekom.eni.pandora.horizon.cache.service.SubscriptionCacheReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

@Configuration
public class LocalSubscriptionCacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LocalSubscriptionCache.class)
    @ConditionalOnProperty(
        prefix = "horizon.cache.local-subscription-cache",
        name = "enabled",
        havingValue = "true")
    public LocalSubscriptionCache localSubscriptionCache(
            @Qualifier("mongoConfigTemplate") ObjectProvider<MongoTemplate> mongoTemplateProvider,
            ObjectProvider<CacheProperties> cachePropertiesProvider) {
        var mongoConfigTemplate = mongoTemplateProvider.getIfAvailable();
        var cacheProperties = cachePropertiesProvider.getIfAvailable(CacheProperties::new);
        var localCacheProperties = cacheProperties.getLocalSubscriptionCache();
        if (mongoConfigTemplate == null) {
            throw new IllegalStateException("MongoTemplate is required for local subscription cache");
        }
        return new LocalSubscriptionCache(new MongoSubscriptionSnapshotLoader(
            mongoConfigTemplate,
            localCacheProperties.getSnapshotCollection(),
            localCacheProperties.getHeadCollection()));
    }

    @Bean
    @ConditionalOnMissingBean(LocalSubscriptionCacheInitializer.class)
    @ConditionalOnProperty(
        prefix = "horizon.cache.local-subscription-cache",
        name = "enabled",
        havingValue = "true")
    public LocalSubscriptionCacheInitializer localSubscriptionCacheInitializer(
        LocalSubscriptionCache localSubscriptionCache,
        ObjectProvider<SubscriptionCacheReader> subscriptionCacheReaderProvider,
        ObjectProvider<CacheProperties> cachePropertiesProvider) {
        return new LocalSubscriptionCacheInitializer(
            localSubscriptionCache,
            subscriptionCacheReaderProvider,
            cachePropertiesProvider.getIfAvailable(CacheProperties::new));
    }
}