// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.cache;

import de.telekom.eni.pandora.horizon.cache.service.LocalSubscriptionCache;
import de.telekom.eni.pandora.horizon.mongo.repository.SubscriptionsMongoRepo;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnBean(SubscriptionsMongoRepo.class)
public class LocalSubscriptionCacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LocalSubscriptionCache.class)
    public LocalSubscriptionCache localSubscriptionCache(
            @Qualifier("getSubscriptionsRepo") SubscriptionsMongoRepo subscriptionsMongoRepo) {
        return new LocalSubscriptionCache(subscriptionsMongoRepo);
    }
}