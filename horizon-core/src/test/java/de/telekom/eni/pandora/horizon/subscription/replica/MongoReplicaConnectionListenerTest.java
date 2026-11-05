// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.subscription.replica;

import de.telekom.eni.pandora.horizon.subscription.SubscriptionRepositoryManager;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.state.ConnectionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.mockito.Mockito.*;

class MongoReplicaConnectionListenerTest {
    private final CuratorFramework client = mock(CuratorFramework.class);
    private final SubscriptionRepositoryManager subscriptionRepositoryManager = mock(SubscriptionRepositoryManager.class);
    private final ReplicaConnectionListener listener = new ReplicaConnectionListener(subscriptionRepositoryManager);

    @Test
    void switchesToHazelcastWhenConnectionIsLost() {
        listener.stateChanged(client, ConnectionState.LOST);

        verify(subscriptionRepositoryManager).switchToHazelcast();
    }

    @Test
    void switchesToReplicaWhenConnected() {
        listener.stateChanged(client, ConnectionState.CONNECTED);

        verify(subscriptionRepositoryManager).switchToMongo();
    }

    @ParameterizedTest
    @EnumSource(value = ConnectionState.class, names = {"LOST", "CONNECTED"}, mode = EnumSource.Mode.EXCLUDE)
    void ignoresOtherStates(ConnectionState state) {
        listener.stateChanged(client, state);

        verifyNoInteractions(subscriptionRepositoryManager);
    }
}
