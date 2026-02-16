// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.kafka.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("horizon.kafka")
public class KafkaProperties {

    @Value("${bootstrapServers:localhost:9092}")
    private String bootstrapServers;

    @Value("${disableGroupId:false}")
    private boolean disableGroupId;

    @Value("${groupId:default-group}")
    private String groupId;

    @Value("${partitionCount:10}")
    private int partitionCount;

    @Value("${autoCreateTopics:false}")
    private boolean autoCreateTopics;

    @Value("${autoOffsetReset:latest}")
    private String autoOffsetReset;

    @Value("${isolationLevel:read_committed}")
    private String isolationLevel;

    @Value("${maxPollRecords:500}")
    private int maxPollRecords;

    @Value("${requestTimeoutMs:30000}")
    private int requestTimeoutMs;

    @Value("${deliveryTimeoutMs:120000}")
    private int deliveryTimeoutMs;

    @Value("${lingerMs:0}")
    private int lingerMs;

    @Value("${acks:1}")
    private String acks;

    @Value("${maxRequestSize:1048576}")
    private String maxRequestSize;

    @Value("${batchSize:16384}")
    private int batchSize;

    private Compression compression = new Compression();

}
