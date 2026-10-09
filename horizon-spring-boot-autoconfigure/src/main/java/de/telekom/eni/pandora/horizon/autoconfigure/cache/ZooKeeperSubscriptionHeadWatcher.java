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

/**
 * Watches ZooKeeper subscription heads and schedules serialized reconciliation work for one pod.
 *
 * <p>The watcher owns event timing and connection transitions; snapshot and head-fallback policy is delegated to the
 * reconciler.</p>
 */
@Slf4j
public class ZooKeeperSubscriptionHeadWatcher implements AutoCloseable {

    private static final long NO_RUNNING_TASK = -1;

    private final CuratorCache preparedCache;
    private final CuratorCache activateCache;
    private final ZooKeeperSubscriptionHeadReconciler reconciler;
    private final CuratorFramework client;
    private final ConnectionStateListener connectionStateListener;
    private final long reconcileIntervalMillis;
    private final long snapshotSyncJitterMillis;
    private final long headPollJitterMillis;
    private final CompletableFuture<Void> initialReconciliation = new CompletableFuture<>();
    private final ScheduledExecutorService executor;
    private boolean watching;
    private boolean zooKeeperConnected = true;
    private long connectionEpoch;
    private long runningTaskEpoch = NO_RUNNING_TASK;
    private long preparedEventGeneration;
    private ScheduledFuture<?> pendingPreparedReconcile;
    private ScheduledFuture<?> pendingReconnectReconcile;
    private boolean reconnectPending;

    /**
     * Creates a watcher with custom reconciliation interval and both jitters.
     *
     * @param client Curator client connected to ZooKeeper
     * @param preparedPath absolute path of the prepared snapshot head
     * @param activatePath absolute path of the active snapshot head
     * @param reconciler reconciler that loads and activates snapshots
     * @param reconcileInterval interval for re-reading the active head; zero disables periodic reconciliation
     * @param snapshotSyncJitter maximum random delay for prepared-head and reconnect reconciliation
     * @param headPollJitter maximum random initial offset of the periodic head reconciliation
     */
    public ZooKeeperSubscriptionHeadWatcher(CuratorFramework client, String preparedPath, String activatePath,
                                            ZooKeeperSubscriptionHeadReconciler reconciler,
                                            Duration reconcileInterval, Duration snapshotSyncJitter,
                                            Duration headPollJitter) {
        this(client, preparedPath, activatePath, reconciler, reconcileInterval,
            snapshotSyncJitter, headPollJitter, newExecutor());
    }

    /**
     * Creates a watcher with an injected scheduler and explicit reconciliation intervals and jitters.
     *
     * @param client Curator client connected to ZooKeeper
     * @param preparedPath absolute path of the prepared snapshot head
     * @param activatePath absolute path of the active snapshot head
     * @param reconciler reconciler that loads and activates snapshots
     * @param reconcileInterval interval for re-reading the active head; zero disables periodic reconciliation
     * @param snapshotSyncJitter maximum random delay for prepared-head and reconnect reconciliation
     * @param headPollJitter maximum random initial offset of the periodic head reconciliation
     * @param executor scheduler used for watcher reconciliation tasks
     */
    ZooKeeperSubscriptionHeadWatcher(CuratorFramework client, String preparedPath, String activatePath,
                                     ZooKeeperSubscriptionHeadReconciler reconciler, Duration reconcileInterval,
                                     Duration snapshotSyncJitter, Duration headPollJitter,
                                     ScheduledExecutorService executor) {
        this.client = client;
        this.reconcileIntervalMillis = nonNegativeMillis(reconcileInterval, "reconcile interval");
        this.snapshotSyncJitterMillis = nonNegativeMillis(snapshotSyncJitter, "snapshot sync jitter");
        this.headPollJitterMillis = nonNegativeMillis(headPollJitter, "head poll jitter");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.preparedCache = CuratorCache.build(client, preparedPath);
        this.activateCache = CuratorCache.build(client, activatePath);
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler must not be null");
        reconciler.setActivationGate(this::commitIfCurrent);
        this.connectionStateListener = this::connectionStateChanged;
        preparedCache.listenable().addListener((type, oldData, data) ->
            preparedHeadChanged(data == null ? null : data.getData()));
        activateCache.listenable().addListener((type, oldData, data) -> scheduleActivateReconcile());
    }

