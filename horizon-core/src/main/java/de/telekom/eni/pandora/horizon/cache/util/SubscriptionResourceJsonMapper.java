// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.jsonfilter.operator.Operator;
import de.telekom.jsonfilter.serde.OperatorDeserializer;
import de.telekom.jsonfilter.serde.OperatorSerializer;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

import java.time.Instant;

/**
 * Maps subscriptions stored in MongoDB with the same Jackson configuration that is used for subscriptions in Hazelcast,
 * so that all read paths produce identical {@link SubscriptionResource} objects.
 */
public final class SubscriptionResourceJsonMapper {

    // Plain JSON values as in the Hazelcast JSON written by the publisher, not MongoDB extended JSON.
    private static final JsonWriterSettings JSON_SETTINGS = JsonWriterSettings.builder()
        .outputMode(JsonMode.RELAXED)
        .dateTimeConverter((value, writer) -> writer.writeString(Instant.ofEpochMilli(value).toString()))
        .int64Converter((value, writer) -> writer.writeNumber(Long.toString(value)))
        .decimal128Converter((value, writer) -> writer.writeNumber(value.toString()))
        .objectIdConverter((value, writer) -> writer.writeString(value.toHexString()))
        .build();

    private final ObjectMapper objectMapper;

    /** Creates a mapper with the subscription Jackson configuration of {@link #createObjectMapper()}. */
    public SubscriptionResourceJsonMapper() {
        this(createObjectMapper());
    }

    public SubscriptionResourceJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Creates the Jackson configuration used for subscription resources in Hazelcast.
     *
     * @return a new object mapper with the JSON filter operator (de)serializers
     */
    public static ObjectMapper createObjectMapper() {
        var module = new SimpleModule();
        module.addSerializer(Operator.class, new OperatorSerializer());
        module.addDeserializer(Operator.class, new OperatorDeserializer());
        var mapper = new ObjectMapper();
        mapper.registerModule(module);
        return mapper;
    }

    /**
     * Converts a MongoDB subscription document to a subscription resource; the MongoDB {@code _id} is ignored.
     *
     * @param document subscription custom resource as BSON document
     * @return the mapped subscription resource
     * @throws IllegalArgumentException if the document cannot be mapped
     */
    public SubscriptionResource fromDocument(Document document) {
        var resource = new Document(document);
        resource.remove("_id");
        try {
            return objectMapper.readValue(resource.toJson(JSON_SETTINGS), SubscriptionResource.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot map MongoDB subscription document", exception);
        }
    }
}
