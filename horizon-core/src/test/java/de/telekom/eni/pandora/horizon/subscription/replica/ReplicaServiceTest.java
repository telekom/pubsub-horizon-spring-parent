// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.subscription.replica;

import de.telekom.eni.pandora.horizon.kubernetes.resource.Subscription;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResourceSpec;
import de.telekom.eni.pandora.horizon.mongo.model.SnapshotMongoDocument;
import de.telekom.eni.pandora.horizon.mongo.repository.SnapshotMongoRepo;
import de.telekom.eni.pandora.horizon.subscription.snapshot.Head;
import de.telekom.eni.pandora.horizon.subscription.snapshot.IndexedMap;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReplicaServiceTest {

    private final String snapshotA = new ObjectId().toHexString();
    private final String snapshotB = new ObjectId().toHexString();
    private final SnapshotMongoDocument docA = document("sub-a");
    private final SnapshotMongoDocument docB = document("sub-b");

    private TestingServer server;
    private CuratorFramework client;
    private SnapshotMongoRepo repo;
    private ReplicaConnectionListener listener;
    private ReplicaService service;

    @BeforeEach
    void setUp() throws Exception {
        server = new TestingServer(true);
        client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100));
        client.start();

        repo = mock(SnapshotMongoRepo.class);
        when(repo.findBySnapshotId(snapshotA)).thenReturn(List.of(docA));
        when(repo.findBySnapshotId(snapshotB)).thenReturn(List.of(docB));

        listener = mock(ReplicaConnectionListener.class);
        service = new ReplicaService(client, new ReplicaMaps(), repo, listener);
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
        server.close();
    }

    @Test
    void registersConnectionListener() {
        var curator = mock(CuratorFramework.class, RETURNS_DEEP_STUBS);

        new ReplicaService(curator, new ReplicaMaps(), repo, listener);

        verify(curator.getConnectionStateListenable()).addListener(listener);
    }

    @Test
    void setupLoadsExistingSnapshotAndReusesPreparedMap() throws Exception {
        write(Head.ZNODE_PREPARED, snapshotA);
        write(Head.ZNODE_ACTIVATED, snapshotA);

        var maps = service.initMaps();

        assertTrue(maps.isFullyInitialized());
        assertSame(docA.getResource(), active(maps).get("sub-a"));
        assertSame(maps.getPrepared().get(), maps.getActive().get());
        verify(repo, times(1)).findBySnapshotId(snapshotA);
    }

    @Test
    void initFailsWhenZnodesAreMissing() {
        assertThrows(Exception.class, service::initMaps);
    }

    @Test
    void initFailsOnUnreadableHead() throws Exception {
        client.create().creatingParentsIfNeeded().forPath(Head.ZNODE_PREPARED, "not-json".getBytes(StandardCharsets.UTF_8));

        assertThrows(RuntimeException.class, service::initMaps);
    }

    @Test
    void switchesToNewSnapshotWhenZnodesChange() throws Exception {
        write(Head.ZNODE_PREPARED, snapshotA);
        write(Head.ZNODE_ACTIVATED, snapshotA);
        var maps = service.initMaps();

        write(Head.ZNODE_PREPARED, snapshotB);
        write(Head.ZNODE_ACTIVATED, snapshotB);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertSame(docB.getResource(), active(maps).get("sub-b")));
        assertNull(active(maps).get("sub-a"));
    }

    @Test
    void loadsActivatedSnapshotDirectlyWhenItWasNotPrepared() throws Exception {
        write(Head.ZNODE_PREPARED, snapshotA);
        write(Head.ZNODE_ACTIVATED, snapshotA);
        var maps = service.initMaps();

        write(Head.ZNODE_ACTIVATED, snapshotB);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertSame(docB.getResource(), active(maps).get("sub-b")));
        assertSame(docA.getResource(), maps.getPrepared().get().get("sub-a"));
    }

    private static IndexedMap active(ReplicaMaps maps) {
        return maps.getActive().get();
    }

    private void write(String path, String snapshotId) throws Exception {
        var data = ("{\"snapshotId\":\"" + snapshotId + "\"}").getBytes(StandardCharsets.UTF_8);
        if (client.checkExists().forPath(path) == null) {
            client.create().creatingParentsIfNeeded().forPath(path, data);
        } else {
            client.setData().forPath(path, data);
        }
    }

    static SnapshotMongoDocument document(String id) {
        var subscription = new Subscription();
        subscription.setType("event.v1");

        var spec = new SubscriptionResourceSpec();
        spec.setEnvironment("playground");
        spec.setSubscription(subscription);

        var resource = new SubscriptionResource();
        resource.setSpec(spec);

        var document = new SnapshotMongoDocument();
        document.setSnapshotId(id);
        document.setResource(resource);
        return document;
    }
}
