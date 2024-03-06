// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.autoconfigure.kafka;

import de.telekom.eni.pandora.horizon.kafka.config.KafkaProperties;
import de.telekom.eni.pandora.horizon.kafka.event.EventWriter;
import de.telekom.eni.pandora.horizon.model.meta.HorizonComponentId;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.*;

import java.util.HashMap;

@Configuration
@Getter
@Slf4j
@EnableConfigurationProperties(KafkaProperties.class)
public class KafkaAutoConfiguration {

    private ProducerFactory<String, String> producerFactory(KafkaProperties kafkaProperties) {
        log.debug("Initialized new ProducerFactory");

        var props = new HashMap<String, Object>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, kafkaProperties.getAcks());
        props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, kafkaProperties.getMaxRequestSize());

        final var compression = kafkaProperties.getCompression();
        log.info("Compression is {}", compression.isEnabled() ? "enabled" : "disabled");
        if (compression.isEnabled()) {
            log.debug("Using compression: " + compression.getType());
            props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, compression.getType());
            props.put(ProducerConfig.LINGER_MS_CONFIG, kafkaProperties.getLingerMs());
        }

        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory(KafkaProperties kafkaProperties) {
        log.debug("Initialized new consumer factory");

        var props = new HashMap<String, Object>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());

        if (!kafkaProperties.isDisableGroupId())
            props.put(ConsumerConfig.GROUP_ID_CONFIG, kafkaProperties.getGroupId());

        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, String.valueOf(kafkaProperties.isAutoCreateTopics()));
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, kafkaProperties.getAutoOffsetReset());
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, kafkaProperties.getIsolationLevel());
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, kafkaProperties.getMaxPollRecords());

        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer());
    }

    @Bean(name="kafkaTemplate")
    public KafkaTemplate<String,String> kafkaTemplate(KafkaProperties kafkaProperties, ConsumerFactory<String, String> consumerFactory) {
        log.debug("Initialized new kafka template");

        var kafkaTemplate = new KafkaTemplate<>(producerFactory(kafkaProperties));
        kafkaTemplate.setConsumerFactory(consumerFactory);
        return kafkaTemplate;
    }

    @Bean
    public EventWriter eventWriter(@Qualifier("kafkaTemplate") KafkaTemplate<String,String> kafkaTemplate, KafkaProperties kafkaProperties) {
        return new EventWriter(kafkaTemplate, HorizonComponentId.fromGroupId(kafkaProperties.getGroupId()));
    }

}
