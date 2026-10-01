package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SubscriptionZooKeeperClientTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestProperties.class)
        .withConfiguration(AutoConfigurations.of(LocalSubscriptionCacheAutoConfiguration.class));

    @Test
    void clientIsDisabledByDefault() {
        contextRunner.run(context -> {
            assertFalse(context.containsBean("subscriptionZooKeeperClient"));
            assertFalse(context.containsBean("subscriptionHeadWatcher"));
        });
    }

    @Test
    void enabledLocalCacheStartsWatcherWithoutReadingLegacyMongoHead() throws Exception {
        try (var server = new TestingServer()) {
            var cache = mock(LocalSubscriptionCache.class);
            contextRunner
                .withBean(LocalSubscriptionCache.class, () -> cache)
                .withPropertyValues(
                    "horizon.cache.local-subscription-cache.enabled=true",
                    "horizon.cache.local-subscription-cache.zoo-keeper.enabled=true",
                    "horizon.cache.local-subscription-cache.zoo-keeper.connect-string=" + server.getConnectString(),
                    "horizon.cache.local-subscription-cache.zoo-keeper.prepared-path=/subscriptions/prepared",
                    "horizon.cache.local-subscription-cache.zoo-keeper.activate-path=/subscriptions/activate")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(ZooKeeperSubscriptionHeadWatcher.class)
                        .initialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);
                    context.getBean(LocalSubscriptionCacheInitializer.class).run(null);
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
                "horizon.cache.local-subscription-cache.zoo-keeper.enabled=true",
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
                "horizon.cache.local-subscription-cache.zoo-keeper.enabled=true",
                "horizon.cache.local-subscription-cache.zoo-keeper.prepared-path=/subscriptions/prepared")
            .run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void zNodePathsBindIndependently() {
        contextRunner
            .withPropertyValues(
                "horizon.cache.local-subscription-cache.zoo-keeper.prepared-path=/horizon/subscriptions/prepared",
                "horizon.cache.local-subscription-cache.zoo-keeper.activate-path=/horizon/subscriptions/activate")
            .run(context -> {
                var properties = context.getBean(CacheProperties.class).getLocalSubscriptionCache().getZooKeeper();
                assertThat(properties.getPreparedPath()).isEqualTo("/horizon/subscriptions/prepared");
                assertThat(properties.getActivatePath()).isEqualTo("/horizon/subscriptions/activate");
            });
    }

    @Test
    void enabledClientRequiresConnectString() {
        contextRunner
            .withPropertyValues("horizon.cache.local-subscription-cache.zoo-keeper.enabled=true")
            .run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void configuredTimeoutsReachCuratorClient() throws Exception {
        try (var server = new TestingServer()) {
            contextRunner
                .withPropertyValues(
                    "horizon.cache.local-subscription-cache.zoo-keeper.enabled=true",
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
            contextRunner
                .withPropertyValues(
                    "horizon.cache.local-subscription-cache.zoo-keeper.enabled=true",
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
        contextRunner
            .withPropertyValues(
                "horizon.cache.local-subscription-cache.zoo-keeper.enabled=true",
                "horizon.cache.local-subscription-cache.zoo-keeper.connect-string=localhost:2181",
                "horizon.cache.local-subscription-cache.zoo-keeper.connection-timeout=0s")
            .run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void enabledClientRejectsSubMillisecondTimeout() {
        contextRunner
            .withPropertyValues(
                "horizon.cache.local-subscription-cache.zoo-keeper.enabled=true",
                "horizon.cache.local-subscription-cache.zoo-keeper.connect-string=localhost:2181",
                "horizon.cache.local-subscription-cache.zoo-keeper.session-timeout=1ns")
            .run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void enabledClientConnectsAndClosesWithContext() throws Exception {
        try (var server = new TestingServer()) {
            var clientReference = new AtomicReference<CuratorFramework>();
            contextRunner
                .withPropertyValues(
                    "horizon.cache.local-subscription-cache.zoo-keeper.enabled=true",
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
    void watchesBothHeadsAndReadsTheirCurrentValuesOnStartAndChange() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            client.create().creatingParentsIfNeeded().forPath("/subscriptions/activate", head("active-1"));
            var reader = new ZooKeeperSubscriptionSnapshotHeadReader(client, new ObjectMapper(),
                "/subscriptions/prepared", "/subscriptions/activate");
            BlockingQueue<String> observed = new LinkedBlockingQueue<>();
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            doAnswer(ignored -> {
                observed.add(reader.readPrepared().map(value -> value.getSnapshotId()).orElse("missing") + ":"
                    + reader.readActivate().map(value -> value.getSnapshotId()).orElse("missing"));
                return null;
            }).when(reconciler).run();

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler)) {
                watcher.start();
                awaitObserved(observed, "missing:active-1");

                client.create().forPath("/subscriptions/prepared", head("prepared-1"));
                awaitObserved(observed, "prepared-1:active-1");

                client.setData().forPath("/subscriptions/activate", head("active-2"));
                awaitObserved(observed, "prepared-1:active-2");

                client.delete().forPath("/subscriptions/prepared");
                awaitObserved(observed, "missing:active-2");
            }
        }
    }

    @Test
    void suspensionAndLossInvalidateWhileReconnectSchedulesForcedReconciliation() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler)) {
                watcher.start();
                verify(reconciler, timeout(10000)).run();

                watcher.connectionStateChanged(client, ConnectionState.SUSPENDED);
                watcher.connectionStateChanged(client, ConnectionState.LOST);
                verify(reconciler, times(2)).suspended();

                watcher.connectionStateChanged(client, ConnectionState.RECONNECTED);
                verify(reconciler, timeout(10000)).reconcileAfterReconnect();
            }
        }
    }

    @Test
    void initialReconciliationFinishesOnlyAfterCallbackReturns() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            doAnswer(ignored -> {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return null;
            }).when(reconciler).run();

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler)) {
                watcher.start();
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertFalse(watcher.initialReconciliation().toCompletableFuture().isDone());
                release.countDown();
                watcher.initialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void missingActivateHeadCompletesInitialReconciliation() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            var reader = new ZooKeeperSubscriptionSnapshotHeadReader(client, new ObjectMapper(),
                "/subscriptions/prepared", "/subscriptions/activate");
            var cache = mock(de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache.class);
            var reconciler = new ZooKeeperSubscriptionHeadReconciler(reader, cache);

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler)) {
                watcher.start();
                watcher.initialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);
                verify(cache).disconnected();
            }
        }
    }

    @Test
    void initialReadFailureIsReportedToStartup() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            doAnswer(ignored -> {
                throw new IllegalStateException("ZooKeeper unavailable");
            }).when(reconciler).run();

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler)) {
                watcher.start();
                var error = assertThrows(ExecutionException.class,
                    () -> watcher.initialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS));
                assertThat(error.getCause()).isInstanceOf(IllegalStateException.class)
                    .hasMessage("ZooKeeper unavailable");
            }
        }
    }

    @Test
    void suspensionDuringInitialReconciliationIsReportedAsInterrupted() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            doAnswer(ignored -> {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return null;
            }).when(reconciler).run();

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler)) {
                watcher.start();
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                watcher.connectionStateChanged(client, ConnectionState.SUSPENDED);
                release.countDown();
                assertThrows(ExecutionException.class,
                    () -> watcher.initialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
    }

    private static void awaitObserved(BlockingQueue<String> observed, String expected) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String actual;
        do {
            var remaining = deadline - System.nanoTime();
            actual = remaining > 0 ? observed.poll(remaining, TimeUnit.NANOSECONDS) : null;
        } while (actual != null && !actual.equals(expected));
        assertThat(actual).isEqualTo(expected);
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