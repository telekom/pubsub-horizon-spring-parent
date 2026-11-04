// Copyright 2026 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.subscription.replica;

import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.subscription.SubscriptionProxy;
import org.jspecify.annotations.Nullable;

import java.util.List;

public class MongoReplica implements SubscriptionProxy {

    private final ReplicaMaps replicaMaps;

    // TODO: Must clarify if we should fallback to Hazelcast
    // TODO: How should we handle cases in the running component? Switch to Hazelcast?
    public MongoReplica(ReplicaService replicaService) throws Exception {
        this.replicaMaps = replicaService.initMaps();
    }

    @Override
    public @Nullable SubscriptionResource get(String subscriptionId) {
        return replicaMaps.getActive().get().get(subscriptionId);
    }

    @Override
    public List<SubscriptionResource> get(String environment, String eventType) {
        return replicaMaps.getActive().get().get(environment, eventType);
    }

}
