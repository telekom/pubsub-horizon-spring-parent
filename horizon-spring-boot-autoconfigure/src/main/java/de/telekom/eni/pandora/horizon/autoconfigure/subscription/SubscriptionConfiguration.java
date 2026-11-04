package de.telekom.eni.pandora.horizon.autoconfigure.subscription;

import de.telekom.eni.pandora.horizon.subscription.SubscriptionProxy;
import de.telekom.eni.pandora.horizon.subscription.SubscriptionRepository;
import de.telekom.eni.pandora.horizon.subscription.replica.MongoReplica;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty({"zookeeper.enabled", "horizon.cache.enabled"})
public class SubscriptionConfiguration {

    @Bean
    public SubscriptionRepository subscriptionRepository(MongoReplica mongoReplica) {
        return new SubscriptionRepository(mongoReplica);
    }

    @Bean
    public SubscriptionProxy mongoReplica(MongoReplica mongoReplica) {
        return mongoReplica;
    }

}
