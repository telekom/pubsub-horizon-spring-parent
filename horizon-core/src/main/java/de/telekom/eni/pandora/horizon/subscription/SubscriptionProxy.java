package de.telekom.eni.pandora.horizon.subscription;

import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import org.jspecify.annotations.Nullable;

import java.util.List;

public interface SubscriptionProxy {

    @Nullable
    SubscriptionResource get(String subscriptionId);

    List<SubscriptionResource> get(String environment, String eventType);

}
