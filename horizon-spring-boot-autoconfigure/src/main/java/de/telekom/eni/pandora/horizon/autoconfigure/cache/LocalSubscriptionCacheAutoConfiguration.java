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

/**
 * Configures the pod-local subscription cache, its head source, and related health, metrics, and startup beans.
 *
 * <p>The local cache is created only when {@code horizon.cache.local-subscription-cache.enabled=true}.</p>
 */
@Slf4j
@Configuration
public class LocalSubscriptionCacheAutoConfiguration {

    /** Creates the local subscription cache auto-configuration. */
    public LocalSubscriptionCacheAutoConfiguration() {
    }

    /** Configures the Curator client and watcher when ZooKeeper is the selected head source. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
        prefix = "horizon.cache.local-subscription-cache.zoo-keeper",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
    static class ZooKeeperHeadSourceConfiguration {

        /**
         * Creates and starts the Curator client for the local subscription cache.
         *
         * @param cacheProperties local cache and ZooKeeper connection settings
         * @return started Curator client, closed by the Spring context
         * @throws IllegalArgumentException if the connect string or configured timeouts are invalid
         */
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

        /**
         * Registers a gauge reporting whether the ZooKeeper head source is connected.
         *
         * @param clientProvider provider for the configured Curator client
         * @return meter binder for the ZooKeeper connection gauge
         */
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

        /**
         * Creates the ZooKeeper head reader, reconciler, and lifecycle-managed watcher.
         *
         * @param client Curator client for the configured ensemble
         * @param cache pod-local subscription cache
         * @param cacheProperties paths, fallback policy, and reconciliation timing settings
         * @param meterRegistryProvider optional registry for ZooKeeper head-read failure metrics
         * @return watcher started and closed by the Spring context
         * @throws IllegalArgumentException if the PREPARED and ACTIVATE paths are not distinct absolute paths
         */
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
                reader, cache, localCacheProperties.getMongoHeadFallbackMode());
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

    /** Configures MongoDB-head polling when ZooKeeper is disabled. */
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

    /**
     * Creates the startup barrier for configurations that require a fresh local snapshot.
     *
     * @param cache local cache whose first FRESH snapshot completes the barrier
     * @param cacheProperties startup requirement and timeout settings
     * @return application runner that waits for the first fresh snapshot when required
     * @throws IllegalArgumentException if the configured timeout is negative or sub-millisecond
     */
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

    /**
     * Creates the MongoDB-backed local cache when no custom cache bean is provided.
     *
     * @param mongoTemplateProvider provider for the qualified MongoDB configuration template
     * @param cachePropertiesProvider provider for local cache collection and timeout settings
     * @return configured local cache
     * @throws IllegalStateException if the required MongoDB template is unavailable
     */
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
            localCacheProperties.getHeadCollection(),
            localCacheProperties.getMongoLoadTimeout()), staleLocalCacheReadGracePeriod);
    }

    /**
     * Creates a diagnostic health indicator for the local cache and effective reader.
     *
     * @param localSubscriptionCache local cache state to report
     * @param subscriptionCacheReaderProvider provider for the effective reader, including fallback
     * @param cachePropertiesProvider provider for the configured fallback/read policy
     * @return local cache health indicator
     */
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

    /**
     * Creates the local cache metrics binder when no custom metrics bean is provided.
     *
     * @param localSubscriptionCache cache state and counters to expose
     * @return local cache metrics binder
     */
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