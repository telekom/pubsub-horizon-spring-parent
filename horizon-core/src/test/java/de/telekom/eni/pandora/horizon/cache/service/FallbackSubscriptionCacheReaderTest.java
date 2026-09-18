// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FallbackSubscriptionCacheReaderTest {

    private final SubscriptionCacheReader primary = mock(SubscriptionCacheReader.class);
    private final SubscriptionCacheReader fallback = mock(SubscriptionCacheReader.class);
    private final FallbackSubscriptionCacheReader reader = new FallbackSubscriptionCacheReader(primary, fallback);

    @Test
    void shouldUseFallbackWhenPrimaryIsNotReady() throws JsonCacheException {
        var expected = List.of(new SubscriptionResource());
        when(primary.isReady()).thenReturn(false);
        when(fallback.findByEnvironmentAndEventType("production", "event-type")).thenReturn(expected);

        assertEquals(expected, reader.findByEnvironmentAndEventType("production", "event-type"));

        verify(primary, never()).findByEnvironmentAndEventType(any(), any());
    }

    @Test
    void shouldUseFallbackWhenPrimaryQueryFails() throws JsonCacheException {
        var expected = List.of(new SubscriptionResource());
        when(primary.isReady()).thenReturn(true);
        when(primary.findByEnvironmentAndEventType("production", "event-type"))
                .thenThrow(new JsonCacheException("primary unavailable", new RuntimeException()));
        when(fallback.findByEnvironmentAndEventType("production", "event-type")).thenReturn(expected);

        assertEquals(expected, reader.findByEnvironmentAndEventType("production", "event-type"));
    }

    @Test
    void shouldReturnPrimaryIdLookupResultWithoutFallback() throws JsonCacheException {
        var expected = Optional.of(new SubscriptionResource());
        when(primary.isReady()).thenReturn(true);
        when(primary.getById("subscription-id")).thenReturn(expected);

        assertEquals(expected, reader.getById("subscription-id"));

        verify(fallback, never()).getById(any());
    }
}
