// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.subscription;

import de.telekom.eni.pandora.horizon.subscription.SubscriptionProxy;
import de.telekom.eni.pandora.horizon.subscription.SubscriptionRepository;
import org.apache.curator.framework.CuratorFramework;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class MongoReplicaConfigurationTest {

    @Test
    void registersConnectionListenerOnCurator() {
        var curator = mock(CuratorFramework.class, RETURNS_DEEP_STUBS);

        var listener = new ReplicaConfiguration().replicaConnectionListener(
                curator, mock(SubscriptionRepository.class), mock(SubscriptionProxy.class), mock(SubscriptionProxy.class));

        verify(curator.getConnectionStateListenable()).addListener(listener);
    }

}
