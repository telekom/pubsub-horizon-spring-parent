package de.telekom.eni.pandora.horizon.cache.fallback;

import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.Subscription;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResourceSpec;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionTrigger;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionMongoDocument;
import de.telekom.eni.pandora.horizon.mongo.repository.SubscriptionsMongoRepo;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@AllArgsConstructor
public class SubscriptionCacheMongoFallback implements JsonCacheFallback<SubscriptionResource> {

    private final SubscriptionsMongoRepo subscriptionsMongoRepo;

    @Override
    public Optional<SubscriptionResource> getByKey(String key) throws JsonCacheException {
        Optional<SubscriptionResource> result;

        log.warn("Hazelcast map not available. Falling back to MongoDB.");
        List<SubscriptionMongoDocument> docs = subscriptionsMongoRepo.findBySubscriptionId(key);
        log.debug("MongoDB Query raw result: {}", docs);

        if (docs.getFirst() != null) {
            List<SubscriptionResource> mapped = mapMongoSubscriptions(docs);

            result = Optional.of(mapped.getFirst());
            log.debug("MongoDB Query result: {}", result);

            return result;
        }

        return Optional.empty();
    }

    @Override
    public List<SubscriptionResource> getQuery(Query query) throws JsonCacheException {
        log.error("Hazelcast map is not available, using MongoDB instead");

        List<SubscriptionMongoDocument> docs = subscriptionsMongoRepo.findByType(query.getEventType());
        log.debug("MongoDB Query raw result: {}", docs);

        List<SubscriptionResource> result = mapMongoSubscriptions(docs);
        log.debug("MongoDB Query result: {}", result);

        return result;
    }

    @Override
    public List<SubscriptionResource> getAll() throws JsonCacheException {
        log.error("Hazelcast map is not available, using MongoDB instead");
        List<SubscriptionMongoDocument> docs = subscriptionsMongoRepo.findAll();
        return mapMongoSubscriptions(docs);
    }

    public List<SubscriptionResource> mapMongoSubscriptions(List<SubscriptionMongoDocument> docs) {
        List<SubscriptionResource> mappedValues = new ArrayList<>();

        for (SubscriptionMongoDocument doc : docs) {
            SubscriptionTrigger trigger = new SubscriptionTrigger();
            SubscriptionTrigger publisherTrigger = new SubscriptionTrigger();

            SubscriptionResourceSpec spec = new SubscriptionResourceSpec();
            SubscriptionResource resource = new SubscriptionResource();
            Subscription sub = new Subscription();

            sub.setSubscriptionId(doc.getSpec().getSubscription().getSubscriptionId());
            sub.setSubscriberId(doc.getSpec().getSubscription().getSubscriberId());
            sub.setPublisherId(doc.getSpec().getSubscription().getPublisherId());
            sub.setDeliveryType(doc.getSpec().getSubscription().getDeliveryType());
            sub.setType(doc.getSpec().getSubscription().getType());
            sub.setCallback(doc.getSpec().getSubscription().getCallback());

            spec.setSubscription(sub);

            resource.setSpec(spec);
            mappedValues.add(resource);
        }

        return mappedValues;
    }

}
