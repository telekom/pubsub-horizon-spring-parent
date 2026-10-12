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
import java.util.function.Consumer;

/**
 * Watches ZooKeeper subscription heads and schedules serialized reconciliation work for one pod.
 *
 * <p>The watcher owns event timing and connection transitions; head decisions and cache changes are delegated to the
 * reconciler.</p>
 */
@Slf4j
public class ZooKeeperSubscriptionHeadWatcher implements AutoCloseable {

    private final CuratorCache preparedCache;
    private final CuratorCache activatedCache;
    private final ZooKeeperSubscriptionHeadReconciler reconciler;
    private final CuratorFramework client;
    private final ConnectionStateListener connectionStateListener;
    private final long reconcileIntervalMillis;
    private final long snapshotSyncJitterMillis;
    private final long headCheckJitterMillis;
    private final CompletableFuture<Void> initialReconciliation = new CompletableFuture<>();
    private final ScheduledExecutorService executor;
    private final LatestScheduledTask preparedReconcile = new LatestScheduledTask();
    private final LatestScheduledTask reconnectReconcile = new LatestScheduledTask();
    private boolean watching;
    private boolean zooKeeperConnected;
    private long connectionEpoch;

    /**
     * Keeps at most one pending reconciliation. Replacing delayed work coalesces rapid head updates so only the latest
     * scheduled run is needed; a generation check invalidates superseded tasks.
     */
    private final class LatestScheduledTask {
        private long generation;
        private ScheduledFuture<?> future;

        /** Replaces any pending task; must be called under the watcher monitor. */
        void schedule(Runnable task, long delayMillis) {
            cancel();
            var scheduledGeneration = generation;
            future = executor.schedule(() -> {
                synchronized (ZooKeeperSubscriptionHeadWatcher.this) {
                    if (scheduledGeneration != generation) {
                        return;
                    }
                    future = null;
                }
                task.run();
            }, delayMillis, TimeUnit.MILLISECONDS);
        }

        /** Invalidates and cancels the pending task; must be called under the watcher monitor. */
        void cancel() {
            generation++;
            if (future != null) {
                future.cancel(false);
                future = null;
            }
        }
    }

    /**
     * Creates a watcher with custom reconciliation interval and both jitters.
     *
     * @param client Curator client connected to ZooKeeper
     * @param preparedPath absolute path of the prepared snapshot head
     * @param activatedPath absolute path of the activated snapshot head
     * @param reconciler reconciler that loads and activates snapshots
     * @param reconcileInterval interval for re-reading the activated head; zero disables periodic reconciliation
     * @param snapshotSyncJitter maximum random delay for prepared-head and reconnect reconciliation
     * @param headCheckJitter maximum random initial offset of the periodic activated-head check
     */
    public ZooKeeperSubscriptionHeadWatcher(CuratorFramework client, String preparedPath, String activatedPath,
                                            ZooKeeperSubscriptionHeadReconciler reconciler,
                                            Duration reconcileInterval, Duration snapshotSyncJitter,
                                            Duration headCheckJitter) {
        this(client, preparedPath, activatedPath, reconciler, reconcileInterval,
            snapshotSyncJitter, headCheckJitter, newExecutor());
    }

    /**
     * Creates a watcher with an injected scheduler and explicit reconciliation intervals and jitters.
     *
     * @param client Curator client connected to ZooKeeper
     * @param preparedPath absolute path of the prepared snapshot head
     * @param activatedPath absolute path of the activated snapshot head
     * @param reconciler reconciler that loads and activates snapshots
     * @param reconcileInterval interval for re-reading the activated head; zero disables periodic reconciliation
     * @param snapshotSyncJitter maximum random delay for prepared-head and reconnect reconciliation
     * @param headCheckJitter maximum random initial offset of the periodic activated-head check
     * @param executor scheduler used for watcher reconciliation tasks
     */
    ZooKeeperSubscriptionHeadWatcher(CuratorFramework client, String preparedPath, String activatedPath,
                                     ZooKeeperSubscriptionHeadReconciler reconciler, Duration reconcileInterval,
                                     Duration snapshotSyncJitter, Duration headCheckJitter,
                                     ScheduledExecutorService executor) {
        this.client = client;
        this.reconcileIntervalMillis = nonNegativeMillis(reconcileInterval, "reconcile interval");
        this.snapshotSyncJitterMillis = nonNegativeMillis(snapshotSyncJitter, "snapshot sync jitter");
        this.headCheckJitterMillis = nonNegativeMillis(headCheckJitter, "head check jitter");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.preparedCache = CuratorCache.build(client, preparedPath);
        this.activatedCache = CuratorCache.build(client, activatedPath);
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler must not be null");
        this.connectionStateListener = this::onConnectionStateChanged;
        preparedCache.listenable().addListener((type, oldData, data) ->
            onPreparedHeadChanged(data == null ? null : data.getData()));
        activatedCache.listenable().addListener((type, oldData, data) -> onActivatedHeadChanged());
    }

