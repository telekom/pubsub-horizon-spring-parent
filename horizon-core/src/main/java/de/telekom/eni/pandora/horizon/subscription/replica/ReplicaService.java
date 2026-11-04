// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.subscription.replica;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.mongo.repository.SnapshotMongoRepo;
import de.telekom.eni.pandora.horizon.subscription.snapshot.Head;
import de.telekom.eni.pandora.horizon.subscription.snapshot.IndexedMap;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.data.Stat;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@Slf4j
public class ReplicaService {

    private final CuratorFramework curatorFramework;

    private final ReplicaMaps replicaMaps;

    private final SnapshotMongoRepo snapshotMongoRepo;

    private final ObjectMapper objectMapper;

    public ReplicaService(
            CuratorFramework curatorFramework,
            ReplicaMaps replicaMaps,
            SnapshotMongoRepo snapshotMongoRepo
    ) {
        this.curatorFramework = curatorFramework;
        this.replicaMaps = replicaMaps;
        this.snapshotMongoRepo = snapshotMongoRepo;
        this.objectMapper = new ObjectMapper();
    }

    private byte[] getAndWatch(Stat stat, String zNode, CuratorWatcher watcher) throws Exception {
        return curatorFramework.getData().storingStatIn(stat).usingWatcher(watcher).forPath(zNode);
    }

    public ReplicaMaps initMaps() throws Exception {
        var prepareStat = new Stat();
        var prepareBytes = getAndWatch(prepareStat, Head.ZNODE_PREPARED, this::watchPrepared);
        actOnPrepare(prepareBytes, prepareStat.getMzxid());

        var activeStat = new Stat();
        var activeBytes = getAndWatch(activeStat, Head.ZNODE_ACTIVATED, this::watchActive);
        actOnActive(activeBytes, activeStat.getMzxid());

        log.info("initialized subscription replica-maps");
        return replicaMaps;
    }

    private void watchPrepared(WatchedEvent event) throws Exception {
        var prepareStat = new Stat();
        var prepareBytes = getAndWatch(prepareStat, Head.ZNODE_PREPARED, this::watchPrepared);

        actOnPrepare(prepareBytes, prepareStat.getMzxid());
    }

    private void watchActive(WatchedEvent event) throws Exception {
        var activeStat = new Stat();
        var activeBytes = getAndWatch(activeStat, Head.ZNODE_ACTIVATED, this::watchActive);

        actOnActive(activeBytes, activeStat.getMzxid());
    }

    private void actOnPrepare(byte[] bytes, long mzxid) {
        try {
            var head = objectMapper.readValue(bytes, Head.class);
            var docs = snapshotMongoRepo.findBySnapshotId(head.getSnapshotId());

            var indexedMap = new IndexedMap(docs, mzxid, head.getSnapshotObjectId());
            log.info("preparing mzxid {}", mzxid);
            replicaMaps.prepare(indexedMap);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void actOnActive(byte[] bytes, long mzxid) {
        try {
            var head = objectMapper.readValue(bytes, Head.class);
            log.info("activating mzxid {}", mzxid);
            if (head.getSnapshotObjectId().equals(replicaMaps.getPrepared().get().getSnapshotId())) {
                replicaMaps.activate();
            } else {
                var docs = snapshotMongoRepo.findBySnapshotId(head.getSnapshotId());

                var indexedMap = new IndexedMap(docs, mzxid, head.getSnapshotObjectId());
                replicaMaps.activate(indexedMap);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
