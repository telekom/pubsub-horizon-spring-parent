package de.telekom.eni.pandora.horizon.subscription;

import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class SubscriptionRepository {

    private final AtomicReference<SubscriptionProxy> proxy;

    public SubscriptionRepository(SubscriptionProxy proxy) {
        this.proxy = new AtomicReference<>(proxy);
    }

    public void changeProxy(SubscriptionProxy subscriptionProxy) {
        proxy.set(subscriptionProxy);
    }

    @Nullable
    public SubscriptionResource get(String subscriptionId) {
        return proxy.get().get(subscriptionId);
    }

    public List<SubscriptionResource> get(String environment, String subscriptionId) {
        return proxy.get().get(environment, subscriptionId);
    }

}
