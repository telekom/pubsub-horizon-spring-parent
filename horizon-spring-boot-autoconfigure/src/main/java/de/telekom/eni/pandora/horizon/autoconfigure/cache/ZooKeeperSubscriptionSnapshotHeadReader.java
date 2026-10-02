package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.cache.service.ZooKeeperSubscriptionSnapshotHeadParser;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheSnapshotException;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionSnapshotHead;
import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.KeeperException;

import java.util.Optional;

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

    private Optional<SubscriptionSnapshotHead> read(String path) {
        try {
            return Optional.of(parser.parse(client.getData().forPath(path)));
        } catch (KeeperException.NoNodeException exception) {
            return Optional.empty();
        } catch (SubscriptionCacheSnapshotException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read ZooKeeper subscription snapshot head at " + path, exception);
        }
    }
}