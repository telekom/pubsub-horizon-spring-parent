// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.kubernetes.resource.Subscription;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResourceSpec;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionTrigger;
import de.telekom.jsonfilter.operator.comparison.EqualsOperator;
import de.telekom.jsonfilter.operator.comparison.GreaterEqualOperator;
import de.telekom.jsonfilter.operator.logic.AndOperator;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubscriptionResourceJsonMapperTest {

    private static final String CREATED_AT = "2026-09-30T12:30:00Z";

    private final ObjectMapper objectMapper = SubscriptionResourceJsonMapper.createObjectMapper();
    private final SubscriptionResourceJsonMapper jsonMapper = new SubscriptionResourceJsonMapper(objectMapper);

    @Test
    void mapsMongoDocumentLikeHazelcastJson() throws Exception {
        var json = objectMapper.writeValueAsString(subscriptionWithFilters());
        var fromHazelcast = objectMapper.readValue(json, SubscriptionResource.class);

        var document = Document.parse(json);
        document.put("_id", new ObjectId());

        assertSameContent(fromHazelcast, jsonMapper.fromDocument(document));
    }

    @Test
    void convertsBsonTypesToPlainJsonValues() throws Exception {
        var json = objectMapper.writeValueAsString(subscriptionWithFilters());
        var fromHazelcast = objectMapper.readValue(json, SubscriptionResource.class);

        // Types a BSON writer may produce for the same CR: dates, int64 and decimal128 instead of JSON strings/ints.
        var document = (Document) toBsonTypes(Document.parse(json));
        var subscription = document.get("spec", Document.class).get("subscription", Document.class);
        subscription.put("createdAt", Date.from(Instant.parse(CREATED_AT)));
        subscription.put("retryableStatusCodes", List.of(429L, 503L));

        assertSameContent(fromHazelcast, jsonMapper.fromDocument(document));
    }

    @Test
    void rejectsDocumentsThatCannotBeMapped() {
        var document = new Document("spec", new Document("subscription", new Document("retryableStatusCodes", "no list")));

        assertThrows(IllegalArgumentException.class, () -> jsonMapper.fromDocument(document));
    }

    private void assertSameContent(SubscriptionResource expected, SubscriptionResource actual) throws Exception {
        assertEquals(objectMapper.readTree(objectMapper.writeValueAsString(expected)),
            objectMapper.readTree(objectMapper.writeValueAsString(actual)));
    }

    private static Object toBsonTypes(Object node) {
        if (node instanceof Document document) {
            document.replaceAll((key, value) -> toBsonTypes(value));
            return document;
        }
        if (node instanceof List<?> list) {
            return list.stream().map(SubscriptionResourceJsonMapperTest::toBsonTypes).toList();
        }
        if (node instanceof Integer number) {
            return new Decimal128(BigDecimal.valueOf(number));
        }
        return node;
    }

    private static SubscriptionResource subscriptionWithFilters() {
        var trigger = new SubscriptionTrigger();
        trigger.setResponseFilter(List.of("$.data.id"));
        trigger.setSelectionFilter(Map.of("type", "order"));
        trigger.setAdvancedSelectionFilter(new AndOperator(List.of(
            new EqualsOperator<>("$.foo", "bar"),
            new GreaterEqualOperator<>("$.number", 12))));

        var subscription = new Subscription();
        subscription.setSubscriptionId("subscription-id");
        subscription.setSubscriberId("subscriber-id");
        subscription.setPublisherId("publisher-id");
        subscription.setAdditionalPublisherIds(List.of("other-publisher"));
        subscription.setCreatedAt(CREATED_AT);
        subscription.setTrigger(trigger);
        subscription.setType("de.telekom.test.v1");
        subscription.setCallback("https://example.test/callback");
        subscription.setDeliveryType("callback");
        subscription.setCircuitBreakerOptOut(true);
        subscription.setRetryableStatusCodes(List.of(429, 503));

        var spec = new SubscriptionResourceSpec();
        spec.setEnvironment("integration");
        spec.setSubscription(subscription);

        var resource = new SubscriptionResource();
        resource.setSpec(spec);
        return resource;
    }
}
