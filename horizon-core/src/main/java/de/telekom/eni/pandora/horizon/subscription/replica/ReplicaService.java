// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.subscription.replica;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.mongo.model.SnapshotMongoDocument;
import de.telekom.eni.pandora.horizon.mongo.repository.SnapshotMongoRepo;
import de.telekom.eni.pandora.horizon.subscription.SubscriptionRepositoryManager;
import de.telekom.eni.pandora.horizon.subscription.snapshot.SnapshotId;
import de.telekom.eni.pandora.horizon.subscription.snapshot.IndexedMap;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.Stat;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

@Slf4j
public class ReplicaService {

    private final CuratorFramework curatorFramework;

    private final ReplicaMaps replicaMaps;

    private final SnapshotMongoRepo snapshotMongoRepo;

    private final ObjectMapper objectMapper;

    private final SubscriptionRepositoryManager subscriptionRepositoryManager;

    public ReplicaService(
            CuratorFramework curatorFramework,
            ReplicaMaps replicaMaps,
            SnapshotMongoRepo snapshotMongoRepo, SubscriptionRepositoryManager subscriptionRepositoryManager
    ) {
        this.curatorFramework = curatorFramework;
        this.replicaMaps = replicaMaps;
        this.snapshotMongoRepo = snapshotMongoRepo;
        this.subscriptionRepositoryManager = subscriptionRepositoryManager;
        this.objectMapper = new ObjectMapper();
    }

    private byte[] getAndWatch(Stat stat, String zNode, CuratorWatcher watcher) throws Exception {
        return curatorFramework.getData().storingStatIn(stat).usingWatcher(watcher).forPath(zNode);
    }

    public ReplicaMaps initMaps() throws Exception {
        var prepareStat = new Stat();
        var prepareBytes = getAndWatch(prepareStat, SnapshotId.ZNODE_PREPARED, this::watchPrepared);
        var prepareSnapshotId = objectMapper.readValue(prepareBytes, SnapshotId.class);
        prepare(prepareSnapshotId, prepareStat.getMzxid());

        var activeStat = new Stat();
        var activeBytes = getAndWatch(activeStat, SnapshotId.ZNODE_ACTIVATED, this::watchActivated);
        var activeSnapshotId = objectMapper.readValue(activeBytes, SnapshotId.class);
        activate(activeSnapshotId, activeStat.getMzxid());

        log.info("initialized subscription replica-maps");
        return replicaMaps;
    }

    private void watchPrepared(WatchedEvent event) throws Exception {
        if (event.getType() == Watcher.Event.EventType.NodeDataChanged) {
            var prepareStat = new Stat();
            var prepareBytes = getAndWatch(prepareStat, SnapshotId.ZNODE_PREPARED, this::watchPrepared);
            final SnapshotId snapshotId;
            try {
                snapshotId = objectMapper.readValue(prepareBytes, SnapshotId.class);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            prepare(snapshotId, prepareStat.getMzxid());
        }
    }

    private void watchActivated(WatchedEvent event) throws Exception {
        if (event.getType() == Watcher.Event.EventType.NodeDataChanged) {
            var activeStat = new Stat();
            var activeBytes = getAndWatch(activeStat, SnapshotId.ZNODE_ACTIVATED, this::watchActivated);
            final SnapshotId snapshotId;
            try {
                snapshotId = objectMapper.readValue(activeBytes, SnapshotId.class);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            activate(snapshotId, activeStat.getMzxid());
        }
    }

    private void prepare(SnapshotId snapshotId, long mzxid) {
        final List<SnapshotMongoDocument> docs;
        try {
            docs = snapshotMongoRepo.findBySnapshotId(snapshotId.getValue().toHexString());
        } catch (DataAccessResourceFailureException | TransientDataAccessResourceException ex) { //TODO: prove the exact exceptions
            // TODO: handle MongoDB exception
            subscriptionRepositoryManager.switchToHazelcast();
            return;
        }

        var indexedMap = new IndexedMap(docs, snapshotId);
        log.info("preparing mzxid {}", mzxid);
        replicaMaps.prepare(indexedMap);
    }

    private void activate(SnapshotId snapshotId, long mzxid) {
        log.info("activating mzxid {}", mzxid);
        if (snapshotId.compareTo(replicaMaps.getPrepared().get().getSnapshotId()) == 0) {
            replicaMaps.activate();
        } else {
            prepare(snapshotId, mzxid);
            replicaMaps.activate();
        }
    }
}
