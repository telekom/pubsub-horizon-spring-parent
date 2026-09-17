// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.fallback;

import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.kubernetes.resource.Subscription;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResourceSpec;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionTrigger;
import de.telekom.eni.pandora.horizon.mongo.config.MongoProperties;
import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionMongoDocument;
import de.telekom.eni.pandora.horizon.mongo.repository.SubscriptionsMongoRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubscriptionCacheMongoFallbackTest {

    private SubscriptionsMongoRepo subscriptionsMongoRepo;
    
    private SubscriptionCacheMongoFallback subscriptionCacheMongoFallback;
    
    private static final String TEST_SUBSCRIPTION_ID = "123";
    
    private static final String TEST_SUBSCRIPTION_TYPE = "testSubscriptionType";
    
    private static final MongoProperties mongoProperties = new MongoProperties();


    @BeforeEach
    void setUp() {
        subscriptionsMongoRepo = mock(SubscriptionsMongoRepo.class);
        subscriptionCacheMongoFallback = new SubscriptionCacheMongoFallback(subscriptionsMongoRepo, mongoProperties);
    }

    @Test
    void testGetQuery() {

        // Prepare test data and simulate mongo db
        SubscriptionMongoDocument mockDocument = createMockSubscriptionDocument(TEST_SUBSCRIPTION_ID, TEST_SUBSCRIPTION_TYPE);
        when(subscriptionsMongoRepo.findByTypeAndEnvironment(any(), any())).thenReturn(List.of(mockDocument));

        // Call method to test
        Query query = Query.builder(SubscriptionMongoDocument.class)
                .addMatcher("spec.subscription.type", TEST_SUBSCRIPTION_TYPE)
                .addMatcher("spec.environment", "integration")
                .build();

        List<SubscriptionResource> cacheResult = subscriptionCacheMongoFallback.getQuery(query);

        // Verify result
        verify(subscriptionsMongoRepo, times(1)).findByTypeAndEnvironment(TEST_SUBSCRIPTION_TYPE, "integration");
        assertFalse(cacheResult. isEmpty(), "Result should be filled");
        assertEquals(TEST_SUBSCRIPTION_ID, cacheResult.getFirst().getSpec().getSubscription().getSubscriptionId(), "SubscriptionId should match");
    }

    @Test
    void testGetByKey() {

        // Prepare test data and simulate mongo db
        SubscriptionMongoDocument mockDocument = createMockSubscriptionDocument(TEST_SUBSCRIPTION_ID, TEST_SUBSCRIPTION_TYPE);
        when(subscriptionsMongoRepo.findBySubscriptionId(TEST_SUBSCRIPTION_ID)).thenReturn(List.of(mockDocument));

        // Call method to test
        Optional<SubscriptionResource> cacheResult = subscriptionCacheMongoFallback.getByKey(TEST_SUBSCRIPTION_ID);

        // Verify result
        verify(subscriptionsMongoRepo, times(1)).findBySubscriptionId(TEST_SUBSCRIPTION_ID);
        assertFalse(cacheResult. isEmpty(), "Result should be filled");
        assertEquals(TEST_SUBSCRIPTION_ID, cacheResult.get().getSpec().getSubscription().getSubscriptionId(), "SubscriptionId should match");
    }

    @Test
    void testGetAll() {

        // Prepare test data and simulate mongo db
        SubscriptionMongoDocument mockDocument = createMockSubscriptionDocument(TEST_SUBSCRIPTION_ID, TEST_SUBSCRIPTION_TYPE);
        when(subscriptionsMongoRepo.findAll()).thenReturn(List.of(mockDocument));

        // Call method to test
        List<SubscriptionResource> cacheResult  = subscriptionCacheMongoFallback.getAll();

        // Verify result
        verify(subscriptionsMongoRepo, times(1)).findAll();
        assertFalse(cacheResult. isEmpty(), "Result should be filled");
        assertEquals(TEST_SUBSCRIPTION_ID, cacheResult.getFirst().getSpec().getSubscription().getSubscriptionId(), "SubscriptionId should match");
    }

    @Test
    void testMapSubscriptionsFallback() {

        // Prepare test data and simulate mongo db
        SubscriptionMongoDocument mockDocument = createMockSubscriptionDocument(TEST_SUBSCRIPTION_ID, TEST_SUBSCRIPTION_TYPE);
        when(subscriptionsMongoRepo.findByType(any())).thenReturn(List.of(mockDocument));

        // Call method to test
        Query query = Query.builder(SubscriptionMongoDocument.class)
                .addMatcher("spec.subscription.type" , TEST_SUBSCRIPTION_TYPE)
                .build();

        List<SubscriptionResource> cacheResult = subscriptionCacheMongoFallback.getQuery(query);

        // Verify results
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
        trigger.setSelectionFilter(java.util.Map.of("method", "POST"));
        var publisherTrigger = new SubscriptionTrigger();
        publisherTrigger.setResponseFilterMode(SubscriptionTrigger.ResponseFilterMode.EXCLUDE);
        document.getSpec().getSubscription().setTrigger(trigger);
        document.getSpec().getSubscription().setPublisherTrigger(publisherTrigger);
        document.getSpec().setEnvironment("integration");

        var result = subscriptionCacheMongoFallback.mapMongoSubscriptions(List.of(document)).getFirst();

        assertSame(trigger, result.getSpec().getSubscription().getTrigger());
        assertSame(publisherTrigger, result.getSpec().getSubscription().getPublisherTrigger());
        assertEquals("integration", result.getSpec().getEnvironment());
        assertEquals("POST", result.getSpec().getSubscription().getTrigger().getSelectionFilter().get("method"));
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