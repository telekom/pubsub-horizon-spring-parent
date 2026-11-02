// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.subscription;

import de.telekom.eni.pandora.horizon.mongo.repository.SnapshotMongoRepo;
import de.telekom.eni.pandora.horizon.subscription.ReplicaMaps;
import de.telekom.eni.pandora.horizon.subscription.ReplicaService;
import org.apache.curator.framework.CuratorFramework;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReplicaConfiguration {

    @Bean
    public ReplicaMaps replicaMaps() {
        return new ReplicaMaps();
    }

    @Bean(initMethod = "setup", destroyMethod = "tearDown")
    public ReplicaService replicaService(CuratorFramework curatorFramework, ReplicaMaps replicaMaps, SnapshotMongoRepo snapshotMongoRepo) {
        return new ReplicaService(curatorFramework, replicaMaps, snapshotMongoRepo);
    }

}
