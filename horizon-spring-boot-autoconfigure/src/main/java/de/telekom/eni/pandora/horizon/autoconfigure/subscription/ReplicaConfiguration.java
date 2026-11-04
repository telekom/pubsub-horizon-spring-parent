// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.subscription;

import de.telekom.eni.pandora.horizon.mongo.repository.SnapshotMongoRepo;
import de.telekom.eni.pandora.horizon.subscription.SubscriptionProxy;
import de.telekom.eni.pandora.horizon.subscription.SubscriptionRepository;
import de.telekom.eni.pandora.horizon.subscription.SubscriptionRepositoryManager;
import de.telekom.eni.pandora.horizon.subscription.replica.MongoReplica;
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
    public ReplicaService replicaService(
            CuratorFramework curatorFramework,
            ReplicaMaps replicaMaps,
            SnapshotMongoRepo snapshotMongoRepo,
            SubscriptionRepositoryManager subscriptionRepositoryManager
    ) {
        return new ReplicaService(curatorFramework, replicaMaps, snapshotMongoRepo, subscriptionRepositoryManager);
    }

    @Bean
    public MongoReplica replica(ReplicaService replicaService) throws Exception {
        return new MongoReplica(replicaService);
    }

    @Bean
    public ReplicaConnectionListener replicaConnectionListener(
            CuratorFramework curator,
            SubscriptionRepositoryManager subscriptionRepositoryManager) {
        var listener = new ReplicaConnectionListener(subscriptionRepositoryManager);
        curator.getConnectionStateListenable().addListener(listener);
        return listener;
    }

}
