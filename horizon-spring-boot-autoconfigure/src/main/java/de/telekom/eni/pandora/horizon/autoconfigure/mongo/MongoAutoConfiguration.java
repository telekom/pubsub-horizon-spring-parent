// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.mongo;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import de.telekom.eni.pandora.horizon.mongo.config.MongoProperties;
import de.telekom.eni.pandora.horizon.mongo.repository.MessageStateMongoRepo;
import de.telekom.eni.pandora.horizon.mongo.repository.SubscriptionsMongoRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.retry.annotation.EnableRetry;

@Slf4j
@Configuration
@ConditionalOnProperty(value = "horizon.mongo.enabled")
@EnableRetry
@EnableConfigurationProperties({MongoProperties.class})
public class MongoAutoConfiguration {
    @Bean
    public MongoClient mongo(MongoProperties properties) {
        log.debug("Using database at: " + properties.getUrl());
        log.debug("Using status database: " + properties.getDatabase());
        log.debug("Using config database: " + properties.getDatabaseConfig());
        log.debug("isRethrowExceptions: " + properties.isRethrowExceptions());

        var connectionString = new ConnectionString(properties.getUrl());
        var clientSettings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .writeConcern(properties.getWriteConcern())
                .build();

        return MongoClients.create(clientSettings);
    }

    @Bean
    @ConditionalOnMissingBean
    public MongoTemplate mongoStatusTemplate(MongoProperties properties) {
        return new MongoTemplate(mongo(properties), properties.getDatabase());
    }

    @Bean
    @ConditionalOnMissingBean
    public MongoTemplate mongoConfigTemplate(MongoProperties properties) {
        return new MongoTemplate(mongo(properties), properties.getDatabaseConfig());
    }

    @Bean
    public MessageStateMongoRepo getStatusMessageRepo(MongoTemplate mongoStatusTemplate) {
        MongoRepositoryFactory mongoRepositoryFactory = new MongoRepositoryFactory(mongoStatusTemplate);
        return mongoRepositoryFactory.getRepository(MessageStateMongoRepo.class);
    }

    @Bean
    public SubscriptionsMongoRepo getSubscriptionsRepo(MongoTemplate mongoConfigTemplate) {
        MongoRepositoryFactory mongoRepositoryFactory = new MongoRepositoryFactory(mongoConfigTemplate);
        return mongoRepositoryFactory.getRepository(SubscriptionsMongoRepo.class);
    }

}
