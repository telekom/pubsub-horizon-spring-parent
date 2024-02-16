package de.telekom.eni.pandora.horizon.kubernetes;

import de.telekom.eni.pandora.horizon.kubernetes.util.InformerWrapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class InformerStoreInitHandler {

    private static final int CACHE_SYNC_PHASE_TIMEOUT_MINUTES = 30;

    private final KubernetesClientWrapper kubernetesClientWrapper;

    private final AtomicBoolean fullySynced = new AtomicBoolean(false);

    @Getter
    private ConcurrentHashMap<String, Integer> initalSyncedStats = new ConcurrentHashMap<>();

    public InformerStoreInitHandler(KubernetesClientWrapper kubernetesClientWrapper) {
        this.kubernetesClientWrapper = kubernetesClientWrapper;
    }

    public void handle(List<InformerWrapper> informers) {
        final ConcurrentLinkedQueue<InformerWrapper> queue = new ConcurrentLinkedQueue<>(informers);

        new Thread(() -> {
            final int waitTimeMs = 1000;
            var waitedMs = 0;

            while (!queue.isEmpty()) {
                if (waitedMs >= CACHE_SYNC_PHASE_TIMEOUT_MINUTES * 60 * 1000) {
                    log.info("Cache syncing phase takes longer than {} minutes, we assume it's fully synced.", CACHE_SYNC_PHASE_TIMEOUT_MINUTES);
                    break;
                }

                var informer = queue.poll();

                if (!sync(informer)) {
                    queue.add(informer);
                }

                try {
                    Thread.sleep(waitTimeMs);
                } catch (InterruptedException ignored) {
                    break;
                }

                waitedMs += waitTimeMs;
            }

            if (!Thread.interrupted()) {
                fullySynced.compareAndSet(false, true);
                log.info("Cache syncing phase ended.");
            }
        }).start();
    }

    public boolean isFullySynced() {
        return fullySynced.get();
    }

    private boolean sync(InformerWrapper informer) {
        log.debug("Syncing {}", informer.getInformer().getApiTypeClass().getSimpleName());

        try {
            var items = kubernetesClientWrapper.get(informer.getInformer().getApiTypeClass(), informer.getNamespace());

            Optional.ofNullable(informer.getEventHandler()).ifPresent(i -> {
                i.addAll(items);
            });

            log.info("Synced {}", informer.getInformer().getApiTypeClass().getSimpleName());
        } catch (Exception e) {
            log.error(e.getMessage());

            return false;
        }

        return true;
    }
}