    /**
     * Starts the ZooKeeper watches, schedules initial reconciliation, and enables periodic activated-head checks.
     *
     * <p>Without a ZooKeeper connection, the initial reconciliation runs on the first {@code CONNECTED} event.</p>
     *
     * @throws IllegalStateException if this watcher has already been started
     */
    public synchronized void start() {
        if (watching) {
            throw new IllegalStateException("Subscription head watcher already started");
        }
        client.getConnectionStateListenable().addListener(connectionStateListener);
        preparedCache.start();
        activatedCache.start();
        watching = true;
        zooKeeperConnected = client.getZookeeperClient().isConnected();
        if (zooKeeperConnected) {
            var epoch = connectionEpoch;
            executor.execute(() -> runInitialReconciliation(epoch));
        }
        if (reconcileIntervalMillis > 0) {
            // The random first delay spreads periodic head reads of all pods; later runs keep the fixed interval.
            executor.scheduleWithFixedDelay(this::reconcileActivatedHeadPeriodically,
                reconcileIntervalMillis + randomDelayMillis(headCheckJitterMillis), reconcileIntervalMillis,
                TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Returns completion of the first head reconciliation performed after startup.
     *
     * @return a stage completed when initial reconciliation finishes, or exceptionally if it is interrupted
     */
    public CompletionStage<Void> getInitialReconciliation() {
        return initialReconciliation.minimalCompletionStage();
    }

    /**
     * Schedules immediate reconciliation after an ACTIVATED-head event.
     *
     * <p>Pending PREPARED and reconnect reconciliations are cancelled because this run rereads both heads.</p>
     */
    synchronized void onActivatedHeadChanged() {
        if (watching && zooKeeperConnected) {
            preparedReconcile.cancel();
            reconnectReconcile.cancel();
            var epoch = connectionEpoch;
            executor.execute(() -> reconcileAllHeads(epoch, "ZooKeeper ACTIVATED reconciliation failed"));
        }
    }

    /**
     * Schedules processing of the latest PREPARED-head event after a randomized delay.
     *
     * @param data serialized PREPARED head, or {@code null} when the node was removed
     */
    synchronized void onPreparedHeadChanged(byte[] data) {
        if (!watching || !zooKeeperConnected) {
            return;
        }
        var epoch = connectionEpoch;
        var eventData = data == null ? null : data.clone();
        preparedReconcile.schedule(() -> {
            try {
                tryRunInEpoch(epoch, gate -> reconciler.reconcilePreparedHead(eventData));
            } catch (RuntimeException exception) {
                log.warn("Jittered ZooKeeper PREPARED reconciliation failed", exception);
            }
        }, randomDelayMillis(snapshotSyncJitterMillis));
    }

    /**
     * Updates watcher connectivity for Curator connection transitions.
     *
     * @param ignored Curator client supplied by the listener API
     * @param state new ZooKeeper connection state
     */
    void onConnectionStateChanged(CuratorFramework ignored, ConnectionState state) {
        synchronized (this) {
            if (!watching) {
                return;
            }
            if (state == ConnectionState.SUSPENDED || state == ConnectionState.LOST) {
                // Discard delayed work and invalidate in-flight work from the connection being lost.
                preparedReconcile.cancel();
                reconnectReconcile.cancel();
                var wasConnected = zooKeeperConnected;
                zooKeeperConnected = false;
                connectionEpoch++;
                if (wasConnected) {
                    // Without a confirmed ZooKeeper connection, the cached expected head is no longer trusted.
                    reconciler.onConnectionLost();
                }
            } else if (state == ConnectionState.RECONNECTED
                    // Curator reports CONNECTED, not RECONNECTED, for the first connection after a disconnected start.
                    || state == ConnectionState.CONNECTED && !zooKeeperConnected) {
                // The reconnect reconciliation rereads both heads, making any queued PREPARED payload obsolete.
                preparedReconcile.cancel();
                zooKeeperConnected = true;
                var epoch = ++connectionEpoch;
                // Reconcile immediately the first time; jitter later reconnects to spread load across pods.
                var delayMillis = initialReconciliation.isDone() ? randomDelayMillis(snapshotSyncJitterMillis) : 0;
                reconnectReconcile.schedule(
                    () -> reconcileAllHeads(epoch, "ZooKeeper reconnect reconciliation failed"), delayMillis);
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
            preparedReconcile.cancel();
            reconnectReconcile.cancel();
            initialReconciliation.completeExceptionally(
                new IllegalStateException("Subscription head watcher closed before initial reconciliation"));
            client.getConnectionStateListenable().removeListener(connectionStateListener);
            executor.shutdownNow();
        }
        // Outside the monitor: running tasks may need it to finish.
        awaitExecutorTermination();
        preparedCache.close();
        activatedCache.close();
    }

    /**
     * Creates a named daemon scheduler that runs watcher work sequentially, preventing reconciliations from overlapping.
     * The daemon thread does not keep the JVM alive during shutdown.
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

    /** Runs the initial head reconciliation and completes its startup stage. */
    private void runInitialReconciliation(long epoch) {
        try {
            if (tryRunInEpoch(epoch, reconciler::reconcilePreparedAndActivatedHeads)) {
                initialReconciliation.complete(null);
            } else {
                initialReconciliation.completeExceptionally(
                    new IllegalStateException("Initial subscription head reconciliation was interrupted"));
            }
        } catch (RuntimeException | Error exception) {
            initialReconciliation.completeExceptionally(exception);
            throw exception;
        }
    }

    /**
     * Reconciles both heads and completes the initial reconciliation, if still pending.
     *
     * @param epoch connection epoch captured when the task was scheduled
     * @param failureMessage log message for an unexpected failure
     */
    private void reconcileAllHeads(long epoch, String failureMessage) {
        try {
            if (tryRunInEpoch(epoch, reconciler::reconcilePreparedAndActivatedHeads)) {
                initialReconciliation.complete(null);
            }
        } catch (RuntimeException exception) {
            log.warn(failureMessage, exception);
        }
    }

    /**
     * Periodically re-reads the activated ZooKeeper head while connected.
     */
    private void reconcileActivatedHeadPeriodically() {
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
                tryRunInEpoch(epoch, reconciler::reconcileActivatedHead);
            }
        } catch (RuntimeException exception) {
            log.warn("Periodic subscription head reconciliation failed", exception);
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
     * Runs a reconciliation task if the supplied connection epoch is still current, passing it a commit gate bound to
     * that epoch.
     *
     * @param epoch connection epoch captured when the task was scheduled
     * @param task reconciliation task receiving the epoch-bound commit gate
     * @return {@code true} if the task ran and its epoch is still current
     */
    private boolean tryRunInEpoch(long epoch, Consumer<ZooKeeperSubscriptionHeadReconciler.CacheCommitGate> task) {
        synchronized (this) {
            if (!isCurrent(epoch)) {
                return false;
            }
        }
        task.accept(commit -> commitIfEpochUnchanged(epoch, commit));
        synchronized (this) {
            return isCurrent(epoch);
        }
    }

    /**
     * Commits a reconciler cache change only while the supplied connection epoch is still current.
     *
     * <p>Runs under this monitor, so a concurrent {@code SUSPENDED}/{@code LOST} is applied either before the check or
     * after the commit.</p>
     *
     * @param epoch connection epoch of the running task
     * @param commit cache change to apply
     * @return {@code true} if the commit ran
     */
    private synchronized boolean commitIfEpochUnchanged(long epoch, Runnable commit) {
        if (!isCurrent(epoch)) {
            return false;
        }
        commit.run();
        return true;
    }

    /** Returns whether this watcher is connected and the supplied connection epoch is still current. */
    private boolean isCurrent(long epoch) {
        return watching && zooKeeperConnected && epoch == connectionEpoch;
    }

    /** Waits briefly for scheduler shutdown and preserves interruption. */
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