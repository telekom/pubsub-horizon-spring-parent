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

/**
 * Reads and parses the PREPARED and ACTIVATE snapshot heads from their configured ZooKeeper paths.
 *
 * <p>ZooKeeper I/O is performed through Curator; head validation is delegated to
 * {@link ZooKeeperSubscriptionSnapshotHeadParser}.</p>
 */
public class ZooKeeperSubscriptionSnapshotHeadReader {

    private final CuratorFramework client;
    private final ZooKeeperSubscriptionSnapshotHeadParser parser;
    private final String preparedPath;
    private final String activatePath;

    /**
     * Creates a reader for the configured PREPARED and ACTIVATE paths.
     *
     * @param client Curator client used to read ZooKeeper data
     * @param objectMapper mapper used by the head parser
     * @param preparedPath absolute ZooKeeper path containing the PREPARED head
     * @param activatePath absolute ZooKeeper path containing the authoritative ACTIVATE head
     */
    public ZooKeeperSubscriptionSnapshotHeadReader(CuratorFramework client, ObjectMapper objectMapper,
                                                    String preparedPath, String activatePath) {
        this.client = client;
        this.parser = new ZooKeeperSubscriptionSnapshotHeadParser(objectMapper);
        this.preparedPath = preparedPath;
        this.activatePath = activatePath;
    }

    /**
     * Reads and validates the current PREPARED head.
     *
     * @return the parsed head, or empty if the PREPARED node does not exist
     * @throws SubscriptionCacheSnapshotException if the node contains an invalid head payload
     * @throws IllegalStateException if the ZooKeeper read fails, times out, or is interrupted
     */
    public Optional<SubscriptionSnapshotHead> readPrepared() {
        return read(preparedPath);
    }

    /**
     * Reads and validates the current authoritative ACTIVATE head.
     *
     * @return the parsed head, or empty if the ACTIVATE node does not exist
     * @throws SubscriptionCacheSnapshotException if the node contains an invalid head payload
     * @throws IllegalStateException if the ZooKeeper read fails, times out, or is interrupted
     */
    public Optional<SubscriptionSnapshotHead> readActivate() {
        return read(activatePath);
    }

    /**
     * Parses a PREPARED watch-event payload without issuing another ZooKeeper read.
     *
     * @param data serialized PREPARED head, or {@code null} when the node was deleted
     * @return the parsed head, or empty when {@code data} is {@code null}
     * @throws SubscriptionCacheSnapshotException if a non-null payload is invalid
     */
    public Optional<SubscriptionSnapshotHead> parsePreparedEvent(byte[] data) {
        return data == null ? Optional.empty() : Optional.of(parser.parse(data));
    }

    /**
     * Reads a ZooKeeper path asynchronously and bounds the wait by Curator's connection timeout.
     *
     * <p>A missing node is not an error. Interrupted reads restore the thread's interrupt status before returning an
     * exception to the caller.</p>
     */
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