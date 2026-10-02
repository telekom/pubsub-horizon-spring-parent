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
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
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
                "horizon.cache.local-subscription-cache.stale-cache-read-grace-period=30s",
                "horizon.cache.local-subscription-cache.initial-snapshot-timeout=45s",
                "horizon.cache.local-subscription-cache.zoo-keeper.prepared-path=/horizon/subscriptions/prepared",
                "horizon.cache.local-subscription-cache.zoo-keeper.activate-path=/horizon/subscriptions/activate",
                "horizon.cache.local-subscription-cache.zoo-keeper.reconcile-interval=7m",
                "horizon.cache.local-subscription-cache.zoo-keeper.snapshot-sync-jitter=12s")
            .run(context -> {
                assertThat(context.getBean(CacheProperties.class).getLocalSubscriptionCache()
                    .getStaleCacheReadGracePeriod()).isEqualTo(Duration.ofSeconds(30));
                assertThat(context.getBean(CacheProperties.class).getLocalSubscriptionCache()
                    .getInitialSnapshotTimeout()).isEqualTo(Duration.ofSeconds(45));
                var properties = context.getBean(CacheProperties.class).getLocalSubscriptionCache().getZooKeeper();
                assertThat(properties.getPreparedPath()).isEqualTo("/horizon/subscriptions/prepared");
                assertThat(properties.getActivatePath()).isEqualTo("/horizon/subscriptions/activate");
                assertThat(properties.getReconcileInterval()).isEqualTo(Duration.ofMinutes(7));
                assertThat(properties.getSnapshotSyncJitter()).isEqualTo(Duration.ofSeconds(12));
            });
    }

    @Test
    void staleCacheReadGracePeriodDefaultsTo120Seconds() {
        contextRunner.run(context -> assertThat(context.getBean(CacheProperties.class)
            .getLocalSubscriptionCache().getStaleCacheReadGracePeriod()).isEqualTo(Duration.ofSeconds(120)));
    }

    @Test
    void reconcileIntervalDefaultsTo60Seconds() {
        contextRunner.run(context -> assertThat(context.getBean(CacheProperties.class)
            .getLocalSubscriptionCache().getZooKeeper().getReconcileInterval())
            .isEqualTo(Duration.ofSeconds(60)));
    }

    @Test
    void snapshotSyncJitterDefaultsToTenSeconds() {
        contextRunner.run(context -> assertThat(context.getBean(CacheProperties.class)
            .getLocalSubscriptionCache().getZooKeeper().getSnapshotSyncJitter())
            .isEqualTo(Duration.ofSeconds(10)));
    }

    @Test
    void startupBarrierWaitsForFirstFreshSnapshot() throws Exception {
        var cache = mock(LocalSubscriptionCache.class);
        var firstFreshSnapshot = new CompletableFuture<Void>();
        when(cache.firstFreshSnapshot()).thenReturn(firstFreshSnapshot);
        var properties = new CacheProperties();
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
    void preparedEventsAreDebouncedAndUseLatestPayload() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            var periodicTask = new AtomicReference<Runnable>();
            var delayedPreparedTask = new AtomicReference<Runnable>();
            var delayedPreparedFuture = new AtomicReference<ScheduledFuture<?>>();
            var delayedPreparedIntervalMillis = new AtomicLong();
            var scheduler = manualScheduler(periodicTask, delayedPreparedTask,
                delayedPreparedFuture, delayedPreparedIntervalMillis);
            var firstPayload = head("prepared-1");
            var latestPayload = head("prepared-2");

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ofSeconds(10), scheduler)) {
                watcher.start();
                watcher.initialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);

                watcher.preparedHeadChanged(firstPayload);
                var firstFuture = delayedPreparedFuture.get();
                var firstTask = delayedPreparedTask.get();
                assertThat(delayedPreparedIntervalMillis.get()).isBetween(0L, 10_000L);

                watcher.preparedHeadChanged(latestPayload);
                verify(firstFuture).cancel(false);
                var latestTask = delayedPreparedTask.get();
                assertThat(latestTask).isNotSameAs(firstTask);
                latestTask.run();

                verify(reconciler).preparePreparedEvent(latestPayload);
                verify(reconciler, never()).preparePreparedEvent(firstPayload);
            }
        }
    }

    @Test
    void zeroPreparedJitterSchedulesImmediatePreparation() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            var periodicTask = new AtomicReference<Runnable>();
            var delayedPreparedTask = new AtomicReference<Runnable>();
            var delayedPreparedIntervalMillis = new AtomicLong();
            var scheduler = manualScheduler(periodicTask, delayedPreparedTask,
                new AtomicReference<>(), delayedPreparedIntervalMillis);
            var payload = head("prepared");

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ZERO, scheduler)) {
                watcher.start();
                watcher.initialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);
                watcher.preparedHeadChanged(payload);

                assertThat(delayedPreparedIntervalMillis.get()).isZero();
                delayedPreparedTask.get().run();
                verify(reconciler).preparePreparedEvent(payload);
            }
        }
    }

    @Test
    void reconnectUsesSnapshotSyncJitterAndDiscardsInterruptedTask() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            var periodicTask = new AtomicReference<Runnable>();
            var reconnectTask = new AtomicReference<Runnable>();
            var reconnectFuture = new AtomicReference<ScheduledFuture<?>>();
            var delayMillis = new AtomicLong();
            var scheduler = manualScheduler(periodicTask, reconnectTask, reconnectFuture, delayMillis);

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ofSeconds(10), scheduler)) {
                watcher.start();
                watcher.initialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);

                watcher.connectionStateChanged(client, ConnectionState.SUSPENDED);
                watcher.connectionStateChanged(client, ConnectionState.RECONNECTED);
                assertThat(delayMillis.get()).isBetween(0L, 10_000L);
                verify(reconciler, never()).reconcileAfterReconnect();
                var interruptedTask = reconnectTask.get();
                watcher.connectionStateChanged(client, ConnectionState.LOST);
                verify(reconnectFuture.get()).cancel(false);
                interruptedTask.run();
                verify(reconciler, never()).reconcileAfterReconnect();

                watcher.connectionStateChanged(client, ConnectionState.RECONNECTED);
                reconnectTask.get().run();
                verify(reconciler).reconcileAfterReconnect();
            }
        }
    }

    @Test
    void zeroSnapshotSyncJitterSchedulesReconnectWithoutDelay() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            var reconnectTask = new AtomicReference<Runnable>();
            var delayMillis = new AtomicLong(-1);
            var scheduler = manualScheduler(new AtomicReference<>(), reconnectTask,
                new AtomicReference<>(), delayMillis);

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ZERO, scheduler)) {
                watcher.start();
                watcher.initialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);
                watcher.connectionStateChanged(client, ConnectionState.RECONNECTED);

                assertThat(delayMillis.get()).isZero();
                reconnectTask.get().run();
                verify(reconciler).reconcileAfterReconnect();
            }
        }
    }

    @Test
    void activateEventReconcilesPendingReconnectImmediately() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            var reconnectTask = new AtomicReference<Runnable>();
            var reconnectFuture = new AtomicReference<ScheduledFuture<?>>();
            var scheduler = manualScheduler(new AtomicReference<>(), reconnectTask,
                reconnectFuture, new AtomicLong());

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ofSeconds(10), scheduler)) {
                watcher.start();
                watcher.initialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);
                watcher.connectionStateChanged(client, ConnectionState.SUSPENDED);
                watcher.connectionStateChanged(client, ConnectionState.RECONNECTED);
                var pendingTask = reconnectTask.get();

                watcher.scheduleActivateReconcile();
                verify(reconciler).reconcileAfterReconnect();
                verify(reconnectFuture.get()).cancel(false);
                pendingTask.run();
                verify(reconciler, times(1)).reconcileAfterReconnect();

                watcher.scheduleActivateReconcile();
                verify(reconciler, times(2)).run();
                verify(reconciler, times(1)).reconcileAfterReconnect();
            }
        }
    }

    @Test
    void periodicReconciliationUsesActivateOnlyPath() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            var periodicTask = new AtomicReference<Runnable>();
            var scheduler = manualScheduler(periodicTask);

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler, Duration.ofMinutes(7), scheduler)) {
                watcher.start();
                watcher.initialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);
                verify(reconciler).run();
                assertThat(periodicTask.get()).isNotNull();
                watcher.connectionStateChanged(client, ConnectionState.RECONNECTED);
                periodicTask.get().run();
                verify(reconciler).reconcileActiveHead();
            }
        }
    }

    @Test
    void periodicReconciliationIsSkippedWhileSuspended() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            var periodicTask = new AtomicReference<Runnable>();
            var scheduler = manualScheduler(periodicTask);

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler, Duration.ofMinutes(7), scheduler)) {
                watcher.start();
                watcher.initialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);
                verify(reconciler).run();
                watcher.connectionStateChanged(client, ConnectionState.SUSPENDED);
                periodicTask.get().run();
                verify(reconciler, org.mockito.Mockito.never()).reconcileActiveHead();
            }
        }
    }

    @Test
    void negativeReconcileIntervalIsRejected() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);

            assertThrows(IllegalArgumentException.class, () -> new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler, Duration.ofSeconds(-1)));
        }
    }

    @Test
    void zeroReconcileIntervalDisablesOnlyPeriodicTask() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            var periodicTask = new AtomicReference<Runnable>();
            var reconnectTask = new AtomicReference<Runnable>();
            var scheduler = manualScheduler(periodicTask, reconnectTask, new AtomicReference<>(), new AtomicLong());

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler, Duration.ZERO, scheduler)) {
                watcher.start();
                watcher.initialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);

                assertThat(periodicTask.get()).isNull();
                watcher.connectionStateChanged(client, ConnectionState.RECONNECTED);
                reconnectTask.get().run();
                verify(reconciler).reconcileAfterReconnect();
            }
        }
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
            doAnswer(invocation -> {
                observed.add(reader.readPrepared().map(value -> value.getSnapshotId()).orElse("missing") + ":"
                    + reader.readActivate().map(value -> value.getSnapshotId()).orElse("missing"));
                return null;
            }).when(reconciler).preparePreparedEvent(
                org.mockito.ArgumentMatchers.nullable(byte[].class));

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ZERO)) {
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
    void suspensionAndLossUseDifferentCacheTransitionsBeforeReconnect() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            var reconnectTask = new AtomicReference<Runnable>();
            var scheduler = manualScheduler(new AtomicReference<>(), reconnectTask,
                new AtomicReference<>(), new AtomicLong());
            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ZERO, scheduler)) {
                watcher.start();
                watcher.initialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);
                verify(reconciler).run();

                watcher.connectionStateChanged(client, ConnectionState.SUSPENDED);
                watcher.connectionStateChanged(client, ConnectionState.LOST);
                verify(reconciler).suspended();
                verify(reconciler).lost();

                watcher.connectionStateChanged(client, ConnectionState.RECONNECTED);
                reconnectTask.get().run();
                verify(reconciler).reconcileAfterReconnect();
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