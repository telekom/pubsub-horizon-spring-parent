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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty({"zookeeper.enabled", "horizon.cache.enabled"})
public class ReplicaConfiguration {

    @Bean
    public ReplicaMaps replicaMaps() {
        return new ReplicaMaps();
    }

    @Bean
    public ReplicaService replicaService(CuratorFramework curatorFramework, ReplicaMaps replicaMaps, SnapshotMongoRepo snapshotMongoRepo) {
        return new ReplicaService(curatorFramework, replicaMaps, snapshotMongoRepo);
    }

    @Bean
    public Replica replica(ReplicaService replicaService) throws Exception {
        return new Replica(replicaService);
    }

    @Bean
    public ReplicaConnectionListener replicaConnectionListener(
            CuratorFramework curator,
            SubscriptionRepository repository,
            @Qualifier("mongoReplica")
            SubscriptionProxy mongoReplica,
            @Qualifier("hazelcastCache")
            SubscriptionProxy hazelcastCache) {
        var listener = new ReplicaConnectionListener(repository, mongoReplica, hazelcastCache);
        curator.getConnectionStateListenable().addListener(listener);
        return listener;
    }

}
