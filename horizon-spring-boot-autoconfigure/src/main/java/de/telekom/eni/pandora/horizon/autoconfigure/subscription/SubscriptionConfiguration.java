package de.telekom.eni.pandora.horizon.autoconfigure.subscription;

import de.telekom.eni.pandora.horizon.subscription.SubscriptionProxy;
import de.telekom.eni.pandora.horizon.subscription.SubscriptionRepository;
import de.telekom.eni.pandora.horizon.subscription.replica.Replica;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicReference;

@Configuration
public class SubscriptionConfiguration {

    @Bean
    public SubscriptionRepository subscriptionRepository(Replica replica) {
        return new SubscriptionRepository(new AtomicReference<>(replica));
    }

    @Bean
    public SubscriptionProxy mongoReplica(Replica replica) {
        return replica;
    }

}
