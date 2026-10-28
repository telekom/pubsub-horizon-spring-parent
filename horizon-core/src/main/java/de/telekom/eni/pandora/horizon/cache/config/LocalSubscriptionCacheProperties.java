package de.telekom.eni.pandora.horizon.cache.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Getter
@Setter
public class LocalSubscriptionCacheProperties {

    private boolean enabled = false;

    // NONE never reads Hazelcast and serves stale local entries indefinitely if necessary.
    private Fallback fallbackMode = Fallback.HAZELCAST_WITH_MONGO_FALLBACK;

    private String snapshotCollection = "subscriptions.subscriber.horizon.telekom.de.v1-snapshots";

    // Server-side limit (maxTimeMS) for a snapshot load; unresponsive connections need socketTimeoutMS in the URL.
    private Duration mongoLoadTimeout = Duration.ofSeconds(60);

    // Only applies to HAZELCAST_WITH_MONGO_FALLBACK.
    private Duration staleLocalCacheReadGracePeriod = Duration.ofSeconds(300);

    // Only applies to HAZELCAST_WITH_MONGO_FALLBACK; NONE always requires a local cache at startup.
    private boolean requireLocalCacheAtStartup = true;

    // Only applies when startup waits; expiry fails startup, zero waits indefinitely.
    private Duration initialSnapshotTimeout = Duration.ofSeconds(15);

    // Re-check of the ZooKeeper activated head. Zero disables it.
    private Duration reconcileInterval = Duration.ofSeconds(60);

    // Random initial offset of the periodic activated-head check.
    private Duration headCheckJitter = Duration.ofSeconds(10);

    // Maximum random delay before loading a snapshot for prepared preloads and reconnects.
    private Duration snapshotSyncJitter = Duration.ofSeconds(10);

    // ZooKeeper related settings
    private ZooKeeperProperties zooKeeper = new ZooKeeperProperties();

    /** Selects the read source used when the local snapshot cannot serve requests. */
    public enum Fallback {
        HAZELCAST_WITH_MONGO_FALLBACK,
        NONE
    }

}
