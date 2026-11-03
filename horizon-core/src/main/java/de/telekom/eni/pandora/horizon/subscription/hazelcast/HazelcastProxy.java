package de.telekom.eni.pandora.horizon.subscription.hazelcast;

import de.telekom.eni.pandora.horizon.cache.service.JsonCacheService;
import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.subscription.SubscriptionProxy;
import org.jspecify.annotations.Nullable;

import java.util.List;

public class HazelcastProxy implements SubscriptionProxy {

    private final JsonCacheService<SubscriptionResource> subscriptionCache;

    public HazelcastProxy(JsonCacheService<SubscriptionResource> subscriptionCache) {
        this.subscriptionCache = subscriptionCache;
    }

    @Override
    public @Nullable SubscriptionResource get(String subscriptionId) {
        try {
            return subscriptionCache.getByKey(subscriptionId).orElse(null);
        } catch (JsonCacheException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<SubscriptionResource> get(String environment, String eventType) {
        try {
            var query = Query.builder(SubscriptionResource.class)
                    .addMatcher("spec.environment", environment)
                    .addMatcher("spec.subscription.type", eventType)
                    .build();
            return subscriptionCache.getQuery(query);
        } catch (JsonCacheException e) {
            throw new RuntimeException(e);
        }
    }
}
