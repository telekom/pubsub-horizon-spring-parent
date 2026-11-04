// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.mongo;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.connection.ServerConnectionState;
import com.mongodb.event.ServerDescriptionChangedEvent;
import com.mongodb.event.ServerListener;
import de.telekom.eni.pandora.horizon.mongo.client.ReconnectServerListener;
import de.telekom.eni.pandora.horizon.mongo.config.MongoProperties;
import de.telekom.eni.pandora.horizon.mongo.repository.MessageStateMongoRepo;
import de.telekom.eni.pandora.horizon.mongo.repository.SnapshotMongoRepo;
import de.telekom.eni.pandora.horizon.mongo.repository.SubscriptionsMongoRepo;
import de.telekom.eni.pandora.horizon.subscription.SubscriptionRepositoryManager;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.retry.annotation.EnableRetry;

@Slf4j
@Configuration
@ConditionalOnProperty(value = "horizon.mongo.enabled")
@EnableRetry
@EnableConfigurationProperties({MongoProperties.class})
public class MongoAutoConfiguration {

    @Value("${spring.application.name:horizon-parent}")
    private String applicationName;

    @Bean
    public MongoClient mongoClient(MongoProperties properties, SubscriptionRepositoryManager subscriptionRepositoryManager) {
        log.debug("Using status database: " + properties.getDatabases().getRunTimeDatabase());
        log.debug("Using config database: " + properties.getDatabases().getConfigTimeDatabase());

        var connectionString = new ConnectionString(properties.getUrl());
        var clientSettings = MongoClientSettings.builder()
                .applyToServerSettings(serverSettings -> serverSettings.addServerListener(new ReconnectServerListener(subscriptionRepositoryManager)))
                .applyConnectionString(connectionString)
                .writeConcern(properties.getWriteConcern())
                .applicationName(applicationName)
                .build();

        return MongoClients.create(clientSettings);
    }


    @Bean(name = "mongoStatusTemplate")
    //@ConditionalOnMissingBean(name = "mongoStatusTemplate")
    public MongoTemplate mongoStatusTemplate(MongoClient mongoClient, MongoProperties properties) {
        return new MongoTemplate(mongoClient, properties.getDatabases().getRunTimeDatabase());
    }

    @Primary
    @Bean(name = "mongoConfigTemplate")
    //@ConditionalOnMissingBean(name = "mongoConfigTemplate")
    public MongoTemplate mongoConfigTemplate(MongoClient mongoClient, MongoProperties properties) {
        return new MongoTemplate(mongoClient, properties.getDatabases().getConfigTimeDatabase());
    }

    @Bean
    public MessageStateMongoRepo getStatusMessageRepo(@Qualifier("mongoStatusTemplate") MongoTemplate mongoStatusTemplate) {
        MongoRepositoryFactory mongoRepositoryFactory = new MongoRepositoryFactory(mongoStatusTemplate);
        return mongoRepositoryFactory.getRepository(MessageStateMongoRepo.class);
    }

    @Bean
    public SubscriptionsMongoRepo getSubscriptionsRepo(@Qualifier("mongoConfigTemplate") MongoTemplate mongoConfigTemplate) {
        MongoRepositoryFactory mongoRepositoryFactory = new MongoRepositoryFactory(mongoConfigTemplate);
        return mongoRepositoryFactory.getRepository(SubscriptionsMongoRepo.class);
    }

    @Bean
    public SnapshotMongoRepo getSnapshotRepo(@Qualifier("mongoConfigTemplate") MongoTemplate mongoConfigTemplate) {
        MongoRepositoryFactory mongoRepositoryFactory = new MongoRepositoryFactory(mongoConfigTemplate);
        return mongoRepositoryFactory.getRepository(SnapshotMongoRepo.class);
    }
}