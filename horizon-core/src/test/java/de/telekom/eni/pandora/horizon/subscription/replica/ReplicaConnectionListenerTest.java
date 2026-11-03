// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.subscription.replica;

import de.telekom.eni.pandora.horizon.subscription.SubscriptionProxy;
import de.telekom.eni.pandora.horizon.subscription.SubscriptionRepository;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.state.ConnectionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.mockito.Mockito.*;

class ReplicaConnectionListenerTest {

    private final SubscriptionRepository repository = mock(SubscriptionRepository.class);
    private final SubscriptionProxy mongoReplica = mock(SubscriptionProxy.class);
    private final SubscriptionProxy hazelcastCache = mock(SubscriptionProxy.class);
    private final CuratorFramework client = mock(CuratorFramework.class);
    private final ReplicaConnectionListener listener = new ReplicaConnectionListener(repository, mongoReplica, hazelcastCache);

    @Test
    void switchesToHazelcastWhenConnectionIsLost() {
        listener.stateChanged(client, ConnectionState.LOST);

        verify(repository).changeProxy(hazelcastCache);
    }

    @Test
    void switchesToReplicaWhenConnected() {
        listener.stateChanged(client, ConnectionState.CONNECTED);

        verify(repository).changeProxy(mongoReplica);
    }

    @ParameterizedTest
    @EnumSource(value = ConnectionState.class, names = {"LOST", "CONNECTED"}, mode = EnumSource.Mode.EXCLUDE)
    void ignoresOtherStates(ConnectionState state) {
        listener.stateChanged(client, state);

        verifyNoInteractions(repository);
    }
}
