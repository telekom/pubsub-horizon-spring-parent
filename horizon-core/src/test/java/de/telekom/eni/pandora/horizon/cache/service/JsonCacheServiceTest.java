// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.client.HazelcastClientOfflineException;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.Subscription;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResourceSpec;
import de.telekom.eni.pandora.horizon.model.dummy.CacheDummy;
import de.telekom.eni.pandora.horizon.mongo.config.MongoProperties;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionMongoDocument;
import de.telekom.eni.pandora.horizon.mongo.repository.SubscriptionsMongoRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JsonCacheServiceTest {

    private SubscriptionsMongoRepo subscriptionsMongoRepo;
    private JsonCacheService<SubscriptionResource> jsonCacheService;

    private static final String TEST_MAP_NAME = "testMap";
    private static final String TEST_SUBSCRIPTION_ID = "123";
    private static final String TEST_SUBSCRIPTION_TYPE = "testSubscriptionType";
    private static final MongoProperties mongoProperties = new MongoProperties();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        subscriptionsMongoRepo = mock(SubscriptionsMongoRepo.class);
        jsonCacheService = new JsonCacheService<>(
                SubscriptionResource.class,
                mock(IMap.class),
                new ObjectMapper()
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetQueryHazelcastAvailable() throws JsonCacheException {

        // Prepare test data and simulate Hazelcast
        HazelcastJsonValue mockValue = new HazelcastJsonValue("{\"spec\":{\"subscription\":{\"subscriptionId\":\"123\"}}}");
        Query query = Query.builder(SubscriptionResource.class)
                .addMatcher("spec.subscription.subscriptionId", TEST_SUBSCRIPTION_ID)
                .build();
        var mockMap = jsonCacheService.getMap();
        when(mockMap.values(query.toSqlPredicate())).thenReturn(List.of(mockValue));
        when(mockMap.size()).thenReturn(1);

        // Call method to test
        List<SubscriptionResource> result = jsonCacheService.getQuery(query);

        // Verify results
        verify(mockMap, times(1)).values(query.toSqlPredicate());
        assertFalse(result.isEmpty(), "Result should be filled");
        assertEquals(TEST_SUBSCRIPTION_ID, result.getFirst().getSpec().getSubscription().getSubscriptionId(), "SubscriptionId should match");
    }

    @Test
    void testGetQueryException() throws JsonCacheException {
        // Prepare test data and simulate Hazelcast map unavailability
        var mockMap = jsonCacheService.getMap();
        when(mockMap.values(any())).thenThrow(new HazelcastClientOfflineException());

        // Call method to test
        Query query = Query.builder(SubscriptionMongoDocument.class)
                .addMatcher("spec.subscription.type", TEST_SUBSCRIPTION_TYPE)
                .build();

        List<SubscriptionResource> cacheResult = jsonCacheService.getQuery(query);

        // Verify result
        assertNull(cacheResult, "Result should be empty");
    }

    @Test
    void testGetByKeyHazelcastAvailable() throws JsonCacheException {

        // Prepare test data and simulate Hazelcast
        HazelcastJsonValue mockValue = new HazelcastJsonValue("{\"spec\":{\"subscription\":{\"subscriptionId\":\"123\"}}}");
        var mockMap = jsonCacheService.getMap();
        when(mockMap.get(TEST_SUBSCRIPTION_ID)).thenReturn(mockValue);
        when(mockMap.size()).thenReturn(1);

        // Call method to test
        Optional<SubscriptionResource> result = jsonCacheService.getByKey(TEST_SUBSCRIPTION_ID);

        // Verify results
        verify(mockMap, times(1)).get(TEST_SUBSCRIPTION_ID);
        assertFalse(result.isEmpty(), "Result should be filled");
        assertEquals(TEST_SUBSCRIPTION_ID, result.get().getSpec().getSubscription().getSubscriptionId(), "SubscriptionId should match");
    }

    @Test
    void testGetByKeyException() throws JsonCacheException {

        // Prepare test data and simulate Hazelcast map unavailability
        var mockMap = jsonCacheService.getMap();
        when(mockMap.get(TEST_SUBSCRIPTION_ID)).thenThrow(new HazelcastClientOfflineException());

        // Call method to test
        Optional<SubscriptionResource> result = jsonCacheService.getByKey(TEST_SUBSCRIPTION_ID);

        // Verify result
        verify(mockMap, times(1)).get(TEST_SUBSCRIPTION_ID);
        assertFalse(result.isPresent(), "Result should be empty");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetAllHazelcastAvailable() throws JsonCacheException {
        // Prepare test data and simulate Hazelcast
        IMap<String, HazelcastJsonValue> mockMap = jsonCacheService.getMap();

        HazelcastJsonValue mockValue = new HazelcastJsonValue("{\"spec\":{\"subscription\":{\"subscriptionId\":\"123\"}}}");
        when(mockMap.values()).thenReturn(List.of(mockValue));
        when(mockMap.size()).thenReturn(1);

        // Call method to test
        List<SubscriptionResource> result = jsonCacheService.getAll();

        // Verify results
        verify(mockMap, times(1)).values();
        assertFalse(result.isEmpty(), "Result should be filled");
        assertEquals(TEST_SUBSCRIPTION_ID, result.getFirst().getSpec().getSubscription().getSubscriptionId(), "SubscriptionId should match");
    }

    @Test
    void testGetAllException() throws JsonCacheException {

        // Prepare test data and simulate Hazelcast map unavailability
        var mockMap = jsonCacheService.getMap();
        when(mockMap.get(TEST_SUBSCRIPTION_ID)).thenThrow(new HazelcastClientOfflineException());

        // Call method to test
        List<SubscriptionResource> result = jsonCacheService.getAll();

        // Verify result
        assertTrue(result.isEmpty(), "Result should be filled");
    }

    @Test
    void testRemove() {

        //Prepare test data
        String testKey = "testKey";

        // Call method to test
        jsonCacheService.remove(testKey);

        // Verify that the hazelcast remove method was called
        verify(jsonCacheService.getMap(), times(1)).remove(testKey);
    }

    @Test
    void testSet() throws JsonCacheException, JsonProcessingException {

        // Prepare test data
        var cacheObject = new CacheDummy("bar");
        var expectedJsonValue = new HazelcastJsonValue(new ObjectMapper().writeValueAsString(cacheObject));
        String key = "foo";

        // Call method to test
        jsonCacheService.set(key, cacheObject);

        // Verify results
        ArgumentCaptor<HazelcastJsonValue> captor = ArgumentCaptor.forClass(HazelcastJsonValue.class);
        verify(jsonCacheService.getMap(), times(1)).set(eq(key), captor.capture());
        HazelcastJsonValue actualJsonValue = captor.getValue();
        assertEquals(expectedJsonValue.getValue(), actualJsonValue.getValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testMapSubscriptions() throws JsonCacheException, JsonProcessingException {
        // Prepare test data and simulate Hazelcast
        var mockMap = jsonCacheService.getMap();

        SubscriptionMongoDocument mongoDocument = createMockSubscriptionDocument("subscription-123", "subscription-type");
        String jsonString = new ObjectMapper().writeValueAsString(mongoDocument);
        HazelcastJsonValue hazelcastJsonValue = new HazelcastJsonValue(jsonString);

        Query query = Query.builder(SubscriptionResource.class)
                .addMatcher("spec.subscription.subscriptionId", TEST_SUBSCRIPTION_ID)
                .build();

        when(mockMap.values(query.toSqlPredicate())).thenReturn(List.of(hazelcastJsonValue));
        when(mockMap.size()).thenReturn(1);

        // Call method getQuery to map cache subscriptions
        List<SubscriptionResource> result = jsonCacheService.getQuery(query);

        // Verify results
        assertFalse(result.isEmpty(), "Result should be filled");
        assertEquals(1, result.size(), "Size should be 1");
        assertInstanceOf(SubscriptionResource.class, result.getFirst(), "Result should be of type SubscriptionResource");

        var mockSubscription = mongoDocument.getSpec().getSubscription();
        var resultSubscription = result.getFirst().getSpec().getSubscription();

        assertEquals(mockSubscription.getSubscriptionId(), resultSubscription.getSubscriptionId(), "SubscriptionId should match");
        assertEquals(mockSubscription.getSubscriberId(), resultSubscription.getSubscriberId(), "SubscriberId should match");
        assertEquals(mockSubscription.getPublisherId(), resultSubscription.getPublisherId(), "PublisherId should match");
        assertEquals(mockSubscription.getDeliveryType(), resultSubscription.getDeliveryType(), "DeliveryType should match");
        assertEquals(mockSubscription.getType(), resultSubscription.getType(), "Type should match");
        assertEquals(mockSubscription.getCallback(), resultSubscription.getCallback(), "Callback should match");
    }

    @Test
    void testMapSubscriptionsFallback() throws JsonCacheException {

        // Prepare test data and simulate Hazelcast map unavailability
        var mockMap = jsonCacheService.getMap();
        when(mockMap.values(any())).thenThrow(new HazelcastClientOfflineException());

        // Call method getQuery to map subscriptions for fallback scenario
        Query query = Query.builder(SubscriptionMongoDocument.class)
                .addMatcher("spec.subscription.type" , TEST_SUBSCRIPTION_TYPE)
                .build();
        List<SubscriptionResource> result = jsonCacheService.getQuery(query);

        // Verify results
        assertNull(result, "Result should be null");
    }

    // Helper method to create a mock SubscriptionMongoDocument
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
        document.setSpec(spec);
        return document;
    }
}