    /**
     * Creates the single-threaded daemon scheduler used for serialized watcher work.
     *
     * @return a new scheduler named {@code subscription-head-watcher}
     */
    private static ScheduledExecutorService newExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "subscription-head-watcher");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts the ZooKeeper watches, schedules initial reconciliation, and enables periodic active-head checks.
     *
     * @throws IllegalStateException if this watcher has already been started
     */
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
                if (runIfConnected(epoch, reconciler)) {
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
            // The random first delay spreads periodic head reads of all pods; later runs keep the fixed interval.
            executor.scheduleWithFixedDelay(this::reconcilePeriodically,
                reconcileIntervalMillis + randomDelayMillis(headPollJitterMillis), reconcileIntervalMillis,
                TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Returns completion of the first active-head reconciliation performed after startup.
     *
     * @return a stage completed when initial reconciliation finishes, or exceptionally if it is interrupted
     */
    public CompletionStage<Void> initialReconciliation() {
        return initialReconciliation.minimalCompletionStage();
    }

    /**
     * Schedules immediate reconciliation after an ACTIVATE-head event.
     *
     * <p>A pending PREPARED reconciliation is cancelled because the active head takes precedence.</p>
     */
    synchronized void scheduleActivateReconcile() {
        if (watching && zooKeeperConnected) {
            cancelPendingPreparedReconcile();
            var epoch = connectionEpoch;
            cancelPendingReconnectReconcile();
            executor.execute(() -> {
                try {
                    runIfConnected(epoch, reconciler);
                } catch (RuntimeException exception) {
                    log.warn("ZooKeeper ACTIVATE reconciliation failed", exception);
                }
            });
        }
    }

    /**
     * Schedules processing of the latest PREPARED-head event after a randomized delay.
     *
     * @param data serialized PREPARED head, or {@code null} when the node was removed
     */
    void preparedHeadChanged(byte[] data) {
        synchronized (this) {
            if (!watching || !zooKeeperConnected) {
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

    /**
     * Processes a scheduled PREPARED event only if its connection epoch and event generation are still current.
     *
     * @param epoch connection epoch captured when the event was scheduled
     * @param generation PREPARED-event generation captured when the event was scheduled
     * @param data serialized PREPARED head, or {@code null} when the node was removed
     */
    private void runPreparedReconcileIfCurrent(long epoch, long generation, byte[] data) {
        synchronized (this) {
            if (!watching || !zooKeeperConnected || epoch != connectionEpoch || generation != preparedEventGeneration) {
                return;
            }
            pendingPreparedReconcile = null;
        }
        try {
            runIfConnected(epoch, () -> reconciler.preparePreparedEvent(data));
        } catch (RuntimeException exception) {
            log.warn("Jittered ZooKeeper PREPARED reconciliation failed", exception);
        }
    }

    /**
     * Invalidates and cancels any scheduled PREPARED-head reconciliation.
     */
    private void cancelPendingPreparedReconcile() {
        preparedEventGeneration++;
        if (pendingPreparedReconcile != null) {
            pendingPreparedReconcile.cancel(false);
            pendingPreparedReconcile = null;
        }
    }

    /**
     * Clears the reconnect-pending state and cancels its scheduled reconciliation, if present.
     */
    private void cancelPendingReconnectReconcile() {
        reconnectPending = false;
        if (pendingReconnectReconcile != null) {
            pendingReconnectReconcile.cancel(false);
            pendingReconnectReconcile = null;
        }
    }

    /**
     * Runs the delayed reconnect reconciliation if its connection epoch is still current.
     *
     * @param epoch connection epoch captured when the reconnect task was scheduled
     */
    private void runPendingReconnect(long epoch) {
        synchronized (this) {
            if (!reconnectPending || epoch != connectionEpoch) {
                return;
            }
            reconnectPending = false;
            pendingReconnectReconcile = null;
        }
        try {
            runIfConnected(epoch, reconciler);
        } catch (RuntimeException exception) {
            log.warn("ZooKeeper reconnect reconciliation failed", exception);
        }
    }

    /**
     * Returns a uniformly distributed delay between zero and the supplied maximum, inclusive when representable.
     *
     * @param maxDelayMillis maximum delay in milliseconds
     * @return randomized delay in milliseconds
     */
    private static long randomDelayMillis(long maxDelayMillis) {
        if (maxDelayMillis == 0) {
            return 0;
        }
        return maxDelayMillis == Long.MAX_VALUE
            ? ThreadLocalRandom.current().nextLong(maxDelayMillis)
            : ThreadLocalRandom.current().nextLong(maxDelayMillis + 1);
    }

    /**
    * Periodically re-reads the active ZooKeeper head while connected.
     */
    private void reconcilePeriodically() {
        long epoch;
        boolean zooKeeperConnected;
        synchronized (this) {
            if (!watching) {
                return;
            }
            epoch = connectionEpoch;
            zooKeeperConnected = this.zooKeeperConnected;
        }
        try {
            if (zooKeeperConnected) {
                runIfConnected(epoch, reconciler::reconcileActiveHead);
            }
        } catch (RuntimeException exception) {
            log.warn("Periodic subscription head reconciliation failed", exception);
        }
    }

    /**
     * Converts a non-negative duration to milliseconds for scheduling.
     *
     * @param interval duration to convert
     * @param name setting name used in validation errors
     * @return duration in milliseconds, with zero preserved
     * @throws IllegalArgumentException if the duration is null, negative, sub-millisecond, or out of range
     */
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

    /**
     * Runs a reconciliation task only while the watcher is connected in the supplied epoch.
     *
     * @param epoch connection epoch captured when the task was scheduled
     * @param task reconciliation task to run
     * @return {@code true} if the task ran and the watcher remains connected in the same epoch
     */
    private boolean runIfConnected(long epoch, Runnable task) {
        synchronized (this) {
            if (!watching || !zooKeeperConnected || epoch != connectionEpoch) {
                return false;
            }
            runningTaskEpoch = epoch;
        }
        try {
            task.run();
        } finally {
            synchronized (this) {
                runningTaskEpoch = NO_RUNNING_TASK;
            }
        }
        synchronized (this) {
            return watching && zooKeeperConnected && epoch == connectionEpoch;
        }
    }

    /**
     * Commits a reconciler cache change only while the running task's connection epoch is still current.
     *
     * <p>Runs under this monitor, so a concurrent {@code SUSPENDED}/{@code LOST} is applied either before the check or
     * after the commit.</p>
     *
     * @param commit cache change to apply
     * @return {@code true} if the commit ran
     */
    private synchronized boolean commitIfCurrent(Runnable commit) {
        if (!watching || !zooKeeperConnected || runningTaskEpoch != connectionEpoch) {
            return false;
        }
        commit.run();
        return true;
    }

    /**
     * Updates watcher connectivity for Curator connection transitions.
     *
     * @param ignored Curator client supplied by the listener API
     * @param state new ZooKeeper connection state
     */
    void connectionStateChanged(CuratorFramework ignored, ConnectionState state) {
        synchronized (this) {
            if (!watching) {
                return;
            }
            if (state == ConnectionState.SUSPENDED || state == ConnectionState.LOST) {
                cancelPendingPreparedReconcile();
                cancelPendingReconnectReconcile();
                var wasConnected = zooKeeperConnected;
                zooKeeperConnected = false;
                connectionEpoch++;
                if (wasConnected) {
                    reconciler.disconnected();
                }
            } else if (state == ConnectionState.RECONNECTED
                    // Curator reports CONNECTED, not RECONNECTED, for the first connection after a failed startup.
                    || state == ConnectionState.CONNECTED && !zooKeeperConnected) {
                cancelPendingPreparedReconcile();
                cancelPendingReconnectReconcile();
                zooKeeperConnected = true;
                var epoch = ++connectionEpoch;
                reconnectPending = true;
                pendingReconnectReconcile = executor.schedule(
                    () -> runPendingReconnect(epoch), randomDelayMillis(snapshotSyncJitterMillis),
                    TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * Stops scheduled work, removes the connection listener, and closes both ZooKeeper caches.
     */
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
        // Outside the monitor: running tasks may need it to finish.
        awaitExecutorTermination();
        preparedCache.close();
        activateCache.close();
    }

    private void awaitExecutorTermination() {
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Subscription head watcher tasks did not stop within 5 seconds");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}