// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import de.telekom.eni.pandora.horizon.cache.config.CacheProperties;
import de.telekom.eni.pandora.horizon.cache.service.DeDuplicationService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
public class DeDuplicationServiceTest {

    @Mock
    HazelcastInstance hazelcastInstance;

    CacheProperties cacheProperties;
    DeDuplicationService service;

    @BeforeEach
    void init() {
        this.cacheProperties = new CacheProperties();
        this.service = new DeDuplicationService(hazelcastInstance, cacheProperties);
    }

    @Test
    void testDeDuplicationCanBeEnabled() {
        assertFalse(service.isEnabled("test"));

        cacheProperties.setEnabled(true);
        cacheProperties.getDeDuplication().setEnabled(true);

        assertTrue(service.isEnabled("deduplication"));
    }


    @SuppressWarnings("unchecked")
    IMap<String, String> mockCache() {
        return (IMap<String, String>) Mockito.mock(IMap.class);
    }

    @Test
    void testItemsCanBeTracked() {
        final var cacheName = "test";
        final var uniqueKey = "my-unique-key";
        final var val = "some val";
        final var newVal = "some new val";

        cacheProperties.setEnabled(true);
        cacheProperties.getDeDuplication().setEnabled(true);

        IMap<String, String> mockedCache = mockCache();

        var realMap = new HashMap<String, String>();

        when(mockedCache.put(eq(uniqueKey), any(), anyLong(), eq(TimeUnit.SECONDS), anyLong(), eq(TimeUnit.SECONDS))).then(i -> realMap.put(uniqueKey, i.getArgument(1)));
        doReturn(mockedCache).when(hazelcastInstance).getMap(eq(cacheName));

        var oldValue = service.track(cacheName, uniqueKey, val);
        assertNull(oldValue);

        oldValue = service.track(cacheName, uniqueKey, newVal);
        assertEquals(oldValue, val);
    }

    @Test
    void testIsDuplicate() throws InterruptedException {
        final var cacheName = "test";
        final var uniqueKey = "my-unique-key";

        IMap<String, String> mockedCache = mockCache();

        var realMap = new HashMap<String, String>();

        when(mockedCache.put(eq(uniqueKey), any(), anyLong(), eq(TimeUnit.SECONDS), anyLong(), eq(TimeUnit.SECONDS))).then(i -> {
            long maxIdleArg = i.getArgument(4);

            new Thread(() -> {
                try {
                    Thread.sleep(Instant.ofEpochSecond(maxIdleArg).toEpochMilli());
                    realMap.remove(uniqueKey);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }).start();

            return realMap.put(uniqueKey, null);
        });
        when(mockedCache.containsKey(eq(uniqueKey))).thenAnswer(i -> realMap.containsKey(uniqueKey));
        doReturn(mockedCache).when(hazelcastInstance).getMap(eq(cacheName));

        service.track(cacheName, uniqueKey, null);

        var isDuplicate = service.isDuplicate(cacheName, uniqueKey);

        assertFalse(isDuplicate);

        cacheProperties.setEnabled(true);
        cacheProperties.getDeDuplication().setEnabled(true);
        // set max idle to 5 seconds
        cacheProperties.getDeDuplication().setMaxIdleInSeconds(5);

        isDuplicate = service.isDuplicate(cacheName, uniqueKey);

        assertFalse(isDuplicate);

        service.track(cacheName, uniqueKey, null);

        isDuplicate = service.isDuplicate(cacheName, uniqueKey);

        assertTrue(isDuplicate);

        Thread.sleep((cacheProperties.getDeDuplication().getMaxIdleInSeconds() + 1) * 1000);

        isDuplicate = service.isDuplicate(cacheName, uniqueKey);

        assertFalse(isDuplicate);
    }

    @Test
    void testItemsCanBeCleared() {
        final var cacheName = "test";
        final var uniqueKey = "my-unique-key";

        cacheProperties.setEnabled(true);
        cacheProperties.getDeDuplication().setEnabled(true);

        IMap<String, String> mockedCache = mockCache();

        var realMap = new HashMap<String, String>();

        when(mockedCache.put(eq(uniqueKey), any(), anyLong(), eq(TimeUnit.SECONDS), anyLong(), eq(TimeUnit.SECONDS))).then(i -> realMap.put(uniqueKey, null));
        when(mockedCache.containsKey(eq(uniqueKey))).thenAnswer(i -> realMap.containsKey(uniqueKey));
        when(mockedCache.remove(eq(uniqueKey))).then(i -> realMap.remove(uniqueKey));
        doReturn(mockedCache).when(hazelcastInstance).getMap(eq(cacheName));

        service.track(cacheName, uniqueKey, null);

        var isDuplicate = service.isDuplicate(cacheName, uniqueKey);

        assertTrue(isDuplicate);

        service.clear(cacheName, uniqueKey);

        isDuplicate = service.isDuplicate(cacheName, uniqueKey);

        assertFalse(isDuplicate);
    }
}
