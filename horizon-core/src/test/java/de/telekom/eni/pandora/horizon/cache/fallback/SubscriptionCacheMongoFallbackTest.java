// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.fallback;

import com.fasterxml.jackson.core.JsonProcessingException;
import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.cache.util.SubscriptionResourceJsonMapper;
import de.telekom.eni.pandora.horizon.kubernetes.resource.Subscription;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResourceSpec;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionTrigger;
import de.telekom.eni.pandora.horizon.mongo.config.MongoProperties;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionMongoDocument;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubscriptionCacheMongoFallbackTest {

    private MongoTemplate mongoTemplate;
    
    private SubscriptionCacheMongoFallback subscriptionCacheMongoFallback;
    
    private static final String TEST_SUBSCRIPTION_ID = "123";
    
    private static final String TEST_SUBSCRIPTION_TYPE = "testSubscriptionType";
    
    private static final MongoProperties mongoProperties = new MongoProperties();


    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        subscriptionCacheMongoFallback = new SubscriptionCacheMongoFallback(mongoTemplate, mongoProperties,
                new SubscriptionResourceJsonMapper());
    }

    @Test
    void shouldBeReadyWhenMongoDbIsReachable() {
        assertTrue(subscriptionCacheMongoFallback.isReady());

        var captor = ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Query.class);
        verify(mongoTemplate).exists(captor.capture(), eq(SubscriptionCacheMongoFallback.COLLECTION_NAME));
        assertEquals("__horizon_cache_readiness__", captor.getValue().getQueryObject().get("_id"));
    }

    @Test
    void shouldNotBeReadyWhenMongoDbIsNotReachable() {
        when(mongoTemplate.exists(anyMongoQuery(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("MongoDB unavailable"));

        assertFalse(subscriptionCacheMongoFallback.isReady());
    }

    @Test
    void testGetQuery() {

        // Prepare test data and simulate mongo db
        SubscriptionMongoDocument mockDocument = createMockSubscriptionDocument(TEST_SUBSCRIPTION_ID, TEST_SUBSCRIPTION_TYPE);
        stubFind(mockDocument);

        // Call method to test
        Query query = Query.builder(SubscriptionMongoDocument.class)
                .addMatcher("spec.subscription.type", TEST_SUBSCRIPTION_TYPE)
                .addMatcher("spec.environment", "integration")
                .build();

        List<SubscriptionResource> cacheResult = subscriptionCacheMongoFallback.getQuery(query);

        // Verify result
        var filter = capturedFindFilter();
        assertEquals(TEST_SUBSCRIPTION_TYPE, filter.get("spec.subscription.type"));
        assertEquals("integration", filter.get("spec.environment"));
        assertFalse(cacheResult. isEmpty(), "Result should be filled");
        assertEquals(TEST_SUBSCRIPTION_ID, cacheResult.getFirst().getSpec().getSubscription().getSubscriptionId(), "SubscriptionId should match");
    }

    @Test
    void testGetByKey() {

        // Prepare test data and simulate mongo db
        SubscriptionMongoDocument mockDocument = createMockSubscriptionDocument(TEST_SUBSCRIPTION_ID, TEST_SUBSCRIPTION_TYPE);
        stubFind(mockDocument);

        // Call method to test
        Optional<SubscriptionResource> cacheResult = subscriptionCacheMongoFallback.getByKey(TEST_SUBSCRIPTION_ID);

        // Verify result
        assertEquals(TEST_SUBSCRIPTION_ID, capturedFindFilter().get("_id"));
        assertFalse(cacheResult. isEmpty(), "Result should be filled");
        assertEquals(TEST_SUBSCRIPTION_ID, cacheResult.get().getSpec().getSubscription().getSubscriptionId(), "SubscriptionId should match");
    }

    @Test
    void testGetAll() {

        // Prepare test data and simulate mongo db
        SubscriptionMongoDocument mockDocument = createMockSubscriptionDocument(TEST_SUBSCRIPTION_ID, TEST_SUBSCRIPTION_TYPE);
        when(mongoTemplate.findAll(Document.class, SubscriptionCacheMongoFallback.COLLECTION_NAME))
                .thenReturn(List.of(toDocument(mockDocument)));

        // Call method to test
        List<SubscriptionResource> cacheResult  = subscriptionCacheMongoFallback.getAll();

        // Verify result
        verify(mongoTemplate, times(1)).findAll(Document.class, SubscriptionCacheMongoFallback.COLLECTION_NAME);
        assertFalse(cacheResult. isEmpty(), "Result should be filled");
        assertEquals(TEST_SUBSCRIPTION_ID, cacheResult.getFirst().getSpec().getSubscription().getSubscriptionId(), "SubscriptionId should match");
    }

    @Test
    void testMapSubscriptionsFallback() {

        // Prepare test data and simulate mongo db
        SubscriptionMongoDocument mockDocument = createMockSubscriptionDocument(TEST_SUBSCRIPTION_ID, TEST_SUBSCRIPTION_TYPE);
        stubFind(mockDocument);

        // Call method to test
        Query query = Query.builder(SubscriptionMongoDocument.class)
                .addMatcher("spec.subscription.type" , TEST_SUBSCRIPTION_TYPE)
                .build();

        List<SubscriptionResource> cacheResult = subscriptionCacheMongoFallback.getQuery(query);

        // Verify results
        assertFalse(capturedFindFilter().containsKey("spec.environment"), "Query without environment must not filter it");
        assertNotNull(cacheResult, "Result should be filled");
        assertEquals(1, cacheResult.size(), "Size should be 1");
        assertInstanceOf(SubscriptionResource.class, cacheResult.getFirst());


        var mockSubscription = mockDocument.getSpec().getSubscription();
        var resultSubscription = cacheResult.getFirst().getSpec().getSubscription();

        assertEquals(mockSubscription.getSubscriptionId(), resultSubscription.getSubscriptionId(), "SubscriptionId should match");
        assertEquals(mockSubscription.getSubscriberId(), resultSubscription.getSubscriberId(), "SubscriberId should match");
        assertEquals(mockSubscription.getPublisherId(), resultSubscription.getPublisherId(), "PublisherId should match");
        assertEquals(mockSubscription.getDeliveryType(), resultSubscription.getDeliveryType(), "DeliveryType should match");
        assertEquals(mockSubscription.getType(), resultSubscription.getType(), "Type should match");
        assertEquals(mockSubscription.getCallback(), resultSubscription.getCallback(), "Callback should match");
    }

    @Test
    void testMapSubscriptionsPreservesTriggersAndEnvironment() {
        var document = createMockSubscriptionDocument(TEST_SUBSCRIPTION_ID, TEST_SUBSCRIPTION_TYPE);
        var trigger = new SubscriptionTrigger();
        trigger.setResponseFilter(List.of("response.id"));
        trigger.setSelectionFilter(Map.of("method", "POST"));
        var publisherTrigger = new SubscriptionTrigger();
        publisherTrigger.setResponseFilterMode(SubscriptionTrigger.ResponseFilterMode.EXCLUDE);
        document.getSpec().getSubscription().setTrigger(trigger);
        document.getSpec().getSubscription().setPublisherTrigger(publisherTrigger);
        document.getSpec().setEnvironment("integration");
        stubFind(document);

        var result = subscriptionCacheMongoFallback.getByKey(TEST_SUBSCRIPTION_ID).orElseThrow();

        assertEquals(List.of("response.id"), result.getSpec().getSubscription().getTrigger().getResponseFilter());
        assertEquals("POST", result.getSpec().getSubscription().getTrigger().getSelectionFilter().get("method"));
        assertEquals(SubscriptionTrigger.ResponseFilterMode.EXCLUDE,
                result.getSpec().getSubscription().getPublisherTrigger().getResponseFilterMode());
        assertEquals("integration", result.getSpec().getEnvironment());
    }

    private void stubFind(SubscriptionMongoDocument document) {
        when(mongoTemplate.find(anyMongoQuery(), eq(Document.class), eq(SubscriptionCacheMongoFallback.COLLECTION_NAME)))
                .thenReturn(List.of(toDocument(document)));
    }

    private Document capturedFindFilter() {
        var captor = ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Query.class);
        verify(mongoTemplate).find(captor.capture(), eq(Document.class), eq(SubscriptionCacheMongoFallback.COLLECTION_NAME));
        return captor.getValue().getQueryObject();
    }

    private static org.springframework.data.mongodb.core.query.Query anyMongoQuery() {
        return any(org.springframework.data.mongodb.core.query.Query.class);
    }

    private static Document toDocument(SubscriptionMongoDocument document) {
        try {
            return Document.parse(SubscriptionResourceJsonMapper.createObjectMapper().writeValueAsString(document));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // Helper method to create a mocked SubscriptionMongoDocument
    @SuppressWarnings("SameParameterValue")
    private SubscriptionMongoDocument createMockSubscriptionDocument(String subscriptionId, String type) {
        SubscriptionMongoDocument document = new SubscriptionMongoDocument();
        SubscriptionResourceSpec spec = new SubscriptionResourceSpec();
        Subscription subscription = new Subscription();
        subscription.setSubscriptionId(subscriptionId);
        subscription.setType(type);
        subscription.setDeliveryType("callback");
        subscription.setCallback("http://callback.url");
        subscription.setSubscriberId("testSubscriberId");
        subscription.setPublisherId("testPublisherId");
        spec.setSubscription(subscription);
        spec.setEnvironment("integration");
        document.setSpec(spec);
        return document;
    }
}