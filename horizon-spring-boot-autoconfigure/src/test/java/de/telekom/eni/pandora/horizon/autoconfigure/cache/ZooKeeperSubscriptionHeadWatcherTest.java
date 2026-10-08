package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZooKeeperSubscriptionHeadWatcherTest {

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
                Duration.ofMinutes(5), Duration.ofSeconds(10), Duration.ZERO, scheduler)) {
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
                Duration.ofMinutes(5), Duration.ZERO, Duration.ZERO, scheduler)) {
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
        var client = mock(CuratorFramework.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        try (var caches = org.mockito.Mockito.mockStatic(org.apache.curator.framework.recipes.cache.CuratorCache.class)) {
            caches.when(() -> org.apache.curator.framework.recipes.cache.CuratorCache.build(
                    org.mockito.ArgumentMatchers.eq(client), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(ignored -> mock(org.apache.curator.framework.recipes.cache.CuratorCache.class,
                    org.mockito.Mockito.RETURNS_DEEP_STUBS));
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            var periodicTask = new AtomicReference<Runnable>();
            var reconnectTask = new AtomicReference<Runnable>();
            var reconnectFuture = new AtomicReference<ScheduledFuture<?>>();
            var delayMillis = new AtomicLong();
            var scheduler = manualScheduler(periodicTask, reconnectTask, reconnectFuture, delayMillis);

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ofSeconds(10), Duration.ZERO, scheduler)) {
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
                Duration.ofMinutes(5), Duration.ZERO, Duration.ZERO, scheduler)) {
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
                Duration.ofMinutes(5), Duration.ofSeconds(10), Duration.ZERO, scheduler)) {
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
    void headPollJitterOnlyOffsetsFirstPeriodicReconciliation() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            var scheduler = manualScheduler(new AtomicReference<>());
            var initialDelay = org.mockito.ArgumentCaptor.forClass(Long.class);
            var period = org.mockito.ArgumentCaptor.forClass(Long.class);

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofSeconds(60), Duration.ZERO, Duration.ofSeconds(10), scheduler)) {
                watcher.start();

                verify(scheduler).scheduleWithFixedDelay(org.mockito.ArgumentMatchers.any(Runnable.class),
                    initialDelay.capture(), period.capture(), org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS));
                assertThat(initialDelay.getValue()).isBetween(60_000L, 70_000L);
                assertThat(period.getValue()).isEqualTo(60_000L);
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
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(7), Duration.ofSeconds(10), Duration.ZERO, scheduler)) {
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
    void periodicReconciliationUsesMongoHeadWhileSuspended() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            var periodicTask = new AtomicReference<Runnable>();
            var scheduler = manualScheduler(periodicTask);

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(7), Duration.ofSeconds(10), Duration.ZERO, scheduler)) {
                watcher.start();
                watcher.initialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);
                verify(reconciler).run();
                watcher.connectionStateChanged(client, ConnectionState.SUSPENDED);
                verify(reconciler).reconcileFromMongoHead();
                periodicTask.get().run();
                verify(reconciler, org.mockito.Mockito.never()).reconcileActiveHead();
                verify(reconciler, times(2)).reconcileFromMongoHead();
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
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofSeconds(-1), Duration.ofSeconds(10), Duration.ZERO));
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
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ZERO, Duration.ofSeconds(10), Duration.ZERO, scheduler)) {
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
            }).when(reconciler).preparePreparedEvent(org.mockito.ArgumentMatchers.nullable(byte[].class));

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ZERO, Duration.ZERO)) {
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
                Duration.ofMinutes(5), Duration.ZERO, Duration.ZERO, scheduler)) {
                watcher.start();
                watcher.initialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);
                verify(reconciler).run();

                watcher.connectionStateChanged(client, ConnectionState.SUSPENDED);
                watcher.connectionStateChanged(client, ConnectionState.LOST);
                verify(reconciler).suspended();
                verify(reconciler).lost();
                verify(reconciler, times(1)).reconcileFromMongoHead();

                watcher.connectionStateChanged(client, ConnectionState.RECONNECTED);
                reconnectTask.get().run();
                verify(reconciler).reconcileAfterReconnect();
            }
        }
    }

    @Test
    void firstConnectionAfterLostStartupReconcilesLikeReconnect() throws Exception {
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
                Duration.ofMinutes(5), Duration.ZERO, Duration.ZERO, scheduler)) {
                watcher.start();
                watcher.initialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);

                watcher.connectionStateChanged(client, ConnectionState.CONNECTED);
                assertThat(reconnectTask.get()).isNull();

                watcher.connectionStateChanged(client, ConnectionState.LOST);
                watcher.connectionStateChanged(client, ConnectionState.CONNECTED);
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
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ofSeconds(10), Duration.ZERO)) {
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
            var cache = mock(LocalSubscriptionCache.class);
            when(cache.readSnapshotHead()).thenThrow(new SubscriptionCacheSnapshotException("no head"));
            var reconciler = new ZooKeeperSubscriptionHeadReconciler(reader, cache);

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ofSeconds(10), Duration.ZERO)) {
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
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ofSeconds(10), Duration.ZERO)) {
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
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ofSeconds(10), Duration.ZERO)) {
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
}