// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import de.telekom.eni.pandora.horizon.cache.util.Query;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheReadException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class HazelcastCacheReaderTest {

    @Test
    void shouldDelegateReadinessToSharedCache() {
        var subscriptionCache = mock(JsonCacheService.class);
        when(subscriptionCache.isReady()).thenReturn(true);
        var reader = new HazelcastCacheReader(subscriptionCache);

        assertTrue(reader.isReady());

        verify(subscriptionCache).isReady();
    }

    @Test
    void shouldDelegateIdLookupToSharedCache() throws JsonCacheException, SubscriptionCacheReadException {
        var subscriptionCache = mock(JsonCacheService.class);
        var expected = Optional.of(new SubscriptionResource());
        when(subscriptionCache.getByKey("subscription-id")).thenReturn(expected);
        var reader = new HazelcastCacheReader(subscriptionCache);

        assertSame(expected, reader.getById("subscription-id"));

        verify(subscriptionCache).getByKey("subscription-id");
    }

    @Test
        void shouldTranslateEnvironmentAndEventTypeToSharedCacheQuery()
            throws JsonCacheException, SubscriptionCacheReadException {
        var subscriptionCache = mock(JsonCacheService.class);
        var expected = List.of(new SubscriptionResource());
        when(subscriptionCache.getQuery(org.mockito.ArgumentMatchers.any(Query.class))).thenReturn(expected);
        var reader = new HazelcastCacheReader(subscriptionCache);

        assertSame(expected, reader.findByEnvironmentAndEventType("production", "event-type"));

        var queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(subscriptionCache).getQuery(queryCaptor.capture());
        assertEquals("production", queryCaptor.getValue().getEnvironment());
        assertEquals("event-type", queryCaptor.getValue().getEventType());
    }

    @Test
    void shouldReturnEmptyResultsForInvalidLookupArguments() throws SubscriptionCacheReadException {
        var subscriptionCache = mock(JsonCacheService.class);
        var reader = new HazelcastCacheReader(subscriptionCache);

        assertEquals(Optional.empty(), reader.getById(null));
        assertEquals(List.of(), reader.findByEnvironmentAndEventType(null, "event-type"));
        assertEquals(List.of(), reader.findByEnvironmentAndEventType("production", null));
    }

    @Test
    void shouldTranslateJsonCacheFailure() throws JsonCacheException {
        var subscriptionCache = mock(JsonCacheService.class);
        var cause = new JsonCacheException("invalid json", new RuntimeException());
        when(subscriptionCache.getQuery(org.mockito.ArgumentMatchers.any(Query.class))).thenThrow(cause);
        var reader = new HazelcastCacheReader(subscriptionCache);

        var exception = assertThrows(SubscriptionCacheReadException.class,
                () -> reader.findByEnvironmentAndEventType("production", "event-type"));

        assertInstanceOf(JsonCacheException.class, exception.getCause());
    }

    @Test
    void shouldTranslateRuntimeCacheFailure() throws JsonCacheException {
        var subscriptionCache = mock(JsonCacheService.class);
        var cause = new IllegalStateException("cache unavailable");
        when(subscriptionCache.getByKey("subscription-id")).thenThrow(cause);
        var reader = new HazelcastCacheReader(subscriptionCache);

        var exception = assertThrows(SubscriptionCacheReadException.class,
                () -> reader.getById("subscription-id"));

        assertSame(cause, exception.getCause());
    }

    @Test
    void shouldRejectNullQueryResult() throws JsonCacheException {
        var subscriptionCache = mock(JsonCacheService.class);
        when(subscriptionCache.getQuery(org.mockito.ArgumentMatchers.any(Query.class))).thenReturn(null);
        var reader = new HazelcastCacheReader(subscriptionCache);

        assertThrows(SubscriptionCacheReadException.class,
                () -> reader.findByEnvironmentAndEventType("production", "event-type"));
    }

    @Test
    void shouldReportNotReadyWhenReadinessCheckFails() {
        var subscriptionCache = mock(JsonCacheService.class);
        when(subscriptionCache.isReady()).thenThrow(new IllegalStateException("cache unavailable"));
        var reader = new HazelcastCacheReader(subscriptionCache);

        assertFalse(reader.isReady());
    }
}