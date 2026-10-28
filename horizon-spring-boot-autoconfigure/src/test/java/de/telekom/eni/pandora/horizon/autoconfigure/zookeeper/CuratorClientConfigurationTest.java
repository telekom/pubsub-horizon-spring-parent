// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.zookeeper;

import de.telekom.eni.pandora.horizon.zookeeper.ZooKeeperBackOffProperties;
import de.telekom.eni.pandora.horizon.zookeeper.ZooKeeperProperties;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class CuratorClientConfigurationTest {

    private static TestingServer testingServer;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CuratorClientConfiguration.class);

    @BeforeAll
    static void setUp() throws Exception {
        testingServer = new TestingServer(true);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (testingServer != null) {
            testingServer.close();
        }
    }

    @Test
    @DisplayName("Verify CuratorFramework bean creation and basic ZooKeeper operations when zookeeper.enabled is true")
    void testCuratorFrameworkBeanCreatedAndOperational() {
        contextRunner
                .withPropertyValues(
                        "zookeeper.enabled=true",
                        "zookeeper.uri=" + testingServer.getConnectString(),
                        "zookeeper.session-timeout-ms=10000",
                        "zookeeper.connection-timeout-ms=5000",
                        "zookeeper.back-off.base-sleep-ms=100",
                        "zookeeper.back-off.max-retries=3"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(CuratorFramework.class);
                    var client = context.getBean(CuratorFramework.class);

                    assertNotNull(client);
                    assertEquals(CuratorFrameworkState.STARTED, client.getState());

                    // Test node creation
                    final String path = "/horizon/test-node";
                    final byte[] data = "hello-zookeeper".getBytes(StandardCharsets.UTF_8);

                    client.create().creatingParentsIfNeeded().forPath(path, data);
                    assertNotNull(client.checkExists().forPath(path));

                    // Test reading data
                    byte[] retrievedData = client.getData().forPath(path);
                    assertEquals("hello-zookeeper", new String(retrievedData, StandardCharsets.UTF_8));

                    // Test updating data
                    byte[] updatedData = "updated-zookeeper-data".getBytes(StandardCharsets.UTF_8);
                    client.setData().forPath(path, updatedData);
                    assertEquals("updated-zookeeper-data", new String(client.getData().forPath(path), StandardCharsets.UTF_8));

                    // Test deleting node
                    client.delete().deletingChildrenIfNeeded().forPath(path);
                    assertNull(client.checkExists().forPath(path));
                });
    }

    @Test
    @DisplayName("Verify CuratorFramework bean is not created when zookeeper.enabled is false")
    void testCuratorFrameworkBeanNotCreatedWhenDisabled() {
        contextRunner
                .withPropertyValues(
                        "zookeeper.enabled=false",
                        "zookeeper.uri=" + testingServer.getConnectString()
                )
                .run(context -> assertThat(context).doesNotHaveBean(CuratorFramework.class));
    }

    @Test
    @DisplayName("Verify CuratorFramework bean is not created when zookeeper.enabled property is missing")
    void testCuratorFrameworkBeanNotCreatedWhenPropertyMissing() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(CuratorFramework.class));
    }

    @Test
    @DisplayName("Verify direct curatorFramework bean method creates and starts client")
    void testDirectCuratorFrameworkCreation() throws Exception {
        var configuration = new CuratorClientConfiguration();

        var properties = new ZooKeeperProperties();
        properties.setEnabled(true);
        properties.setUri(testingServer.getConnectString());
        properties.setSessionTimeoutMs(10000);
        properties.setConnectionTimeoutMs(5000);

        var backOff = new ZooKeeperBackOffProperties();
        backOff.setBaseSleepMs(200);
        backOff.setMaxRetries(2);
        properties.setBackOff(backOff);

        var client = configuration.curatorFramework(properties);
        try {
            assertNotNull(client);
            assertEquals(CuratorFrameworkState.STARTED, client.getState());
            assertNotNull(client.checkExists().forPath("/"));
        } finally {
            client.close();
        }
    }
}
