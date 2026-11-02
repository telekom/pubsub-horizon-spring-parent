// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.subscription;

import de.telekom.eni.pandora.horizon.kubernetes.resource.Subscription;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResourceSpec;
import de.telekom.eni.pandora.horizon.mongo.model.SnapshotMongoDocument;
import de.telekom.eni.pandora.horizon.mongo.repository.SnapshotMongoRepo;
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
    private ReplicaService service;

    @BeforeEach
    void setUp() throws Exception {
        server = new TestingServer(true);
        client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100));
        client.start();

        repo = mock(SnapshotMongoRepo.class);
        when(repo.findBySnapshotId(snapshotA)).thenReturn(List.of(docA));
        when(repo.findBySnapshotId(snapshotB)).thenReturn(List.of(docB));

        service = new ReplicaService(client, new ReplicaMaps(), repo);
    }

    @AfterEach
    void tearDown() throws Exception {
        service.tearDown();
        client.close();
        server.close();
    }

    @Test
    void setupLoadsExistingSnapshotAndReusesPreparedMap() throws Exception {
        write(Head.ZNODE_PREPARED, snapshotA);
        write(Head.ZNODE_ACTIVATED, snapshotA);

        service.initMaps();

        assertSame(docA.getResource(), service.getActiveMap().get("sub-a"));
        verify(repo, times(1)).findBySnapshotId(snapshotA);
    }

    @Test
    void switchesToNewSnapshotWhenZnodesChange() throws Exception {
        write(Head.ZNODE_PREPARED, snapshotA);
        write(Head.ZNODE_ACTIVATED, snapshotA);
        service.initMaps();

        write(Head.ZNODE_PREPARED, snapshotB);
        write(Head.ZNODE_ACTIVATED, snapshotB);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertSame(docB.getResource(), service.getActiveMap().get("sub-b")));
        assertNull(service.getActiveMap().get("sub-a"));
    }

    @Test
    void loadsActivatedSnapshotDirectlyWhenItWasNotPrepared() throws Exception {
        write(Head.ZNODE_PREPARED, snapshotA);
        write(Head.ZNODE_ACTIVATED, snapshotA);
        service.initMaps();

        write(Head.ZNODE_ACTIVATED, snapshotB);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertSame(docB.getResource(), service.getActiveMap().get("sub-b")));
    }

    private void write(String path, String snapshotId) throws Exception {
        var data = ("{\"snapshotId\":\"" + snapshotId + "\"}").getBytes(StandardCharsets.UTF_8);
        if (client.checkExists().forPath(path) == null) {
            client.create().creatingParentsIfNeeded().forPath(path, data);
        } else {
            client.setData().forPath(path, data);
        }
    }

    private static SnapshotMongoDocument document(String id) {
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
