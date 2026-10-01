// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.cache.service.MongoSubscriptionSnapshotLoader;
import de.telekom.eni.pandora.horizon.cache.service.SubscriptionCacheReader;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;

@Configuration
public class LocalSubscriptionCacheAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(CuratorFramework.class)
    @ConditionalOnProperty(
        prefix = "horizon.cache.local-subscription-cache.zoo-keeper",
        name = "enabled",
        havingValue = "true")
    public CuratorFramework subscriptionZooKeeperClient(CacheProperties cacheProperties) {
        var zooKeeper = cacheProperties.getLocalSubscriptionCache().getZooKeeper();
        var connectString = zooKeeper.getConnectString();
        if (connectString == null || connectString.isBlank()) {
            throw new IllegalArgumentException("ZooKeeper connect string is required when enabled");
        }
        var sessionTimeoutMs = timeoutMillis(zooKeeper.getSessionTimeout(), "session");
        var connectionTimeoutMs = timeoutMillis(zooKeeper.getConnectionTimeout(), "connection");
        var client = CuratorFrameworkFactory.builder()
            .connectString(connectString)
            .sessionTimeoutMs(sessionTimeoutMs)
            .connectionTimeoutMs(connectionTimeoutMs)
            .retryPolicy(new ExponentialBackoffRetry(1000, 3))
            .ensembleTracker(zooKeeper.isEnsembleTrackerEnabled())
            .build();
        client.start();
        return client;
    }

    private static int timeoutMillis(Duration timeout, String name) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("ZooKeeper " + name + " timeout must be positive");
        }
        try {
            var millis = Math.toIntExact(timeout.toMillis());
            if (millis == 0) {
                throw new IllegalArgumentException("ZooKeeper " + name + " timeout must be at least 1ms");
            }
            return millis;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("ZooKeeper " + name + " timeout exceeds the supported range", exception);
        }
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(name = {
        "horizon.cache.local-subscription-cache.enabled",
        "horizon.cache.local-subscription-cache.zoo-keeper.enabled"
    }, havingValue = "true")
    public ZooKeeperSubscriptionHeadWatcher subscriptionHeadWatcher(CuratorFramework client,
            LocalSubscriptionCache cache, CacheProperties cacheProperties) {
        var zooKeeper = cacheProperties.getLocalSubscriptionCache().getZooKeeper();
        var preparedPath = zooKeeper.getPreparedPath();
        var activatePath = zooKeeper.getActivatePath();
        if (preparedPath == null || !preparedPath.startsWith("/") || preparedPath.isBlank()
                || activatePath == null || !activatePath.startsWith("/") || activatePath.isBlank()
                || preparedPath.equals(activatePath)) {
            throw new IllegalArgumentException("Distinct absolute ZooKeeper prepared and activate paths are required");
        }
        var reader = new ZooKeeperSubscriptionSnapshotHeadReader(
            client, new ObjectMapper(), preparedPath, activatePath);
        return new ZooKeeperSubscriptionHeadWatcher(client, preparedPath, activatePath,
            new ZooKeeperSubscriptionHeadReconciler(reader, cache));
    }

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