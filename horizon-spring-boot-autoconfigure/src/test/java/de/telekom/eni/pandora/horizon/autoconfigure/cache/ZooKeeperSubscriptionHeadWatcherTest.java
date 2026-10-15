// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
                watcher.getInitialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);

                watcher.onPreparedHeadChanged(firstPayload);
                var firstFuture = delayedPreparedFuture.get();
                var firstTask = delayedPreparedTask.get();
                assertThat(delayedPreparedIntervalMillis.get()).isBetween(0L, 10_000L);

                watcher.onPreparedHeadChanged(latestPayload);
                verify(firstFuture).cancel(false);
                var latestTask = delayedPreparedTask.get();
                assertThat(latestTask).isNotSameAs(firstTask);
                latestTask.run();

                verify(reconciler).reconcilePreparedHead(latestPayload);
                verify(reconciler, never()).reconcilePreparedHead(firstPayload);
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
                watcher.getInitialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);
                watcher.onPreparedHeadChanged(payload);

                assertThat(delayedPreparedIntervalMillis.get()).isZero();
                delayedPreparedTask.get().run();
                verify(reconciler).reconcilePreparedHead(payload);
            }
        }
    }

    @Test
    void reconnectUsesSnapshotSyncJitterAndDiscardsInterruptedTask() throws Exception {
        var client = mock(CuratorFramework.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(client.getZookeeperClient().isConnected()).thenReturn(true);
        try (var caches = org.mockito.Mockito.mockStatic(org.apache.curator.framework.recipes.cache.CuratorCache.class)) {
            caches.when(() -> org.apache.curator.framework.recipes.cache.CuratorCache.build(
                    org.mockito.ArgumentMatchers.eq(client), org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.eq(CuratorCache.Options.SINGLE_NODE_CACHE)))
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
                watcher.getInitialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);

                watcher.onConnectionStateChanged(client, ConnectionState.SUSPENDED);
                watcher.onConnectionStateChanged(client, ConnectionState.RECONNECTED);
                assertThat(delayMillis.get()).isBetween(0L, 10_000L);
                verify(reconciler, times(1)).reconcilePreparedAndActivatedHeads(anyGate());
                var interruptedTask = reconnectTask.get();
                watcher.onConnectionStateChanged(client, ConnectionState.LOST);
                verify(reconnectFuture.get()).cancel(false);
                interruptedTask.run();
                verify(reconciler, times(1)).reconcilePreparedAndActivatedHeads(anyGate());

                watcher.onConnectionStateChanged(client, ConnectionState.RECONNECTED);
                reconnectTask.get().run();
                verify(reconciler, times(2)).reconcilePreparedAndActivatedHeads(anyGate());
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
                watcher.getInitialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);
                watcher.onConnectionStateChanged(client, ConnectionState.RECONNECTED);

                assertThat(delayMillis.get()).isZero();
                reconnectTask.get().run();
                verify(reconciler, times(2)).reconcilePreparedAndActivatedHeads(anyGate());
            }
        }
    }

    @Test
    void activatedEventReconcilesPendingReconnectImmediately() throws Exception {
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
                watcher.getInitialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);
                watcher.onConnectionStateChanged(client, ConnectionState.SUSPENDED);
                watcher.onConnectionStateChanged(client, ConnectionState.RECONNECTED);
                var pendingTask = reconnectTask.get();

                watcher.onActivatedHeadChanged();
                verify(reconciler, times(2)).reconcilePreparedAndActivatedHeads(anyGate());
                verify(reconnectFuture.get()).cancel(false);
                pendingTask.run();
                verify(reconciler, times(2)).reconcilePreparedAndActivatedHeads(anyGate());

                watcher.onActivatedHeadChanged();
                verify(reconciler, times(3)).reconcilePreparedAndActivatedHeads(anyGate());
            }
        }
    }

    @Test
    void headCheckJitterOnlyOffsetsFirstPeriodicReconciliation() throws Exception {
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
    void periodicReconciliationUsesActivatedOnlyPath() throws Exception {
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
                watcher.getInitialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);
                verify(reconciler).reconcilePreparedAndActivatedHeads(anyGate());
                assertThat(periodicTask.get()).isNotNull();
                watcher.onConnectionStateChanged(client, ConnectionState.RECONNECTED);
                periodicTask.get().run();
                verify(reconciler).reconcileActivatedHead(anyGate());
            }
        }
    }

    @Test
    void periodicReconciliationDoesNothingWhileSuspended() throws Exception {
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
                watcher.getInitialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);
                verify(reconciler).reconcilePreparedAndActivatedHeads(anyGate());
                watcher.onConnectionStateChanged(client, ConnectionState.SUSPENDED);
                verify(reconciler).onConnectionLost();
                periodicTask.get().run();
                verify(reconciler, org.mockito.Mockito.never()).reconcileActivatedHead(anyGate());
                verify(reconciler, times(1)).onConnectionLost();
            }
        }
    }

    @Test
    void errorsDoNotStopPeriodicOrEventReconciliation() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            org.mockito.Mockito.doThrow(new OutOfMemoryError("test")).doNothing()
                .when(reconciler).reconcileActivatedHead(anyGate());
            org.mockito.Mockito.doNothing().doThrow(new OutOfMemoryError("test"))
                .when(reconciler).reconcilePreparedAndActivatedHeads(anyGate());
            var periodicTask = new AtomicReference<Runnable>();
            var scheduler = manualScheduler(periodicTask);
            var logger = (Logger) LoggerFactory.getLogger(ZooKeeperSubscriptionHeadWatcher.class);
            var appender = new ListAppender<ILoggingEvent>();
            appender.start();
            logger.addAppender(appender);

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(7), Duration.ZERO, Duration.ZERO, scheduler)) {
                watcher.start();
                watcher.getInitialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);

                // A throwing periodic run would cancel all further runs of scheduleWithFixedDelay.
                assertDoesNotThrow(() -> periodicTask.get().run());
                assertDoesNotThrow(() -> periodicTask.get().run());
                assertDoesNotThrow(watcher::onActivatedHeadChanged);

                verify(reconciler, times(2)).reconcileActivatedHead(anyGate());
                verify(reconciler, times(2)).reconcilePreparedAndActivatedHeads(anyGate());
                assertThat(appender.list)
                    .filteredOn(event -> event.getLevel() == Level.ERROR)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("Unexpected failure in periodic subscription head reconciliation",
                        "Unexpected failure in ACTIVATED event subscription head reconciliation");
            } finally {
                logger.detachAppender(appender);
                appender.stop();
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
                watcher.getInitialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);

                assertThat(periodicTask.get()).isNull();
                watcher.onConnectionStateChanged(client, ConnectionState.RECONNECTED);
                reconnectTask.get().run();
                verify(reconciler, times(2)).reconcilePreparedAndActivatedHeads(anyGate());
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
                observed.add(reader.readPreparedHead().map(value -> value.getSnapshotId()).orElse("missing") + ":"
                    + reader.readActivatedHead().map(value -> value.getSnapshotId()).orElse("missing"));
                return null;
            }).when(reconciler).reconcilePreparedAndActivatedHeads(anyGate());
            doAnswer(invocation -> {
                observed.add(reader.readPreparedHead().map(value -> value.getSnapshotId()).orElse("missing") + ":"
                    + reader.readActivatedHead().map(value -> value.getSnapshotId()).orElse("missing"));
                return null;
            }).when(reconciler).reconcilePreparedHead(org.mockito.ArgumentMatchers.nullable(byte[].class));

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
    void suspensionFollowedByLossMarksCacheUnconfirmedOnceBeforeReconnect() throws Exception {
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
                watcher.getInitialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);
                verify(reconciler).reconcilePreparedAndActivatedHeads(anyGate());

                watcher.onConnectionStateChanged(client, ConnectionState.SUSPENDED);
                watcher.onConnectionStateChanged(client, ConnectionState.LOST);
                verify(reconciler, times(1)).onConnectionLost();

                watcher.onConnectionStateChanged(client, ConnectionState.RECONNECTED);
                reconnectTask.get().run();
                verify(reconciler, times(2)).reconcilePreparedAndActivatedHeads(anyGate());
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
                watcher.getInitialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);

                watcher.onConnectionStateChanged(client, ConnectionState.CONNECTED);
                assertThat(reconnectTask.get()).isNull();

                watcher.onConnectionStateChanged(client, ConnectionState.LOST);
                watcher.onConnectionStateChanged(client, ConnectionState.CONNECTED);
                reconnectTask.get().run();
                verify(reconciler, times(2)).reconcilePreparedAndActivatedHeads(anyGate());
            }
        }
    }

    @Test
    void commitGateRejectsCommitAfterEpochChangedDuringRunningTask() throws Exception {
        var client = mock(CuratorFramework.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        try (var ignored = mockCuratorCaches(client)) {
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            var gate = new AtomicReference<ZooKeeperSubscriptionHeadReconciler.CacheCommitGate>();
            var watcherReference = new AtomicReference<ZooKeeperSubscriptionHeadWatcher>();
            var commits = new AtomicInteger();
            List<Boolean> results = new ArrayList<>();
            doAnswer(invocation -> {
                gate.set(invocation.getArgument(0));
                results.add(gate.get().commitIfStillValid(commits::incrementAndGet));
                // Suspension and reconnect while the task is still running, i.e. after its tryRunInEpoch check.
                watcherReference.get().onConnectionStateChanged(client, ConnectionState.SUSPENDED);
                watcherReference.get().onConnectionStateChanged(client, ConnectionState.RECONNECTED);
                results.add(gate.get().commitIfStillValid(commits::incrementAndGet));
                return null;
            }).when(reconciler).reconcilePreparedAndActivatedHeads(anyGate());

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ofSeconds(10), Duration.ZERO, manualScheduler(new AtomicReference<>()))) {
                watcherReference.set(watcher);
                watcher.start();

                assertThat(results).containsExactly(true, false);
                assertThat(commits.get()).isEqualTo(1);
                assertFalse(gate.get().commitIfStillValid(commits::incrementAndGet));
                assertThat(commits.get()).isEqualTo(1);
            }
        }
    }

    @Test
    void failingActivatedReconciliationIsLoggedAndDoesNotEscape() throws Exception {
        var client = mock(CuratorFramework.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        try (var ignored = mockCuratorCaches(client)) {
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            var logger = (Logger) LoggerFactory.getLogger(ZooKeeperSubscriptionHeadWatcher.class);
            var appender = new ListAppender<ILoggingEvent>();
            appender.start();
            logger.addAppender(appender);
            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ZERO, Duration.ZERO, manualScheduler(new AtomicReference<>()))) {
                watcher.start();
                org.mockito.Mockito.doThrow(new IllegalStateException("unexpected")).when(reconciler)
                    .reconcilePreparedAndActivatedHeads(anyGate());

                assertDoesNotThrow(watcher::onActivatedHeadChanged);

                assertThat(appender.list).anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                        .contains("Unexpected failure in ACTIVATED event subscription head reconciliation");
                });
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
        }
    }

    @Test
    void bothHeadsAreWatchedAsSingleNodes() throws Exception {
        var client = mock(CuratorFramework.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        try (var caches = mockCuratorCaches(client)) {
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ZERO, Duration.ZERO, manualScheduler(new AtomicReference<>()))) {
                caches.verify(() -> CuratorCache.build(client, "/subscriptions/prepared",
                    CuratorCache.Options.SINGLE_NODE_CACHE));
                caches.verify(() -> CuratorCache.build(client, "/subscriptions/activate",
                    CuratorCache.Options.SINGLE_NODE_CACHE));
            }
        }
    }

    @Test
    void childNodesBelowPreparedHeadAreNotReportedAsHeadChanges() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            client.create().creatingParentsIfNeeded().forPath("/subscriptions/prepared", head("prepared-1"));
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ZERO, Duration.ZERO)) {
                watcher.start();
                watcher.getInitialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);

                client.create().forPath("/subscriptions/prepared/child", head("child"));
                client.setData().forPath("/subscriptions/prepared", head("prepared-2"));

                var payload = org.mockito.ArgumentCaptor.forClass(byte[].class);
                verify(reconciler, org.mockito.Mockito.timeout(10_000).atLeastOnce())
                    .reconcilePreparedHead(payload.capture());
                assertThat(payload.getAllValues())
                    .allSatisfy(value -> assertThat(new String(value, StandardCharsets.UTF_8))
                        .doesNotContain("\"child\""));
            }
        }
    }

    @Test
    void connectionLossAndReconnectAreLoggedAsConnectionEvents() throws Exception {
        var client = mock(CuratorFramework.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        try (var ignored = mockCuratorCaches(client)) {
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            var logger = (Logger) LoggerFactory.getLogger(
                de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCacheEvents.EVENTS_LOGGER + ".connection");
            var appender = new ListAppender<ILoggingEvent>();
            appender.start();
            logger.addAppender(appender);
            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ZERO, Duration.ZERO, manualScheduler(new AtomicReference<>()))) {
                watcher.start();

                watcher.onConnectionStateChanged(client, ConnectionState.SUSPENDED);
                watcher.onConnectionStateChanged(client, ConnectionState.LOST);
                watcher.onConnectionStateChanged(client, ConnectionState.RECONNECTED);
                // A second reconnect without a preceding loss is not reported again.
                watcher.onConnectionStateChanged(client, ConnectionState.RECONNECTED);

                assertThat(appender.list).extracting(ILoggingEvent::getLevel)
                    .containsOnly(Level.INFO);
                assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                    "ZooKeeper connection SUSPENDED; local subscription cache is no longer confirmed",
                    "ZooKeeper session LOST after 0s disconnected; watches are re-registered on reconnect",
                    "ZooKeeper reconnected after 0s; rereading subscription heads in 0 ms");
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
        }
    }

    @Test
    void initialConnectionIsNotLoggedAsReconnect() throws Exception {
        var client = mock(CuratorFramework.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        try (var ignored = mockCuratorCaches(client)) {
            when(client.getZookeeperClient().isConnected()).thenReturn(false);
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            var logger = (Logger) LoggerFactory.getLogger(
                de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCacheEvents.EVENTS_LOGGER + ".connection");
            var appender = new ListAppender<ILoggingEvent>();
            appender.start();
            logger.addAppender(appender);
            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ZERO, Duration.ZERO, manualScheduler(new AtomicReference<>()))) {
                watcher.start();
                watcher.onConnectionStateChanged(client, ConnectionState.LOST);
                watcher.onConnectionStateChanged(client, ConnectionState.CONNECTED);

                assertThat(appender.list).isEmpty();
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
        }
    }

    @Test
    void disconnectedStartReconcilesOnFirstConnectionWithoutJitter() throws Exception {
        var client = mock(CuratorFramework.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        try (var ignored = mockCuratorCaches(client)) {
            when(client.getZookeeperClient().isConnected()).thenReturn(false);
            var reconciler = mock(ZooKeeperSubscriptionHeadReconciler.class);
            var reconnectTask = new AtomicReference<Runnable>();
            var delayMillis = new AtomicLong(-1);
            var scheduler = manualScheduler(new AtomicReference<>(), reconnectTask, new AtomicReference<>(),
                delayMillis);

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ofSeconds(10), Duration.ZERO, scheduler)) {
                watcher.start();
                verify(reconciler, never()).reconcilePreparedAndActivatedHeads(anyGate());
                assertFalse(watcher.getInitialReconciliation().toCompletableFuture().isDone());

                watcher.onConnectionStateChanged(client, ConnectionState.CONNECTED);
                assertThat(delayMillis.get()).isZero();
                reconnectTask.get().run();

                verify(reconciler).reconcilePreparedAndActivatedHeads(anyGate());
                watcher.getInitialReconciliation().toCompletableFuture().get(1, TimeUnit.SECONDS);
                verify(reconciler, never()).onConnectionLost();
            }
        }
    }

    private static MockedStatic<CuratorCache> mockCuratorCaches(CuratorFramework client) {
        when(client.getZookeeperClient().isConnected()).thenReturn(true);
        var caches = org.mockito.Mockito.mockStatic(CuratorCache.class);
        caches.when(() -> CuratorCache.build(org.mockito.ArgumentMatchers.eq(client),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(CuratorCache.Options.SINGLE_NODE_CACHE)))
            .thenAnswer(ignored -> mock(CuratorCache.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
        return caches;
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
            }).when(reconciler).reconcilePreparedAndActivatedHeads(anyGate());

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ofSeconds(10), Duration.ZERO)) {
                watcher.start();
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertFalse(watcher.getInitialReconciliation().toCompletableFuture().isDone());
                release.countDown();
                watcher.getInitialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void missingActivatedHeadCompletesInitialReconciliation() throws Exception {
        try (var server = new TestingServer();
             var client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100))) {
            client.start();
            assertTrue(client.blockUntilConnected(10, TimeUnit.SECONDS));
            var reader = new ZooKeeperSubscriptionSnapshotHeadReader(client, new ObjectMapper(),
                "/subscriptions/prepared", "/subscriptions/activate");
            var cache = mock(LocalSubscriptionCache.class);
            var reconciler = new ZooKeeperSubscriptionHeadReconciler(reader, cache);

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ofSeconds(10), Duration.ZERO)) {
                watcher.start();
                watcher.getInitialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS);
                verify(cache).clearExpectedHead();
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
            }).when(reconciler).reconcilePreparedAndActivatedHeads(anyGate());
            var logger = (Logger) LoggerFactory.getLogger(ZooKeeperSubscriptionHeadWatcher.class);
            var appender = new ListAppender<ILoggingEvent>();
            appender.start();
            logger.addAppender(appender);

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ofSeconds(10), Duration.ZERO)) {
                watcher.start();
                var error = assertThrows(ExecutionException.class,
                    () -> watcher.getInitialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS));
                assertThat(error.getCause()).isInstanceOf(IllegalStateException.class)
                    .hasMessage("ZooKeeper unavailable");
                assertThat(appender.list).anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                        .isEqualTo("Unexpected failure in initial subscription head reconciliation");
                    assertThat(event.getThrowableProxy()).isNotNull();
                });
            } finally {
                logger.detachAppender(appender);
                appender.stop();
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
            }).when(reconciler).reconcilePreparedAndActivatedHeads(anyGate());

            try (var watcher = new ZooKeeperSubscriptionHeadWatcher(client,
                "/subscriptions/prepared", "/subscriptions/activate", reconciler,
                Duration.ofMinutes(5), Duration.ofSeconds(10), Duration.ZERO)) {
                watcher.start();
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                watcher.onConnectionStateChanged(client, ConnectionState.SUSPENDED);
                release.countDown();
                assertThrows(ExecutionException.class,
                    () -> watcher.getInitialReconciliation().toCompletableFuture().get(10, TimeUnit.SECONDS));
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

    private static ZooKeeperSubscriptionHeadReconciler.CacheCommitGate anyGate() {
        return org.mockito.ArgumentMatchers.any();
    }

    private static byte[] head(String snapshotId) {
        return ("{\"snapshotId\":\"" + snapshotId + "\",\"documentCount\":1,\"revision\":null,"
            + "\"sourceHash\":\"hash\",\"createdAt\":\"2026-09-30T12:30:00Z\"}")
            .getBytes(StandardCharsets.UTF_8);
    }
}