// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import de.telekom.eni.pandora.horizon.autoconfigure.cache.CacheAutoConfiguration;
import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.model.dummy.CacheDummy;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"horizon.cache.enabled=true"}, classes = {CacheAutoConfiguration.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JsonCacheServiceTest {

    HazelcastInstance hazelcastInstance;

    JsonCacheService<CacheDummy> cache;

    @Autowired
    public JsonCacheServiceTest(HazelcastInstance hazelcastInstance) {
        this.hazelcastInstance = hazelcastInstance;

        IMap<String, HazelcastJsonValue> dummyMap = hazelcastInstance.getMap("dummyMap");
        this.cache = new JsonCacheService<>(CacheDummy.class, dummyMap, new ObjectMapper());
    }

    @Test
    @Order(1)
    void set() {
        var dummy = new CacheDummy("bar");
        assertDoesNotThrow(() -> cache.set("foo", dummy));
    }

    @Test
    @Order(2)
    void getByKey() {
        assertDoesNotThrow(() -> {
            var dummy = cache.getByKey("foo");
            assertTrue(dummy.isPresent());
            assertEquals("bar", dummy.get().getFoo());
        });
    }

    @Test
    @Order(3)
    void getQuery() {
        var query = Query.builder(CacheDummy.class)
                .addMatcher("foo", "bar")
                .build();

        assertDoesNotThrow(() -> {
            var result = cache.getQuery(query);
            assertEquals(1, result.size());
            assertEquals("bar", result.getFirst().getFoo());
        });
    }

    @Test
    @Order(4)
    void getAll() {
        assertDoesNotThrow(() -> {
            var entries = cache.getAll();
            assertEquals(1, entries.size());
            assertEquals("bar", entries.getFirst().getFoo());
        });
    }


    @Test
    @Order(5)
    void remove() {
        cache.remove("foo");
        assertDoesNotThrow(() -> {
            var entries = cache.getAll();
            assertTrue(entries.isEmpty());
        });
    }

    @Test
    @Order(6)
    void getMap() {
        assertNotNull(cache.getMap());
    }

}