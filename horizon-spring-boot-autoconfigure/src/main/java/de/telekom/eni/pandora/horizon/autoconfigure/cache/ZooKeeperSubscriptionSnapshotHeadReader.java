package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.cache.service.ZooKeeperSubscriptionSnapshotHeadParser;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.CuratorEvent;
import org.apache.zookeeper.KeeperException;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ZooKeeperSubscriptionSnapshotHeadReader {

    private final CuratorFramework client;
    private final ZooKeeperSubscriptionSnapshotHeadParser parser;
    private final String preparedPath;
    private final String activatePath;

    public ZooKeeperSubscriptionSnapshotHeadReader(CuratorFramework client, ObjectMapper objectMapper,
                                                    String preparedPath, String activatePath) {
        this.client = client;
        this.parser = new ZooKeeperSubscriptionSnapshotHeadParser(objectMapper);
        this.preparedPath = preparedPath;
        this.activatePath = activatePath;
    }

    public Optional<SubscriptionSnapshotHead> readPrepared() {
        return read(preparedPath);
    }

    public Optional<SubscriptionSnapshotHead> readActivate() {
        return read(activatePath);
    }

    public Optional<SubscriptionSnapshotHead> parsePreparedEvent(byte[] data) {
        return data == null ? Optional.empty() : Optional.of(parser.parse(data));
    }

    // Bounded by the connection timeout: a blocking read can hang for the session read timeout plus Curator retries.
    private Optional<SubscriptionSnapshotHead> read(String path) {
        var readTimeoutMs = client.getZookeeperClient().getConnectionTimeoutMs();
        var result = new CompletableFuture<CuratorEvent>();
        try {
            client.getData().inBackground((ignored, event) -> result.complete(event)).forPath(path);
            var event = result.get(readTimeoutMs, TimeUnit.MILLISECONDS);
            var code = KeeperException.Code.get(event.getResultCode());
            if (code == KeeperException.Code.NONODE) {
                return Optional.empty();
            }
            if (code != KeeperException.Code.OK) {
                throw KeeperException.create(code, path);
            }
            return Optional.of(parser.parse(event.getData()));
        } catch (SubscriptionCacheSnapshotException exception) {
            throw exception;
        } catch (TimeoutException exception) {
            throw new IllegalStateException("Timed out after " + readTimeoutMs
                + " ms reading ZooKeeper subscription snapshot head at " + path, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading ZooKeeper subscription snapshot head at " + path,
                exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read ZooKeeper subscription snapshot head at " + path, exception);
        }
    }
}