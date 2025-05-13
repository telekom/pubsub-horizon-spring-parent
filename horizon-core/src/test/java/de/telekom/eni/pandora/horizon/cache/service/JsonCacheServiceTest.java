// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.client.HazelcastClientOfflineException;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import de.telekom.eni.pandora.horizon.cache.fallback.SubscriptionCacheMongoFallback;
import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.Subscription;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResourceSpec;
import de.telekom.eni.pandora.horizon.model.dummy.CacheDummy;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionMongoDocument;
import de.telekom.eni.pandora.horizon.mongo.repository.SubscriptionsMongoRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JsonCacheServiceTest {

    private HazelcastInstance hazelcastInstance;
    private SubscriptionsMongoRepo subscriptionsMongoRepo;
    private JsonCacheService<SubscriptionResource> jsonCacheService;

    private static final String TEST_MAP_NAME = "testMap";
    private static final String TEST_SUBSCRIPTION_ID = "123";
    private static final String TEST_SUBSCRIPTION_TYPE = "testSubscriptionType";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        hazelcastInstance = mock(HazelcastInstance.class);
        subscriptionsMongoRepo = mock(SubscriptionsMongoRepo.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        IMap<String, HazelcastJsonValue> mockMap = mock(IMap.class);

        jsonCacheService = new JsonCacheService<>(
                SubscriptionResource.class,
                mockMap,
                new ObjectMapper(),
                hazelcastInstance,
                TEST_MAP_NAME,
                eventPublisher
        );
        jsonCacheService.setJsonCacheFallback(new SubscriptionCacheMongoFallback(subscriptionsMongoRepo));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetQueryHazelcastAvailable() throws JsonCacheException {

        // Prepare test data and simulate Hazelcast
        IMap<String, HazelcastJsonValue> mockMap = mock(IMap.class);
        when(hazelcastInstance.<String, HazelcastJsonValue>getMap(TEST_MAP_NAME)).thenReturn(mockMap);

        HazelcastJsonValue mockValue = new HazelcastJsonValue("{\"spec\":{\"subscription\":{\"subscriptionId\":\"123\"}}}");
        Query query = Query.builder(SubscriptionResource.class)
                .addMatcher("spec.subscription.subscriptionId", TEST_SUBSCRIPTION_ID)
                .build();

        when(mockMap.values(query.toSqlPredicate())).thenReturn(List.of(mockValue));
        when(mockMap.size()).thenReturn(1);

        // Call method to test
        List<SubscriptionResource> result = jsonCacheService.getQuery(query);

        // Verify results
        verify(hazelcastInstance, times(1)).getMap(TEST_MAP_NAME);
        verify(mockMap, times(1)).values(query.toSqlPredicate());
        assertFalse(result.isEmpty(), "Result should be filled");
        assertEquals(TEST_SUBSCRIPTION_ID, result.getFirst().getSpec().getSubscription().getSubscriptionId(), "SubscriptionId should match");
    }

    @Test
    void testGetQueryFallback() throws JsonCacheException {

        // Prepare test data and simulate Hazelcast map unavailability
        when(hazelcastInstance.getMap(TEST_MAP_NAME)).thenThrow(new HazelcastClientOfflineException());
        SubscriptionMongoDocument mockDocument = createMockSubscriptionDocument(TEST_SUBSCRIPTION_ID, TEST_SUBSCRIPTION_TYPE);
        when(subscriptionsMongoRepo.findByType(TEST_SUBSCRIPTION_TYPE)).thenReturn(List.of(mockDocument));

        // Call method to test
        Query query = Query.builder(SubscriptionMongoDocument.class)
                .addMatcher("spec.subscription.type", TEST_SUBSCRIPTION_TYPE)
                .build();

        List<SubscriptionResource> cacheResult = jsonCacheService.getQuery(query);

        // Verify result
        verify(hazelcastInstance, times(1)).getMap(TEST_MAP_NAME);
        verify(subscriptionsMongoRepo, times(1)).findByType(TEST_SUBSCRIPTION_TYPE);
        assertFalse(cacheResult.isEmpty(), "Result should be filled");
        assertEquals(TEST_SUBSCRIPTION_ID, cacheResult.getFirst().getSpec().getSubscription().getSubscriptionId(), "SubscriptionId should match");
    }

    @Test
    void testGetByKeyHazelcastAvailable() throws JsonCacheException {

        // Prepare test data and simulate Hazelcast
        // noinspection unchecked
        IMap<String, HazelcastJsonValue> mockMap = mock(IMap.class);
        when(hazelcastInstance.<String, HazelcastJsonValue>getMap(TEST_MAP_NAME)).thenReturn(mockMap);

        HazelcastJsonValue mockValue = new HazelcastJsonValue("{\"spec\":{\"subscription\":{\"subscriptionId\":\"123\"}}}");
        when(mockMap.get(TEST_SUBSCRIPTION_ID)).thenReturn(mockValue);
        when(mockMap.size()).thenReturn(1);

        // Call method to test
        Optional<SubscriptionResource> result = jsonCacheService.getByKey(TEST_SUBSCRIPTION_ID);

        // Verify results
        verify(hazelcastInstance, times(1)).getMap(TEST_MAP_NAME);
        verify(mockMap, times(1)).get(TEST_SUBSCRIPTION_ID);
        assertFalse(result.isEmpty(), "Result should be filled");
        assertEquals(TEST_SUBSCRIPTION_ID, result.get().getSpec().getSubscription().getSubscriptionId(), "SubscriptionId should match");
    }

    @Test
    void testGetByKeyFallback() throws JsonCacheException {

        // Prepare test data and simulate Hazelcast map unavailability
        SubscriptionMongoDocument mockDocument = createMockSubscriptionDocument(TEST_SUBSCRIPTION_ID, TEST_SUBSCRIPTION_TYPE);
        when(hazelcastInstance.getMap(TEST_MAP_NAME)).thenThrow(new HazelcastClientOfflineException());
        when(subscriptionsMongoRepo.findBySubscriptionId(TEST_SUBSCRIPTION_ID)).thenReturn(List.of(mockDocument));

        // Call method to test
        Optional<SubscriptionResource> result = jsonCacheService.getByKey(TEST_SUBSCRIPTION_ID);

        // Verify result
        verify(hazelcastInstance, times(1)).getMap(TEST_MAP_NAME);
        verify(subscriptionsMongoRepo, times(1)).findBySubscriptionId(TEST_SUBSCRIPTION_ID);
        assertTrue(result.isPresent(), "Result should be present");
        assertEquals(TEST_SUBSCRIPTION_ID, result.get().getSpec().getSubscription().getSubscriptionId(), "SubscriptionId should match");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetAllHazelcastAvailable() throws JsonCacheException {

        // Prepare test data and simulate Hazelcast
        IMap<String, HazelcastJsonValue> mockMap = mock(IMap.class);
        when(hazelcastInstance.<String, HazelcastJsonValue>getMap(TEST_MAP_NAME)).thenReturn(mockMap);

        HazelcastJsonValue mockValue = new HazelcastJsonValue("{\"spec\":{\"subscription\":{\"subscriptionId\":\"123\"}}}");
        when(mockMap.values()).thenReturn(List.of(mockValue));
        when(mockMap.size()).thenReturn(1);

        // Call method to test
        List<SubscriptionResource> result = jsonCacheService.getAll();

        // Verify results
        verify(hazelcastInstance, times(1)).getMap(TEST_MAP_NAME);
        verify(mockMap, times(1)).values();
        assertFalse(result.isEmpty(), "Result should be filled");
        assertEquals(TEST_SUBSCRIPTION_ID, result.getFirst().getSpec().getSubscription().getSubscriptionId(), "SubscriptionId should match");
    }

    @Test
    void testGetAllFallback() throws JsonCacheException {

        // Prepare test data and simulate Hazelcast map unavailability
        SubscriptionMongoDocument mockDocument = createMockSubscriptionDocument(TEST_SUBSCRIPTION_ID, TEST_SUBSCRIPTION_TYPE);
        when(hazelcastInstance.getMap(TEST_MAP_NAME)).thenThrow(new HazelcastClientOfflineException());
        when(subscriptionsMongoRepo.findAll()).thenReturn(List.of(mockDocument));

        // Call method to test
        List<SubscriptionResource> result = jsonCacheService.getAll();

        // Verify result
        verify(hazelcastInstance, times(1)).getMap(TEST_MAP_NAME);
        verify(subscriptionsMongoRepo, times(1)).findAll();
        assertFalse(result.isEmpty(), "Result should be filled");
        assertEquals(TEST_SUBSCRIPTION_ID, result.getFirst().getSpec().getSubscription().getSubscriptionId(), "SubscriptionId should match");
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
    void testGetCacheMap() throws JsonCacheException {
        
        // Prepare test data and simulate Hazelcast
        IMap<String, HazelcastJsonValue> mockMap = mock(IMap.class);
        when(hazelcastInstance.<String, HazelcastJsonValue>getMap(TEST_MAP_NAME)).thenReturn(mockMap);
        when(mockMap.size()).thenReturn(1);

        // Call method to test indirectly, because its private
        Optional<SubscriptionResource> result = jsonCacheService.getByKey(TEST_SUBSCRIPTION_ID);

        // Verify results
        verify(hazelcastInstance, times(1)).getMap(TEST_MAP_NAME);
        verify(mockMap, times(1)).addEntryListener(any(), eq(true));
        assertNotNull(result, "Map should be filled");
    }

    @Test
    void testGetCacheMapFallback() throws JsonCacheException {

        // Prepare test data and simulate Hazelcast
        SubscriptionMongoDocument mockDocument = createMockSubscriptionDocument(TEST_SUBSCRIPTION_ID, TEST_SUBSCRIPTION_TYPE);
        when(hazelcastInstance.getMap(TEST_MAP_NAME)).thenThrow(new HazelcastClientOfflineException());
        when(subscriptionsMongoRepo.findBySubscriptionId(TEST_SUBSCRIPTION_ID)).thenReturn(List.of(mockDocument));

        // Call method to test indirectly, because its private
        Optional<SubscriptionResource> result = jsonCacheService.getByKey(TEST_SUBSCRIPTION_ID);

        // Verify results
        verify(hazelcastInstance, times(1)).getMap(TEST_MAP_NAME);
        assertNotNull(result, "Map should be filled");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testMapSubscriptions() throws JsonCacheException, JsonProcessingException {

        // Prepare test data and simulate Hazelcast
        IMap<String, HazelcastJsonValue> mockMap = mock(IMap.class);
        when(hazelcastInstance.<String, HazelcastJsonValue>getMap(TEST_MAP_NAME)).thenReturn(mockMap);

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
        when(hazelcastInstance.getMap(TEST_MAP_NAME)).thenThrow(new HazelcastClientOfflineException());
        SubscriptionMongoDocument mockDocument = createMockSubscriptionDocument(TEST_SUBSCRIPTION_ID, TEST_SUBSCRIPTION_TYPE);
        when(subscriptionsMongoRepo.findByType(any())).thenReturn(List.of(mockDocument));

        // Call method getQuery to map subscriptions for fallback scenario
        Query query = Query.builder(SubscriptionMongoDocument.class)
                .addMatcher("spec.subscription.type" , TEST_SUBSCRIPTION_TYPE)
                .build();
        List<SubscriptionResource> result = jsonCacheService.getQuery(query);

        // Verify results
        assertNotNull(result, "Result should be filled");
        assertEquals(1, result.size(), "Size should be 1");
        assertInstanceOf(SubscriptionResource.class, result.getFirst(), "Result should be of type SubscriptionResource");

        var mockSubscription = mockDocument.getSpec().getSubscription();
        var resultSubscription = result.getFirst().getSpec().getSubscription();

        assertEquals(mockSubscription.getSubscriptionId(), resultSubscription.getSubscriptionId(), "SubscriptionId should match");
        assertEquals(mockSubscription.getSubscriberId(), resultSubscription.getSubscriberId(), "SubscriberId should match");
        assertEquals(mockSubscription.getPublisherId(), resultSubscription.getPublisherId(), "PublisherId should match");
        assertEquals(mockSubscription.getDeliveryType(), resultSubscription.getDeliveryType(), "DeliveryType should match");
        assertEquals(mockSubscription.getType(), resultSubscription.getType(), "Type should match");
        assertEquals(mockSubscription.getCallback(), resultSubscription.getCallback(), "Callback should match");

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