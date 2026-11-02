package de.telekom.eni.pandora.horizon.subscription;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;

public class ReplicaConnectionListener implements ConnectionStateListener {

    private final SubscriptionProxy mongoReplica;

    private final SubscriptionProxy hazelcastCache;

    private final SubscriptionRepository repository;

    public ReplicaConnectionListener(SubscriptionRepository repository, SubscriptionProxy mongoReplica, SubscriptionProxy hazelcastCache) {
        this.mongoReplica = mongoReplica;
        this.hazelcastCache = hazelcastCache;
        this.repository = repository;
    }

    @Override
    public void stateChanged(CuratorFramework client, ConnectionState newState) {
        switch (newState) {

            case LOST -> {
                repository.changeProxy(hazelcastCache);
            }

            case CONNECTED -> {
                repository.changeProxy(mongoReplica);
            }

        }
    }

}
