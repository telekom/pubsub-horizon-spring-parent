// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FallbackCacheServiceTest {

    private final CacheReader<SubscriptionResource> primary = mock(CacheReader.class);
    private final CacheReader<SubscriptionResource> fallback = mock(CacheReader.class);
    private final FallbackCacheService<SubscriptionResource> service = new FallbackCacheService<>(primary, fallback);

    @Test
    void shouldUseFallbackWhenPrimaryIsNotReady() throws JsonCacheException {
        var expected = List.of(new SubscriptionResource());
        when(primary.isReady()).thenReturn(false);
        when(fallback.getQuery(any(Query.class))).thenReturn(expected);

        assertEquals(expected, service.getQuery(mock(Query.class)));
    }

    @Test
    void shouldUseFallbackWhenPrimaryFails() throws JsonCacheException {
        var expected = List.of(new SubscriptionResource());
        when(primary.isReady()).thenReturn(true);
        when(primary.getQuery(any(Query.class))).thenThrow(new JsonCacheException("primary unavailable", new RuntimeException()));
        when(fallback.getQuery(any(Query.class))).thenReturn(expected);

        assertEquals(expected, service.getQuery(mock(Query.class)));
    }
}
