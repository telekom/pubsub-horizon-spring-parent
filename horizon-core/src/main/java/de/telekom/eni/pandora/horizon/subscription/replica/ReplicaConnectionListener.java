package de.telekom.eni.pandora.horizon.subscription.replica;

import de.telekom.eni.pandora.horizon.subscription.SubscriptionProxy;
import de.telekom.eni.pandora.horizon.subscription.SubscriptionRepository;
import de.telekom.eni.pandora.horizon.subscription.SubscriptionRepositoryManager;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;

public class ReplicaConnectionListener implements ConnectionStateListener {
    private final SubscriptionRepositoryManager subscriptionRepositoryManager;
    public ReplicaConnectionListener(SubscriptionRepositoryManager subscriptionRepositoryManager) {
        this.subscriptionRepositoryManager = subscriptionRepositoryManager;
    }

    @Override
    public void stateChanged(CuratorFramework client, ConnectionState newState) {
        switch (newState) {

            case LOST -> {
                subscriptionRepositoryManager.switchToHazelcast();
            }

            case CONNECTED -> {
                subscriptionRepositoryManager.switchToMongo();
            }

        }
    }

}
