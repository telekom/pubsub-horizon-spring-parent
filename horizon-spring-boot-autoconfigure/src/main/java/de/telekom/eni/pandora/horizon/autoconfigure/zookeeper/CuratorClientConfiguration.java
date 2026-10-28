// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.zookeeper;

import de.telekom.eni.pandora.horizon.zookeeper.ZooKeeperProperties;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty("zookeeper.enabled")
@EnableConfigurationProperties(ZooKeeperProperties.class)
public class CuratorClientConfiguration {

    @Bean(destroyMethod = "close")
    public CuratorFramework curatorFramework(ZooKeeperProperties properties) {
        var backoffProps = properties.getBackOff();
        var exponentialBackoff = new ExponentialBackoffRetry(backoffProps.getBaseSleepMs(), backoffProps.getMaxRetries());
        var client = CuratorFrameworkFactory.builder()
                .retryPolicy(exponentialBackoff)
                .connectString(properties.getUri())
                .sessionTimeoutMs(properties.getSessionTimeoutMs())
                .connectionTimeoutMs(properties.getConnectionTimeoutMs())
                .build();

        client.start();
        return client;
    }


}
