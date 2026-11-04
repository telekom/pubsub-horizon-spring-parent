package de.telekom.eni.pandora.horizon.mongo.client;

import com.mongodb.connection.ServerConnectionState;
import com.mongodb.event.ServerDescriptionChangedEvent;
import com.mongodb.event.ServerListener;
import de.telekom.eni.pandora.horizon.subscription.SubscriptionRepositoryManager;
import org.checkerframework.checker.nullness.qual.NonNull;

public class ReconnectServerListener implements ServerListener {
    private final SubscriptionRepositoryManager subscriptionRepositoryManager;

    public ReconnectServerListener(SubscriptionRepositoryManager subscriptionRepositoryManager) {
        this.subscriptionRepositoryManager = subscriptionRepositoryManager;
    }

    @Override
    public void serverDescriptionChanged(@NonNull ServerDescriptionChangedEvent event) {
        // TODO: validate possible connection state transitions
        if (event.getNewDescription().getState() == ServerConnectionState.CONNECTED) {
            handleReconnect();
        }
    }

    private void handleReconnect() {
        // TODO: implement
        subscriptionRepositoryManager.switchToMongo();
    }
}
