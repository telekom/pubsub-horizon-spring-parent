package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import de.telekom.eni.pandora.horizon.cache.service.JsonCacheService;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.jsonfilter.operator.Operator;
import de.telekom.jsonfilter.serde.OperatorDeserializer;
import de.telekom.jsonfilter.serde.OperatorSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonCacheAutoconfiguration {

    private static final String SUBSCRIPTION_RESOURCE_V1 = "subscriptions.subscriber.horizon.telekom.de.v1";

    @Bean
    public JsonCacheService<SubscriptionResource> subscriptionCache(HazelcastInstance hazelcastInstance) {
        var module = new SimpleModule();
        module.addSerializer(Operator.class, new OperatorSerializer());
        module.addDeserializer(Operator.class, new OperatorDeserializer());

        var mapper = new ObjectMapper();
        mapper.registerModule(module);

        IMap<String, HazelcastJsonValue> map = hazelcastInstance.getMap(SUBSCRIPTION_RESOURCE_V1);
        return new JsonCacheService<>(SubscriptionResource.class, map, mapper);
    }

}
