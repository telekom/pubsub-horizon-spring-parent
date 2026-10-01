package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ZooKeeperSubscriptionHeadWatcher implements AutoCloseable {

    private final CuratorCache preparedCache;
    private final CuratorCache activateCache;
    private final Runnable reconcile;
    private final Runnable periodicReconcile;
    private final Runnable suspend;
    private final Runnable lost;
    private final Runnable reconnect;
    private final CuratorFramework client;
    private final ConnectionStateListener connectionStateListener;
    private final long reconcileIntervalMillis;
    private final CompletableFuture<Void> initialReconciliation = new CompletableFuture<>();
    private final ScheduledExecutorService executor;
    private boolean watching;
    private boolean connected = true;
    private boolean sessionLost;
    private long connectionEpoch;

    public ZooKeeperSubscriptionHeadWatcher(CuratorFramework client, String preparedPath, String activatePath,
                                            ZooKeeperSubscriptionHeadReconciler reconciler) {
        this(client, preparedPath, activatePath, reconciler, Duration.ofSeconds(300));
    }

    public ZooKeeperSubscriptionHeadWatcher(CuratorFramework client, String preparedPath, String activatePath,
                                            ZooKeeperSubscriptionHeadReconciler reconciler,
                                            Duration reconcileInterval) {
        this(client, preparedPath, activatePath, reconciler, reconcileInterval, newExecutor());
    }

    ZooKeeperSubscriptionHeadWatcher(CuratorFramework client, String preparedPath, String activatePath,
                                     ZooKeeperSubscriptionHeadReconciler reconciler, Duration reconcileInterval,
                                     ScheduledExecutorService executor) {
        this.client = client;
        this.reconcileIntervalMillis = nonNegativeMillis(reconcileInterval);
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.preparedCache = CuratorCache.build(client, preparedPath);
        this.activateCache = CuratorCache.build(client, activatePath);
        this.reconcile = reconciler;
        this.periodicReconcile = reconciler::reconcileActiveHead;
        this.suspend = reconciler::suspended;
        this.lost = reconciler::lost;
        this.reconnect = reconciler::reconcileAfterReconnect;
        this.connectionStateListener = this::connectionStateChanged;
        preparedCache.listenable().addListener((type, oldData, data) -> scheduleReconcile());
        activateCache.listenable().addListener((type, oldData, data) -> scheduleReconcile());
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

    private synchronized void scheduleReconcile() {
        if (watching && connected) {
            var epoch = connectionEpoch;
            executor.execute(() -> runIfConnected(epoch, reconcile));
        }
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

    private static long nonNegativeMillis(Duration interval) {
        if (interval == null || interval.isNegative()) {
            throw new IllegalArgumentException("ZooKeeper reconcile interval must not be negative or null");
        }
        if (interval.isZero()) {
            return 0;
        }
        try {
            var millis = interval.toMillis();
            if (millis == 0) {
                throw new IllegalArgumentException("ZooKeeper reconcile interval must be 0 or at least 1ms");
            }
            return millis;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("ZooKeeper reconcile interval exceeds the supported range", exception);
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
                connected = false;
                sessionLost = false;
                connectionEpoch++;
                suspend.run();
            } else if (state == ConnectionState.LOST) {
                connected = false;
                sessionLost = true;
                connectionEpoch++;
                lost.run();
            } else if (state == ConnectionState.RECONNECTED) {
                connected = true;
                sessionLost = false;
                var epoch = ++connectionEpoch;
                executor.execute(() -> runIfConnected(epoch, reconnect));
            }
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            watching = false;
            initialReconciliation.completeExceptionally(
                new IllegalStateException("Subscription head watcher closed before initial reconciliation"));
            client.getConnectionStateListenable().removeListener(connectionStateListener);
            executor.shutdownNow();
        }
        preparedCache.close();
        activateCache.close();
    }
}