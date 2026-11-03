// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.subscription;

import de.telekom.eni.pandora.horizon.mongo.repository.SnapshotMongoRepo;
import de.telekom.eni.pandora.horizon.subscription.SubscriptionProxy;
import de.telekom.eni.pandora.horizon.subscription.SubscriptionRepository;
import de.telekom.eni.pandora.horizon.subscription.replica.Replica;
import de.telekom.eni.pandora.horizon.subscription.replica.ReplicaConnectionListener;
import de.telekom.eni.pandora.horizon.subscription.replica.ReplicaMaps;
import de.telekom.eni.pandora.horizon.subscription.replica.ReplicaService;
import org.apache.curator.framework.CuratorFramework;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReplicaConfiguration {

    @Bean
    public ReplicaMaps replicaMaps() {
        return new ReplicaMaps();
    }

    @Bean
    public ReplicaService replicaService(CuratorFramework curatorFramework, ReplicaMaps replicaMaps, SnapshotMongoRepo snapshotMongoRepo, ReplicaConnectionListener replicaConnectionListener) {
        return new ReplicaService(curatorFramework, replicaMaps, snapshotMongoRepo, replicaConnectionListener);
    }

    @Bean
    public Replica replica(ReplicaService replicaService) throws Exception {
        return new Replica(replicaService);
    }

    @Bean
    public ReplicaConnectionListener replicaConnectionListener(SubscriptionRepository repository, SubscriptionProxy mongoReplica, SubscriptionProxy hazelcastCache) {
        return new ReplicaConnectionListener(repository, mongoReplica, hazelcastCache);
    }

}
