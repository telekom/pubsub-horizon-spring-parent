// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.cache.service.MongoSubscriptionSnapshotLoader;
import de.telekom.eni.pandora.horizon.cache.service.SubscriptionCacheReader;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Configuration
public class LocalSubscriptionCacheAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
        prefix = "horizon.cache.local-subscription-cache.zoo-keeper",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
    static class ZooKeeperHeadSourceConfiguration {

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(CuratorFramework.class)
        @ConditionalOnProperty(
            prefix = "horizon.cache.local-subscription-cache",
            name = "enabled",
            havingValue = "true")
        public CuratorFramework subscriptionZooKeeperClient(CacheProperties cacheProperties) {
            var zooKeeper = cacheProperties.getLocalSubscriptionCache().getZooKeeper();
            var connectString = zooKeeper.getConnectString();
            if (connectString == null || connectString.isBlank()) {
                throw new IllegalArgumentException(
                    "ZooKeeper connect string is required for the local subscription cache");
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

        @Bean
        @ConditionalOnProperty(
            prefix = "horizon.cache.local-subscription-cache",
            name = "enabled",
            havingValue = "true")
        public MeterBinder localSubscriptionCacheZooKeeperMetrics(ObjectProvider<CuratorFramework> clientProvider) {
            return registry -> clientProvider.ifAvailable(client -> Gauge.builder(
                    LocalSubscriptionCacheMetrics.PREFIX + ".zookeeper.connected", client,
                    c -> c.getZookeeperClient().isConnected() ? 1 : 0)
                .description("1 if the ZooKeeper head source is connected, otherwise 0")
                .strongReference(true)
                .register(registry));
        }

        @Bean(initMethod = "start", destroyMethod = "close")
        @ConditionalOnProperty(
            prefix = "horizon.cache.local-subscription-cache",
            name = "enabled",
            havingValue = "true")
        public ZooKeeperSubscriptionHeadWatcher subscriptionHeadWatcher(CuratorFramework client,
                LocalSubscriptionCache cache, CacheProperties cacheProperties,
                ObjectProvider<MeterRegistry> meterRegistryProvider) {
            var localCacheProperties = cacheProperties.getLocalSubscriptionCache();
            var zooKeeper = localCacheProperties.getZooKeeper();
            var preparedPath = zooKeeper.getPreparedPath();
            var activatePath = zooKeeper.getActivatePath();
            if (preparedPath == null || !preparedPath.startsWith("/") || preparedPath.isBlank()
                    || activatePath == null || !activatePath.startsWith("/") || activatePath.isBlank()
                    || preparedPath.equals(activatePath)) {
                throw new IllegalArgumentException(
                    "Distinct absolute ZooKeeper prepared and activate paths are required");
            }
            var reader = new ZooKeeperSubscriptionSnapshotHeadReader(
                client, new ObjectMapper(), preparedPath, activatePath);
            var reconciler = new ZooKeeperSubscriptionHeadReconciler(
                reader, cache, localCacheProperties.isMongoHeadFallbackEnabled());
            meterRegistryProvider.ifAvailable(registry -> FunctionCounter.builder(
                    LocalSubscriptionCacheMetrics.FAILURES, reconciler,
                    ZooKeeperSubscriptionHeadReconciler::headReadFailureCount)
                .description(LocalSubscriptionCacheMetrics.FAILURES_DESCRIPTION)
                .tag("reason", "zookeeper_head_read")
                .register(registry));
            return new ZooKeeperSubscriptionHeadWatcher(client, preparedPath, activatePath, reconciler,
                localCacheProperties.getReconcileInterval(), localCacheProperties.getMongoSnapshotSyncJitter(),
                localCacheProperties.getMongoHeadPollJitter());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
        prefix = "horizon.cache.local-subscription-cache.zoo-keeper",
        name = "enabled",
        havingValue = "false")
    static class MongoHeadSourceConfiguration {

        @Bean(initMethod = "start", destroyMethod = "close")
        @ConditionalOnProperty(
            prefix = "horizon.cache.local-subscription-cache",
            name = "enabled",
            havingValue = "true")
        public MongoSubscriptionHeadPoller subscriptionHeadPoller(LocalSubscriptionCache cache,
                CacheProperties cacheProperties) {
            var localCacheProperties = cacheProperties.getLocalSubscriptionCache();
            return new MongoSubscriptionHeadPoller(new MongoSubscriptionHeadReconciler(cache),
                localCacheProperties.getReconcileInterval(), localCacheProperties.getMongoHeadPollJitter());
        }
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

    @Bean
    @ConditionalOnProperty(
        prefix = "horizon.cache.local-subscription-cache",
        name = "enabled",
        havingValue = "true")
    public ApplicationRunner localSubscriptionCacheStartupBarrier(LocalSubscriptionCache cache,
            CacheProperties cacheProperties) {
        var localCacheProperties = cacheProperties.getLocalSubscriptionCache();
        var timeout = localCacheProperties.getInitialSnapshotTimeout();
        if (timeout == null || timeout.isNegative() || (!timeout.isZero() && timeout.toMillis() == 0)) {
            throw new IllegalArgumentException(
                "Initial subscription snapshot timeout must be zero or at least 1ms");
        }
        var localCacheRequired = localCacheProperties.getFallbackMode() == CacheProperties.LocalSubscriptionCacheFallback.NONE
            || localCacheProperties.isRequireLocalCacheAtStartup();
        return args -> {
            if (!localCacheRequired) {
                log.info("Local subscription cache is not required at startup; startup continues without waiting");
                return;
            }
            var waitStart = System.nanoTime();
            log.info("Waiting for initial local subscription snapshot (timeout {})",
                timeout.isZero() ? "none" : timeout);
            try {
                var firstFreshSnapshot = cache.firstFreshSnapshot().toCompletableFuture();
                if (timeout.isZero()) {
                    firstFreshSnapshot.get();
                } else {
                    firstFreshSnapshot.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                }
                log.info("Initial local subscription snapshot {} fresh after {} ms",
                    cache.localSnapshotId().orElse("none"),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStart));
            } catch (TimeoutException exception) {
                throw new IllegalStateException("Initial subscription snapshot did not become fresh within " + timeout,
                    exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for initial subscription snapshot", exception);
            }
        };
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
        // Without a shared fallback, a stale local snapshot remains the only source and must stay readable.
        var staleLocalCacheReadGracePeriod = localCacheProperties.getFallbackMode() == CacheProperties.LocalSubscriptionCacheFallback.NONE
            ? ChronoUnit.FOREVER.getDuration()
            : localCacheProperties.getStaleLocalCacheReadGracePeriod();
        return new LocalSubscriptionCache(new MongoSubscriptionSnapshotLoader(
            mongoConfigTemplate,
            localCacheProperties.getSnapshotCollection(),
            localCacheProperties.getHeadCollection()), staleLocalCacheReadGracePeriod);
    }

    @Bean
    @ConditionalOnMissingBean(LocalSubscriptionCacheHealthIndicator.class)
    @ConditionalOnProperty(
        prefix = "horizon.cache.local-subscription-cache",
        name = "enabled",
        havingValue = "true")
    public LocalSubscriptionCacheHealthIndicator localSubscriptionCacheHealthIndicator(
        LocalSubscriptionCache localSubscriptionCache,
        ObjectProvider<SubscriptionCacheReader> subscriptionCacheReaderProvider,
        ObjectProvider<CacheProperties> cachePropertiesProvider) {
        return new LocalSubscriptionCacheHealthIndicator(localSubscriptionCache, subscriptionCacheReaderProvider,
            cachePropertiesProvider.getIfAvailable(CacheProperties::new).getLocalSubscriptionCache());
    }

    @Bean
    @ConditionalOnMissingBean(LocalSubscriptionCacheMetrics.class)
    @ConditionalOnProperty(
        prefix = "horizon.cache.local-subscription-cache",
        name = "enabled",
        havingValue = "true")
    public LocalSubscriptionCacheMetrics localSubscriptionCacheMetrics(LocalSubscriptionCache localSubscriptionCache) {
        return new LocalSubscriptionCacheMetrics(localSubscriptionCache);
    }
}