package de.telekom.eni.pandora.horizon.autoconfigure.mongo;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import de.telekom.eni.pandora.horizon.mongo.config.MongoProperties;
import de.telekom.eni.pandora.horizon.mongo.repository.MessageStateMongoRepo;
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
        var connectionString = new ConnectionString(properties.getUrl());
        var clientSettings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .writeConcern(properties.getWriteConcern())
                .build();

        return MongoClients.create(clientSettings);
    }

    @Bean
    @ConditionalOnMissingBean
    public MongoTemplate mongoTemplate(MongoProperties properties) {
        return new MongoTemplate(mongo(properties), properties.getDatabase());
    }

    @Bean
    public MessageStateMongoRepo getStatusMessageRepo(MongoTemplate mongoTemplate) {
        MongoRepositoryFactory mongoRepositoryFactory = new MongoRepositoryFactory(mongoTemplate);
        return mongoRepositoryFactory.getRepository(MessageStateMongoRepo.class);
    }
}
