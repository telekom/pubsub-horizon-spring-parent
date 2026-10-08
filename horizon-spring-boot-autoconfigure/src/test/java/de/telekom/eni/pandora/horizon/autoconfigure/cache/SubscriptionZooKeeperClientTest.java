package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.retry.RetryNTimes;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriptionZooKeeperClientTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestProperties.class)
        .withConfiguration(AutoConfigurations.of(LocalSubscriptionCacheAutoConfiguration.class));

    private final ApplicationContextRunner enabledRunner = contextRunner
        .withBean(LocalSubscriptionCache.class, () -> mock(LocalSubscriptionCache.class))
        .withPropertyValues(
            "horizon.cache.local-subscription-cache.enabled=true",
            "horizon.cache.local-subscription-cache.zoo-keeper.prepared-path=/subscriptions/prepared",
            "horizon.cache.local-subscription-cache.zoo-keeper.activate-path=/subscriptions/activated");

    @Test
    void clientIsDisabledByDefault() {
        contextRunner.run(context -> {
            assertFalse(context.containsBean("subscriptionZooKeeperClient"));
            assertFalse(context.containsBean("subscriptionHeadWatcher"));
        });
    }

    @Test
    void enabledLocalCacheStartsWatcherAndHealthIndicator() throws Exception {
        try (var server = new TestingServer();
             var setup = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            setup.start();
            setup.create().creatingParentsIfNeeded().forPath("/subscriptions/activate", head("active"));
            var cache = mock(LocalSubscriptionCache.class);
            contextRunner
                .withBean(LocalSubscriptionCache.class, () -> cache)
                .withPropertyValues(
                    "horizon.cache.local-subscription-cache.enabled=true",
                    "horizon.cache.local-subscription-cache.zoo-keeper.connect-string=" + server.getConnectString(),
                    "horizon.cache.local-subscription-cache.zoo-keeper.prepared-path=/subscriptions/prepared",
                    "horizon.cache.local-subscription-cache.zoo-keeper.activate-path=/subscriptions/activate")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LocalSubscriptionCacheHealthIndicator.class);
                    context.getBean(ZooKeeperSubscriptionHeadWatcher.class)
                        .initialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);
                    verify(cache, org.mockito.Mockito.never()).readSnapshotHead();
                });
        }
    }

    @Test
    void enabledWatcherRequiresDistinctPaths() {
        contextRunner
            .withBean(LocalSubscriptionCache.class, () -> mock(LocalSubscriptionCache.class))
            .withBean(CuratorFramework.class, () -> mock(CuratorFramework.class))
            .withPropertyValues(
                "horizon.cache.local-subscription-cache.enabled=true",
                "horizon.cache.local-subscription-cache.zoo-keeper.prepared-path=/subscriptions/head",
                "horizon.cache.local-subscription-cache.zoo-keeper.activate-path=/subscriptions/head")
            .run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void enabledWatcherRequiresBothPaths() {
        contextRunner
            .withBean(LocalSubscriptionCache.class, () -> mock(LocalSubscriptionCache.class))
            .withBean(CuratorFramework.class, () -> mock(CuratorFramework.class))
            .withPropertyValues(
                "horizon.cache.local-subscription-cache.enabled=true",
                "horizon.cache.local-subscription-cache.zoo-keeper.prepared-path=/subscriptions/prepared")
            .run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void propertiesBindIndependently() {
        contextRunner
            .withPropertyValues(
                "horizon.cache.local-subscription-cache.stale-local-cache-read-grace-period=30s",
                "horizon.cache.local-subscription-cache.initial-snapshot-timeout=45s",
                "horizon.cache.local-subscription-cache.mongo-head-fallback-enabled=false",
                "horizon.cache.local-subscription-cache.mongo-head-poll-jitter=3s",
                "horizon.cache.local-subscription-cache.mongo-snapshot-sync-jitter=12s",
                "horizon.cache.local-subscription-cache.zoo-keeper.prepared-path=/horizon/subscriptions/prepared",
                "horizon.cache.local-subscription-cache.zoo-keeper.activate-path=/horizon/subscriptions/activated",
                "horizon.cache.local-subscription-cache.reconcile-interval=7m")
            .run(context -> {
                var localCache = context.getBean(CacheProperties.class).getLocalSubscriptionCache();
                assertThat(localCache.getStaleLocalCacheReadGracePeriod()).isEqualTo(Duration.ofSeconds(30));
                assertThat(localCache.getInitialSnapshotTimeout()).isEqualTo(Duration.ofSeconds(45));
                assertThat(localCache.isMongoHeadFallbackEnabled()).isFalse();
                assertThat(localCache.getMongoHeadPollJitter()).isEqualTo(Duration.ofSeconds(3));
                assertThat(localCache.getMongoSnapshotSyncJitter()).isEqualTo(Duration.ofSeconds(12));
                assertThat(localCache.getReconcileInterval()).isEqualTo(Duration.ofMinutes(7));
                var properties = localCache.getZooKeeper();
                assertThat(properties.getPreparedPath()).isEqualTo("/horizon/subscriptions/prepared");
                assertThat(properties.getActivatePath()).isEqualTo("/horizon/subscriptions/activated");
            });
    }

    @Test
    void localCacheDefaults() {
        contextRunner.run(context -> {
            var localCache = context.getBean(CacheProperties.class).getLocalSubscriptionCache();
            assertThat(localCache.getStaleLocalCacheReadGracePeriod()).isEqualTo(Duration.ofSeconds(120));
            assertThat(localCache.isMongoHeadFallbackEnabled()).isTrue();
            assertThat(localCache.getMongoHeadPollJitter()).isEqualTo(Duration.ofSeconds(10));
            assertThat(localCache.getMongoSnapshotSyncJitter()).isEqualTo(Duration.ofSeconds(10));
            assertThat(localCache.getZooKeeper().isEnabled()).isTrue();
        });
    }

    @Test
    void reconcileIntervalDefaultsTo60Seconds() {
        contextRunner.run(context -> assertThat(context.getBean(CacheProperties.class)
            .getLocalSubscriptionCache().getReconcileInterval())
            .isEqualTo(Duration.ofSeconds(60)));
    }

    @Test
    void disabledZooKeeperUsesMongoHeadPollerWithoutCurator() {
        contextRunner
            .withBean(LocalSubscriptionCache.class, () -> mock(LocalSubscriptionCache.class))
            .withPropertyValues(
                "horizon.cache.local-subscription-cache.enabled=true",
                "horizon.cache.local-subscription-cache.zoo-keeper.enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(CuratorFramework.class);
                assertThat(context).doesNotHaveBean(ZooKeeperSubscriptionHeadWatcher.class);
                assertThat(context).hasSingleBean(MongoSubscriptionHeadPoller.class);
                assertThat(context).hasSingleBean(LocalSubscriptionCacheHealthIndicator.class);
            });
    }

    @Test
    void enabledZooKeeperDoesNotStartMongoHeadPoller() throws Exception {
        try (var server = new TestingServer()) {
            enabledRunner
                .withPropertyValues(
                    "horizon.cache.local-subscription-cache.zoo-keeper.connect-string=" + server.getConnectString())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ZooKeeperSubscriptionHeadWatcher.class);
                    assertThat(context).doesNotHaveBean(MongoSubscriptionHeadPoller.class);
                });
        }
    }

    @Test
    void mongoHeadPollerRunsImmediatelyAndOffsetsOnlyFirstPeriodicPoll() {
        var reconciler = mock(MongoSubscriptionHeadReconciler.class);
        var periodicTask = new AtomicReference<Runnable>();
        var scheduler = manualScheduler(periodicTask);
        var initialDelay = org.mockito.ArgumentCaptor.forClass(Long.class);
        var period = org.mockito.ArgumentCaptor.forClass(Long.class);

        try (var poller = new MongoSubscriptionHeadPoller(reconciler, Duration.ofSeconds(60),
                Duration.ofSeconds(10), scheduler)) {
            poller.start();

            verify(reconciler).reconcile();
            verify(scheduler).scheduleWithFixedDelay(org.mockito.ArgumentMatchers.any(Runnable.class),
                initialDelay.capture(), period.capture(), org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS));
            assertThat(initialDelay.getValue()).isBetween(60_000L, 70_000L);
            assertThat(period.getValue()).isEqualTo(60_000L);
            periodicTask.get().run();
            verify(reconciler, times(2)).reconcile();
        }
        verify(scheduler).shutdownNow();
    }

    @Test
    void zeroReconcileIntervalPollsMongoHeadOnlyOnce() {
        var reconciler = mock(MongoSubscriptionHeadReconciler.class);
        var periodicTask = new AtomicReference<Runnable>();
        var scheduler = manualScheduler(periodicTask);

        try (var poller = new MongoSubscriptionHeadPoller(reconciler, Duration.ZERO, Duration.ofSeconds(10),
                scheduler)) {
            poller.start();

            verify(reconciler).reconcile();
            assertThat(periodicTask.get()).isNull();
        }
    }

    @Test
    void startupBarrierWaitsForFirstFreshSnapshot() throws Exception {
        var cache = mock(LocalSubscriptionCache.class);
        var firstFreshSnapshot = new CompletableFuture<Void>();
        when(cache.firstFreshSnapshot()).thenReturn(firstFreshSnapshot);
        var properties = new CacheProperties();
        properties.getLocalSubscriptionCache().setRequireLocalCacheAtStartup(true);
        properties.getLocalSubscriptionCache().setInitialSnapshotTimeout(Duration.ofMillis(1));
        var barrier = new LocalSubscriptionCacheAutoConfiguration()
            .localSubscriptionCacheStartupBarrier(cache, properties);

        assertThatThrownBy(() -> barrier.run(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("did not become fresh");

        firstFreshSnapshot.complete(null);
        barrier.run(null);
    }

    @Test
    void startupBarrierWaitsIndefinitelyWhenTimeoutIsZero() throws Exception {
        var cache = mock(LocalSubscriptionCache.class);
        var firstFreshSnapshot = new CompletableFuture<Void>();
        when(cache.firstFreshSnapshot()).thenReturn(firstFreshSnapshot);
        var properties = new CacheProperties();
        properties.getLocalSubscriptionCache().setRequireLocalCacheAtStartup(true);
        properties.getLocalSubscriptionCache().setInitialSnapshotTimeout(Duration.ZERO);
        var barrier = new LocalSubscriptionCacheAutoConfiguration()
            .localSubscriptionCacheStartupBarrier(cache, properties);

        var barrierRun = CompletableFuture.runAsync(() -> {
            try {
                barrier.run(null);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });

        verify(cache, timeout(1000)).firstFreshSnapshot();
        assertFalse(barrierRun.isDone());
        firstFreshSnapshot.complete(null);
        barrierRun.get(1, TimeUnit.SECONDS);
    }

    @Test
    void startupBarrierDoesNotWaitWhenLocalCacheIsNotRequired() throws Exception {
        var cache = mock(LocalSubscriptionCache.class);
        var properties = new CacheProperties();
        properties.getLocalSubscriptionCache().setRequireLocalCacheAtStartup(false);
        var barrier = new LocalSubscriptionCacheAutoConfiguration()
            .localSubscriptionCacheStartupBarrier(cache, properties);

        barrier.run(null);

        verify(cache, never()).firstFreshSnapshot();
    }

    @Test
    void startupBarrierAlwaysRequiresLocalCacheWithoutSharedFallback() {
        var cache = mock(LocalSubscriptionCache.class);
        when(cache.firstFreshSnapshot()).thenReturn(new CompletableFuture<>());
        var properties = new CacheProperties();
        properties.getLocalSubscriptionCache().setRequireLocalCacheAtStartup(false);
        properties.getLocalSubscriptionCache().setFallbackMode(CacheProperties.LocalSubscriptionCacheFallback.NONE);
        properties.getLocalSubscriptionCache().setInitialSnapshotTimeout(Duration.ofMillis(1));
        var barrier = new LocalSubscriptionCacheAutoConfiguration()
            .localSubscriptionCacheStartupBarrier(cache, properties);

        assertThatThrownBy(() -> barrier.run(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("did not become fresh");
    }

    @Test
    void requireLocalCacheAtStartupDefaultsToTrue() {
        contextRunner.run(context -> assertThat(context.getBean(CacheProperties.class)
            .getLocalSubscriptionCache().isRequireLocalCacheAtStartup()).isTrue());
    }

    @Test
    void enabledClientRequiresConnectString() {
        enabledRunner
            .run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void configuredTimeoutsReachCuratorClient() throws Exception {
        try (var server = new TestingServer()) {
            enabledRunner
                .withPropertyValues(
                    "horizon.cache.local-subscription-cache.zoo-keeper.connect-string=" + server.getConnectString(),
                    "horizon.cache.local-subscription-cache.zoo-keeper.connection-timeout=5s",
                    "horizon.cache.local-subscription-cache.zoo-keeper.session-timeout=10s")
                .run(context -> {
                    var client = context.getBean(CuratorFramework.class);
                    assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
                    assertThat(client.getZookeeperClient().getConnectionTimeoutMs()).isEqualTo(5000);
                    assertThat(client.getZookeeperClient().getZooKeeper().getSessionTimeout()).isEqualTo(10000);
                });
        }
    }

    @Test
    void ensembleTrackerCanBeDisabledForLocalDockerSetup() throws Exception {
        try (var server = new TestingServer()) {
            enabledRunner
                .withPropertyValues(
                    "horizon.cache.local-subscription-cache.zoo-keeper.connect-string=" + server.getConnectString(),
                    "horizon.cache.local-subscription-cache.zoo-keeper.ensemble-tracker-enabled=false")
                .run(context -> {
                    var client = context.getBean(CuratorFramework.class);
                    assertThat(context.getBean(CacheProperties.class).getLocalSubscriptionCache()
                        .getZooKeeper().isEnsembleTrackerEnabled()).isFalse();
                    assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
                    assertThat(client.getCurrentConfig()).isNull();
                });
        }
    }

    @Test
    void enabledClientRejectsNonPositiveTimeout() {
        enabledRunner
            .withPropertyValues(
                "horizon.cache.local-subscription-cache.zoo-keeper.connect-string=localhost:2181",
                "horizon.cache.local-subscription-cache.zoo-keeper.connection-timeout=0s")
            .run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void enabledClientRejectsSubMillisecondTimeout() {
        enabledRunner
            .withPropertyValues(
                "horizon.cache.local-subscription-cache.zoo-keeper.connect-string=localhost:2181",
                "horizon.cache.local-subscription-cache.zoo-keeper.session-timeout=1ns")
            .run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void enabledClientConnectsAndClosesWithContext() throws Exception {
        try (var server = new TestingServer()) {
            var clientReference = new AtomicReference<CuratorFramework>();
            enabledRunner
                .withPropertyValues(
                    "horizon.cache.local-subscription-cache.zoo-keeper.connect-string=" + server.getConnectString())
                .run(context -> {
                    var client = context.getBean(CuratorFramework.class);
                    clientReference.set(client);
                    assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
                });
            assertThat(clientReference.get().getState()).isEqualTo(CuratorFrameworkState.STOPPED);
        }
    }

    @Test
    void readsCurrentHeadsFromSeparatePaths() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            var reader = new ZooKeeperSubscriptionSnapshotHeadReader(client, new ObjectMapper(),
                "/subscriptions/prepared", "/subscriptions/activate");

            assertThat(reader.readPrepared()).isEmpty();
            assertThat(reader.readActivate()).isEmpty();

            client.create().creatingParentsIfNeeded().forPath("/subscriptions/prepared", head("first"));
            client.create().forPath("/subscriptions/activate", head("second"));

            assertThat(reader.readPrepared()).get().extracting("snapshotId").isEqualTo("first");
            var activated = reader.readActivate().orElseThrow();
            assertThat(activated.getSnapshotId()).isEqualTo("second");
            assertThat(activated.getDocumentCount()).isEqualTo(1L);
            assertThat(activated.getCreatedAt().toInstant()).isEqualTo(Instant.parse("2026-09-30T12:30:00Z"));

            client.setData().forPath("/subscriptions/activate", head("third"));
            assertThat(reader.readActivate().orElseThrow().getSnapshotId()).isEqualTo("third");
        }
    }

    @Test
    void distinguishesInvalidDataAndReadFailureFromMissingNodes() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            var reader = new ZooKeeperSubscriptionSnapshotHeadReader(client, new ObjectMapper(),
                "/subscriptions/prepared", "/subscriptions/activate");
            client.create().creatingParentsIfNeeded().forPath("/subscriptions/prepared", "{}".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(reader::readPrepared).isInstanceOf(SubscriptionCacheSnapshotException.class);
            assertThat(reader.readActivate()).isEmpty();

            client.close();
            assertThatThrownBy(reader::readActivate).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void headReadIsBoundedByConnectionTimeoutWhenServerIsUnreachable() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.builder()
                 .connectString(server.getConnectString())
                 .connectionTimeoutMs(500)
                 .sessionTimeoutMs(30_000)
                 .retryPolicy(new RetryNTimes(5, 2000))
                 .build()) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            client.create().creatingParentsIfNeeded().forPath("/subscriptions/activate", head("active"));
            var reader = new ZooKeeperSubscriptionSnapshotHeadReader(client, new ObjectMapper(),
                "/subscriptions/prepared", "/subscriptions/activate");
            assertThat(reader.readActivate()).get().extracting("snapshotId").isEqualTo("active");

            server.stop();
            var start = System.nanoTime();
            assertThatThrownBy(reader::readActivate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Timed out after 500 ms");
            // Unbounded, the retries alone would take at least 10 seconds.
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(5_000);
        }
    }

    private static ScheduledExecutorService manualScheduler(AtomicReference<Runnable> periodicTask) {
        return manualScheduler(periodicTask, new AtomicReference<>(), new AtomicReference<>(), new AtomicLong());
    }

    private static ScheduledExecutorService manualScheduler(AtomicReference<Runnable> periodicTask,
                                                            AtomicReference<Runnable> delayedPreparedTask,
                                                            AtomicReference<ScheduledFuture<?>> delayedPreparedFuture,
                                                            AtomicLong delayedPreparedIntervalMillis) {
        var scheduler = mock(ScheduledExecutorService.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(scheduler).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        doAnswer(invocation -> {
            periodicTask.set(invocation.getArgument(0));
            return mock(ScheduledFuture.class);
        }).when(scheduler).scheduleWithFixedDelay(
            org.mockito.ArgumentMatchers.any(Runnable.class),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any(TimeUnit.class));
        doAnswer(invocation -> {
            delayedPreparedTask.set(invocation.getArgument(0));
            delayedPreparedIntervalMillis.set(invocation.getArgument(1));
            var future = mock(ScheduledFuture.class);
            delayedPreparedFuture.set(future);
            return future;
        }).when(scheduler).schedule(
            org.mockito.ArgumentMatchers.any(Runnable.class),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any(TimeUnit.class));
        return scheduler;
    }

    private static byte[] head(String snapshotId) {
        return ("{\"snapshotId\":\"" + snapshotId + "\",\"documentCount\":1,\"revision\":null,"
            + "\"sourceHash\":\"hash\",\"createdAt\":\"2026-09-30T12:30:00Z\"}")
            .getBytes(StandardCharsets.UTF_8);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CacheProperties.class)
    static class TestProperties {
    }
}