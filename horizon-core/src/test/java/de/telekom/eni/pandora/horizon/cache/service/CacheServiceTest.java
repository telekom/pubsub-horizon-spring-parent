// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.autoconfigure.cache.CacheAutoConfiguration;
import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.model.meta.CircuitBreakerMessage;
import de.telekom.eni.pandora.horizon.model.meta.CircuitBreakerStatus;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;

import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"horizon.cache.enabled=true"}, classes = {CacheAutoConfiguration.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CacheServiceTest {

    @Autowired
    private CacheService cacheService;

    private static CircuitBreakerMessage dummy;

    @BeforeAll
    static void setup() {
        CacheServiceTest.dummy = createDummy();
    }

    @Test
    @Order(1)
    @DisplayName("Insert into cache")
    void create() {
        assertDoesNotThrow(() -> {
            cacheService.update(dummy);
        });
    }

    @Test
    @DisplayName("Get from cache")
    @Order(2)
    void get() {
        Optional<CircuitBreakerMessage> subscription = cacheService.get(dummy.getSubscriptionId());
        assertTrue(subscription.isPresent());
        assertEquals(dummy.getSubscriptionId(), subscription.get().getSubscriptionId());
    }

    @Test
    @DisplayName("Update cache")
    @Order(3)
    void update() {
        assertDoesNotThrow(() -> {
            dummy.setCallbackUrl("https://example.com/callback-2");
            cacheService.update(dummy);
        });
    }

    @Test
    @DisplayName("Verify cache update")
    @Order(4)
    void verify() {
        Optional<CircuitBreakerMessage> subscription = cacheService.get(dummy.getSubscriptionId());
        assertTrue(subscription.isPresent());
        assertEquals(dummy.getCallbackUrl(), subscription.get().getCallbackUrl());
    }

    @Test
    @DisplayName("Perform SQL query")
    @Order(5)
    void executeQuery() {
        var query = Query.builder(CircuitBreakerMessage.class).addMatchers("subscriptionId", dummy.getSubscriptionId()).build();
        var expectedQuery = format("subscriptionId = %s", dummy.getSubscriptionId());
        assertEquals(expectedQuery, query.toString());

        List<CircuitBreakerMessage> result = cacheService.getWithQuery(query);
        assertFalse(result.isEmpty());
        assertEquals(dummy.getSubscriptionId(), result.get(0).getSubscriptionId());
    }

    @Test
    @DisplayName("Remove chache entry")
    @Order(6)
    void remove() {
        cacheService.remove(dummy.getSubscriptionId());

        Optional<CircuitBreakerMessage> subscription = cacheService.get(dummy.getSubscriptionId());
        assertFalse(subscription.isPresent());
    }

    @Test
    @DisplayName("Clear cache")
    @Order(7)
    void clear() {
        create(); // add an entry again

        var list = cacheService.getValues();
        assertFalse(list.isEmpty());

        cacheService.clear();

        list = cacheService.getValues();
        assertTrue(list.isEmpty());
    }

    static CircuitBreakerMessage createDummy() {
        return new CircuitBreakerMessage("subscription-id", CircuitBreakerStatus.OPEN, "callback-url", "environment");
    }
}