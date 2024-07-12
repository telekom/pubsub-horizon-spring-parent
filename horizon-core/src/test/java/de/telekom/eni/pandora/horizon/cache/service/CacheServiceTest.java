// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.autoconfigure.cache.CacheAutoConfiguration;
import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.utils.CacheServiceDummy;
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

    private static CacheServiceDummy dummy;

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
        System.out.println(cacheService.getValues().toString());

        Optional<CacheServiceDummy> retrievedDummy = cacheService.get(dummy.getKey());
        assertTrue(retrievedDummy.isPresent());
        assertEquals(dummy.getKey(), retrievedDummy.get().getKey());
    }

    @Test
    @DisplayName("Update cache")
    @Order(3)
    void update() {
        assertDoesNotThrow(() -> {
            dummy.setValue("fizzbuzz");
            cacheService.update(dummy);
        });
    }

    @Test
    @DisplayName("Verify cache update")
    @Order(4)
    void verify() {
        Optional<CacheServiceDummy> subscription = cacheService.get(dummy.getKey());
        assertTrue(subscription.isPresent());
        assertEquals(dummy.getKey(), subscription.get().getKey());
    }

    @Test
    @DisplayName("Perform SQL query")
    @Order(5)
    void executeQuery() {
        var query = Query.builder(CacheServiceDummy.class).addMatchers("value", dummy.getValue()).build();
        var expectedQuery = format("value = %s", dummy.getValue());
        assertEquals(expectedQuery, query.toString());

        List<CacheServiceDummy> result = cacheService.getWithQuery(query);
        assertFalse(result.isEmpty());
        assertEquals(dummy.getKey(), result.getFirst().getKey());
    }

    @Test
    @DisplayName("Remove chache entry")
    @Order(6)
    void remove() {
        cacheService.remove(dummy.getKey());

        Optional<CacheServiceDummy> subscription = cacheService.get(dummy.getKey());
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

    static CacheServiceDummy createDummy() {
        return new CacheServiceDummy("foo", "bar");
    }

}