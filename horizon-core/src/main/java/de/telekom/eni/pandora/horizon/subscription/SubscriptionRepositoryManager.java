package de.telekom.eni.pandora.horizon.subscription;

import de.telekom.eni.pandora.horizon.subscription.hazelcast.HazelcastProxy;
import de.telekom.eni.pandora.horizon.subscription.replica.MongoReplica;

public class SubscriptionRepositoryManager {
    private final MongoReplica mongoReplica;
    private final HazelcastProxy hazelcastProxy;
    private final SubscriptionRepository subscriptionRepository;

    public SubscriptionRepositoryManager(MongoReplica mongoReplica, HazelcastProxy hazelcastProxy, SubscriptionRepository subscriptionRepository) {
        this.mongoReplica = mongoReplica;
        this.hazelcastProxy = hazelcastProxy;
        this.subscriptionRepository = subscriptionRepository;
    }

    public void switchToMongo() {
        subscriptionRepository.changeProxy(mongoReplica);
    }

    public void switchToHazelcast() {
        subscriptionRepository.changeProxy(hazelcastProxy);
    }
}
