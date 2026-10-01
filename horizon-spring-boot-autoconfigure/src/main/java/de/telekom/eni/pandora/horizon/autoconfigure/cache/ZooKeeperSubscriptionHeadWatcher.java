package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ZooKeeperSubscriptionHeadWatcher implements AutoCloseable {

    private final CuratorCache preparedCache;
    private final CuratorCache activateCache;
    private final Runnable reconcile;
    private final Runnable invalidate;
    private final Runnable reconnect;
    private final CuratorFramework client;
    private final ConnectionStateListener connectionStateListener;
    private final CompletableFuture<Void> initialReconciliation = new CompletableFuture<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "subscription-head-watcher");
        thread.setDaemon(true);
        return thread;
    });
    private boolean watching;
    private boolean connected = true;
    private long connectionEpoch;

    public ZooKeeperSubscriptionHeadWatcher(CuratorFramework client, String preparedPath, String activatePath,
                                            ZooKeeperSubscriptionHeadReconciler reconciler) {
        this(client, preparedPath, activatePath, reconciler, reconciler::suspended,
            reconciler::reconcileAfterReconnect);
    }

    private ZooKeeperSubscriptionHeadWatcher(CuratorFramework client, String preparedPath, String activatePath,
                                             Runnable reconcile, Runnable invalidate, Runnable reconnect) {
        this.client = client;
        this.preparedCache = CuratorCache.build(client, preparedPath);
        this.activateCache = CuratorCache.build(client, activatePath);
        this.reconcile = reconcile;
        this.invalidate = invalidate;
        this.reconnect = reconnect;
        this.connectionStateListener = this::connectionStateChanged;
        preparedCache.listenable().addListener((type, oldData, data) -> scheduleReconcile());
        activateCache.listenable().addListener((type, oldData, data) -> scheduleReconcile());
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

    private boolean runIfConnected(long epoch, Runnable task) {
        synchronized (this) {
            if (!watching || !connected || epoch != connectionEpoch) {
                return false;
            }
        }
        try {
            task.run();
        } finally {
            synchronized (this) {
                if (!watching || !connected || epoch != connectionEpoch) {
                    invalidate.run();
                }
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
            if (state == ConnectionState.SUSPENDED || state == ConnectionState.LOST) {
                connected = false;
                connectionEpoch++;
                invalidate.run();
            } else if (state == ConnectionState.RECONNECTED) {
                connected = true;
                var epoch = ++connectionEpoch;
                invalidate.run();
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