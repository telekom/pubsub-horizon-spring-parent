package de.telekom.eni.pandora.horizon.cache.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import de.telekom.eni.pandora.horizon.cache.fallback.JsonCacheFallback;
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
                "testMap",
                eventPublisher
        );
        jsonCacheService.setJsonCacheFallback(new SubscriptionCacheMongoFallback(subscriptionsMongoRepo));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetQueryHazelcastAvailable() throws JsonCacheException {

        // Prepare test data and simulate Hazelcast
        IMap<String, HazelcastJsonValue> mockMap = mock(IMap.class);
        when(hazelcastInstance.<String, HazelcastJsonValue>getMap("testMap")).thenReturn(mockMap);

        HazelcastJsonValue mockValue = new HazelcastJsonValue("{\"spec\":{\"subscription\":{\"subscriptionId\":\"123\"}}}");
        Query query = Query.builder(SubscriptionResource.class)
                .addMatcher("spec.subscription.subscriptionId", "123")
                .build();

        when(mockMap.values(query.toSqlPredicate())).thenReturn(List.of(mockValue));
        when(mockMap.size()).thenReturn(1);

        // Call method to test
        List<SubscriptionResource> result = jsonCacheService.getQuery(query);

        // Verify results
        verify(hazelcastInstance, times(1)).getMap("testMap");
        verify(mockMap, times(1)).values(any());
        assertFalse(result.isEmpty());
        assertEquals("123", result.getFirst().getSpec().getSubscription().getSubscriptionId());
    }

    @Test
    void testGetQueryFallback() throws JsonCacheException {

        // Prepare test data and simulate Hazelcast map unavailability
        when(hazelcastInstance.getMap("testMap")).thenThrow(new RuntimeException("Hazelcast map unavailable"));
        SubscriptionMongoDocument mockDocument = createMockSubscriptionDocument("123", "testSubscriptionType");
        when(subscriptionsMongoRepo.findByType(any())).thenReturn(List.of(mockDocument));

        // Call method to test
        Query query = Query.builder(SubscriptionMongoDocument.class)
                .addMatcher("type", "testSubscriptionType")
                .build();

        List<SubscriptionResource> cacheResult = jsonCacheService.getQuery(query);

        // Verify result
        verify(hazelcastInstance, times(1)).getMap("testMap");
        verify(subscriptionsMongoRepo, times(1)).findByType(any());
        assertFalse(cacheResult.isEmpty());
        assertEquals("123", cacheResult.getFirst().getSpec().getSubscription().getSubscriptionId());
    }

    @Test
    void testGetByKeyHazelcastAvailable() throws JsonCacheException {

        // Prepare test data and simulate Hazelcast
        // noinspection unchecked
        IMap<String, HazelcastJsonValue> mockMap = mock(IMap.class);
        when(hazelcastInstance.<String, HazelcastJsonValue>getMap("testMap")).thenReturn(mockMap);

        HazelcastJsonValue mockValue = new HazelcastJsonValue("{\"spec\":{\"subscription\":{\"subscriptionId\":\"123\"}}}");
        when(mockMap.get("123")).thenReturn(mockValue);
        when(mockMap.size()).thenReturn(1);

        // Call method to test
        Optional<SubscriptionResource> result = jsonCacheService.getByKey("123");

        // Verify results
        verify(hazelcastInstance, times(1)).getMap("testMap");
        verify(mockMap, times(1)).get("123");
        assertFalse(result.isEmpty());
        assertEquals("123", result.get().getSpec().getSubscription().getSubscriptionId());
    }

    @Test
    void testGetByKeyFallback() throws JsonCacheException {

        // Prepare test data and simulate Hazelcast map unavailability
        SubscriptionMongoDocument mockDocument = createMockSubscriptionDocument("123", "testSubscriptionType");
        when(hazelcastInstance.getMap("testMap")).thenThrow(new RuntimeException("Hazelcast unavailable"));
        when(subscriptionsMongoRepo.findBySubscriptionId("123")).thenReturn(List.of(mockDocument));

        // Call method to test
        Optional<SubscriptionResource> result = jsonCacheService.getByKey("123");

        // Verify result
        verify(hazelcastInstance, times(1)).getMap("testMap");
        verify(subscriptionsMongoRepo, times(1)).findBySubscriptionId("123");
        assertTrue(result.isPresent());
        assertEquals("123", result.get().getSpec().getSubscription().getSubscriptionId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetAllHazelcastAvailable() throws JsonCacheException {

        // Prepare test data and simulate Hazelcast
        IMap<String, HazelcastJsonValue> mockMap = mock(IMap.class);
        when(hazelcastInstance.<String, HazelcastJsonValue>getMap("testMap")).thenReturn(mockMap);

        HazelcastJsonValue mockValue = new HazelcastJsonValue("{\"spec\":{\"subscription\":{\"subscriptionId\":\"123\"}}}");
        when(mockMap.values()).thenReturn(List.of(mockValue));
        when(mockMap.size()).thenReturn(1);

        // Call method to test
        List<SubscriptionResource> result = jsonCacheService.getAll();

        // Verify results
        verify(hazelcastInstance, times(1)).getMap("testMap");
        verify(mockMap, times(1)).values();
        assertFalse(result.isEmpty());
        assertEquals("123", result.getFirst().getSpec().getSubscription().getSubscriptionId());
    }

    @Test
    void testGetAllFallback() throws JsonCacheException {

        // Prepare test data and simulate Hazelcast map unavailability
        SubscriptionMongoDocument mockDocument = createMockSubscriptionDocument("123", "testSubscriptionType");
        when(hazelcastInstance.getMap("testMap")).thenThrow(new RuntimeException("Hazelcast unavailable"));
        when(subscriptionsMongoRepo.findAll()).thenReturn(List.of(mockDocument));

        // Call method to test
        List<SubscriptionResource> result = jsonCacheService.getAll();

        // Verify result
        verify(hazelcastInstance, times(1)).getMap("testMap");
        verify(subscriptionsMongoRepo, times(1)).findAll();
        assertFalse(result.isEmpty());
        assertEquals("123", result.getFirst().getSpec().getSubscription().getSubscriptionId());
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
    void testGetCacheMapHazelcastAvailable() throws JsonCacheException {
        // Prepare test data and simulate Hazelcast
        IMap<String, HazelcastJsonValue> mockMap = mock(IMap.class);
        when(hazelcastInstance.<String, HazelcastJsonValue>getMap("testMap")).thenReturn(mockMap);
        when(mockMap.size()).thenReturn(1);

        // Call method to test indirectly, because its private
        Optional<SubscriptionResource> result = jsonCacheService.getByKey("123");

        // Verify results
        verify(hazelcastInstance, times(1)).getMap("testMap");
        verify(mockMap, times(1)).addEntryListener(any(), eq(true));
        assertNotNull(result, "Map should be filled");
    }

    @Test
    void testGetCacheMapFallbackToMongoDB() throws JsonCacheException {

        // Prepare test data and simulate Hazelcast
        SubscriptionMongoDocument mockDocument = createMockSubscriptionDocument("123", "testSubscriptionType");
        when(hazelcastInstance.getMap("testMap")).thenThrow(new RuntimeException("Hazelcast unavailable"));
        when(subscriptionsMongoRepo.findBySubscriptionId("123")).thenReturn(List.of(mockDocument));

        // Call method to test indirectly, because its private
        Optional<SubscriptionResource> result = jsonCacheService.getByKey("123");

        // Verify results
        verify(hazelcastInstance, times(1)).getMap("testMap");
        assertNotNull(result, "Map should be filled");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testMapSubscriptions() throws JsonCacheException, JsonProcessingException {

        // Prepare test data and simulate Hazelcast
        IMap<String, HazelcastJsonValue> mockMap = mock(IMap.class);
        when(hazelcastInstance.<String, HazelcastJsonValue>getMap("testMap")).thenReturn(mockMap);

        SubscriptionMongoDocument mongoDocument = createMockSubscriptionDocument("subscription-123", "subscription-type");
        String jsonString = new ObjectMapper().writeValueAsString(mongoDocument);
        HazelcastJsonValue hazelcastJsonValue = new HazelcastJsonValue(jsonString);

        Query query = Query.builder(SubscriptionResource.class)
                .addMatcher("spec.subscription.subscriptionId", "123")
                .build();

        when(mockMap.values(query.toSqlPredicate())).thenReturn(List.of(hazelcastJsonValue));
        when(mockMap.size()).thenReturn(1);

        // Call method getQuery to map cache subscriptions
        List<SubscriptionResource> result = jsonCacheService.getQuery(query);

        // Verify results
        assertFalse(result.isEmpty());
        assertEquals(1, result.size());
        assertInstanceOf(SubscriptionResource.class, result.getFirst());

        var inputDoc = mongoDocument.getSpec().getSubscription();
        var resDoc = result.getFirst().getSpec().getSubscription();

        assertEquals(inputDoc.getSubscriptionId(), resDoc.getSubscriptionId());
        assertEquals(inputDoc.getSubscriberId(), resDoc.getSubscriberId());
        assertEquals(inputDoc.getPublisherId(), resDoc.getPublisherId());
        assertEquals(inputDoc.getDeliveryType(), resDoc.getDeliveryType());
        assertEquals(inputDoc.getType(), resDoc.getType());
        assertEquals(inputDoc.getCallback(), resDoc.getCallback());
    }

    @Test
    void testMapSubscriptionsFallback() throws JsonCacheException {

        // Prepare test data and simulate Hazelcast map unavailability
        when(hazelcastInstance.getMap("testMap")).thenThrow(new RuntimeException("Hazelcast map unavailable"));
        SubscriptionMongoDocument mockDocument = createMockSubscriptionDocument("123", "testSubscriptionType");
        when(subscriptionsMongoRepo.findByType(any())).thenReturn(List.of(mockDocument));

        // Call method getQuery to map subscriptions for fallback scenario
        Query query = Query.builder(SubscriptionMongoDocument.class)
                .addMatcher("type", "testSubscriptionType")
                .build();

        List<SubscriptionResource> result = jsonCacheService.getQuery(query);

        // Verify results
        assertNotNull(result);
        assertEquals(1, result.size());
        assertInstanceOf(SubscriptionResource.class, result.getFirst());

        var mongoDoc = mockDocument.getSpec().getSubscription();
        var resDoc = result.getFirst().getSpec().getSubscription();

        assertEquals(mongoDoc.getSubscriptionId(), resDoc.getSubscriptionId());
        assertEquals(mongoDoc.getSubscriberId(), resDoc.getSubscriberId());
        assertEquals(mongoDoc.getPublisherId(), resDoc.getPublisherId());
        assertEquals(mongoDoc.getDeliveryType(), resDoc.getDeliveryType());
        assertEquals(mongoDoc.getType(), resDoc.getType());
        assertEquals(mongoDoc.getCallback(), resDoc.getCallback());

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