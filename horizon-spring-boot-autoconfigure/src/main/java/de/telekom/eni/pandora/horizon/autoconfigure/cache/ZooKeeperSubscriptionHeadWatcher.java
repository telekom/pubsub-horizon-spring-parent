package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ZooKeeperSubscriptionHeadWatcher implements AutoCloseable {

    private final CuratorCache preparedCache;
    private final CuratorCache activateCache;
    private final Runnable reconcile;
    private final Runnable periodicReconcile;
    private final java.util.function.Consumer<byte[]> preparePreparedEvent;
    private final Runnable suspend;
    private final Runnable lost;
    private final Runnable reconnect;
    private final CuratorFramework client;
    private final ConnectionStateListener connectionStateListener;
    private final long reconcileIntervalMillis;
    private final long snapshotSyncJitterMillis;
    private final CompletableFuture<Void> initialReconciliation = new CompletableFuture<>();
    private final ScheduledExecutorService executor;
    private boolean watching;
    private boolean connected = true;
    private boolean sessionLost;
    private long connectionEpoch;
    private long preparedEventGeneration;
    private ScheduledFuture<?> pendingPreparedReconcile;
    private ScheduledFuture<?> pendingReconnectReconcile;
    private boolean reconnectPending;

    public ZooKeeperSubscriptionHeadWatcher(CuratorFramework client, String preparedPath, String activatePath,
                                            ZooKeeperSubscriptionHeadReconciler reconciler) {
        this(client, preparedPath, activatePath, reconciler, Duration.ofSeconds(300), Duration.ofSeconds(10));
    }

    public ZooKeeperSubscriptionHeadWatcher(CuratorFramework client, String preparedPath, String activatePath,
                                            ZooKeeperSubscriptionHeadReconciler reconciler,
                                            Duration reconcileInterval) {
        this(client, preparedPath, activatePath, reconciler, reconcileInterval, Duration.ofSeconds(10), newExecutor());
    }

    public ZooKeeperSubscriptionHeadWatcher(CuratorFramework client, String preparedPath, String activatePath,
                                            ZooKeeperSubscriptionHeadReconciler reconciler,
                                            Duration reconcileInterval, Duration snapshotSyncJitter) {
        this(client, preparedPath, activatePath, reconciler, reconcileInterval,
            snapshotSyncJitter, newExecutor());
    }

    ZooKeeperSubscriptionHeadWatcher(CuratorFramework client, String preparedPath, String activatePath,
                                     ZooKeeperSubscriptionHeadReconciler reconciler, Duration reconcileInterval,
                                     ScheduledExecutorService executor) {
        this(client, preparedPath, activatePath, reconciler, reconcileInterval,
            Duration.ofSeconds(10), executor);
    }

    ZooKeeperSubscriptionHeadWatcher(CuratorFramework client, String preparedPath, String activatePath,
                                     ZooKeeperSubscriptionHeadReconciler reconciler, Duration reconcileInterval,
                                     Duration snapshotSyncJitter, ScheduledExecutorService executor) {
        this.client = client;
        this.reconcileIntervalMillis = nonNegativeMillis(reconcileInterval, "reconcile interval");
        this.snapshotSyncJitterMillis = nonNegativeMillis(snapshotSyncJitter, "snapshot sync jitter");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.preparedCache = CuratorCache.build(client, preparedPath);
        this.activateCache = CuratorCache.build(client, activatePath);
        this.reconcile = reconciler;
        this.periodicReconcile = reconciler::reconcileActiveHead;
        this.preparePreparedEvent = reconciler::preparePreparedEvent;
        this.suspend = reconciler::suspended;
        this.lost = reconciler::lost;
        this.reconnect = reconciler::reconcileAfterReconnect;
        this.connectionStateListener = this::connectionStateChanged;
        preparedCache.listenable().addListener((type, oldData, data) ->
            preparedHeadChanged(data == null ? null : data.getData()));
        activateCache.listenable().addListener((type, oldData, data) -> scheduleActivateReconcile());
    }

    private static ScheduledExecutorService newExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "subscription-head-watcher");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void start() {
        if (watching) {
            throw new IllegalStateException("Subscription head watcher already started");
        }
        client.getConnectionStateListenable().addListener(connectionStateListener);
        preparedCache.start();
        activateCache.start();
        watching = true;
        var epoch = connectionEpoch;
        executor.execute(() -> {
            try {
                if (runIfConnected(epoch, reconcile)) {
                    initialReconciliation.complete(null);
                } else {
                    initialReconciliation.completeExceptionally(
                        new IllegalStateException("Initial subscription head reconciliation was interrupted"));
                }
            } catch (RuntimeException | Error exception) {
                initialReconciliation.completeExceptionally(exception);
                throw exception;
            }
        });
        if (reconcileIntervalMillis > 0) {
            executor.scheduleWithFixedDelay(this::reconcilePeriodically,
                reconcileIntervalMillis, reconcileIntervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    public CompletionStage<Void> initialReconciliation() {
        return initialReconciliation.minimalCompletionStage();
    }

    synchronized void scheduleActivateReconcile() {
        if (watching && connected) {
            cancelPendingPreparedReconcile();
            var epoch = connectionEpoch;
            var task = reconnectPending ? reconnect : reconcile;
            cancelPendingReconnectReconcile();
            executor.execute(() -> runIfConnected(epoch, task));
        }
    }

    void preparedHeadChanged(byte[] data) {
        synchronized (this) {
            if (!watching || !connected) {
                return;
            }
            cancelPendingPreparedReconcile();
            var epoch = connectionEpoch;
            var generation = preparedEventGeneration;
            var eventData = data == null ? null : data.clone();
            var delayMillis = randomDelayMillis(snapshotSyncJitterMillis);
            pendingPreparedReconcile = executor.schedule(
                () -> runPreparedReconcileIfCurrent(epoch, generation, eventData), delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void runPreparedReconcileIfCurrent(long epoch, long generation, byte[] data) {
        synchronized (this) {
            if (!watching || !connected || epoch != connectionEpoch || generation != preparedEventGeneration) {
                return;
            }
            pendingPreparedReconcile = null;
        }
        try {
            runIfConnected(epoch, () -> preparePreparedEvent.accept(data));
        } catch (RuntimeException exception) {
            log.warn("Jittered ZooKeeper PREPARED reconciliation failed", exception);
        }
    }

    private void cancelPendingPreparedReconcile() {
        preparedEventGeneration++;
        if (pendingPreparedReconcile != null) {
            pendingPreparedReconcile.cancel(false);
            pendingPreparedReconcile = null;
        }
    }

    private void cancelPendingReconnectReconcile() {
        reconnectPending = false;
        if (pendingReconnectReconcile != null) {
            pendingReconnectReconcile.cancel(false);
            pendingReconnectReconcile = null;
        }
    }

    private void runPendingReconnect(long epoch) {
        synchronized (this) {
            if (!reconnectPending || epoch != connectionEpoch) {
                return;
            }
            reconnectPending = false;
            pendingReconnectReconcile = null;
        }
        runIfConnected(epoch, reconnect);
    }

    private static long randomDelayMillis(long maxDelayMillis) {
        if (maxDelayMillis == 0) {
            return 0;
        }
        return maxDelayMillis == Long.MAX_VALUE
            ? ThreadLocalRandom.current().nextLong(maxDelayMillis)
            : ThreadLocalRandom.current().nextLong(maxDelayMillis + 1);
    }

    private void reconcilePeriodically() {
        long epoch;
        synchronized (this) {
            if (!watching || !connected) {
                return;
            }
            epoch = connectionEpoch;
        }
        try {
            runIfConnected(epoch, periodicReconcile);
        } catch (RuntimeException exception) {
            log.warn("Periodic ZooKeeper ACTIVATE reconciliation failed", exception);
        }
    }

    private static long nonNegativeMillis(Duration interval, String name) {
        if (interval == null || interval.isNegative()) {
            throw new IllegalArgumentException("ZooKeeper " + name + " must not be negative or null");
        }
        if (interval.isZero()) {
            return 0;
        }
        try {
            var millis = interval.toMillis();
            if (millis == 0) {
                throw new IllegalArgumentException("ZooKeeper " + name + " must be 0 or at least 1ms");
            }
            return millis;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("ZooKeeper " + name + " exceeds the supported range", exception);
        }
    }

    private boolean runIfConnected(long epoch, Runnable task) {
        synchronized (this) {
            if (!watching || !connected || epoch != connectionEpoch) {
                return false;
            }
        }
        try {
            task.run();
        } finally {
            boolean invalidateForLostSession;
            boolean invalidateForSuspension;
            synchronized (this) {
                invalidateForLostSession = watching && !connected && sessionLost && epoch != connectionEpoch;
                invalidateForSuspension = watching && !connected && !sessionLost && epoch != connectionEpoch;
            }
            if (invalidateForLostSession) {
                lost.run();
            } else if (invalidateForSuspension) {
                suspend.run();
            }
        }
        synchronized (this) {
            return watching && connected && epoch == connectionEpoch;
        }
    }

    void connectionStateChanged(CuratorFramework ignored, ConnectionState state) {
        synchronized (this) {
            if (!watching) {
                return;
            }
            if (state == ConnectionState.SUSPENDED) {
                cancelPendingPreparedReconcile();
                cancelPendingReconnectReconcile();
                connected = false;
                sessionLost = false;
                connectionEpoch++;
                suspend.run();
            } else if (state == ConnectionState.LOST) {
                cancelPendingPreparedReconcile();
                cancelPendingReconnectReconcile();
                connected = false;
                sessionLost = true;
                connectionEpoch++;
                lost.run();
            } else if (state == ConnectionState.RECONNECTED) {
                cancelPendingPreparedReconcile();
                cancelPendingReconnectReconcile();
                connected = true;
                sessionLost = false;
                var epoch = ++connectionEpoch;
                reconnectPending = true;
                pendingReconnectReconcile = executor.schedule(
                    () -> runPendingReconnect(epoch), randomDelayMillis(snapshotSyncJitterMillis),
                    TimeUnit.MILLISECONDS);
            }
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            watching = false;
            cancelPendingPreparedReconcile();
            cancelPendingReconnectReconcile();
            initialReconciliation.completeExceptionally(
                new IllegalStateException("Subscription head watcher closed before initial reconciliation"));
            client.getConnectionStateListenable().removeListener(connectionStateListener);
            executor.shutdownNow();
        }
        preparedCache.close();
        activateCache.close();
    }
}