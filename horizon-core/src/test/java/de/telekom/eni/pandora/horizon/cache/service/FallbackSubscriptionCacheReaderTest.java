// Copyright 2026 Deutsche Telekom AG
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.telekom.eni.pandora.horizon.exception.SubscriptionCacheReadException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionResource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void shouldUseFallbackWhenPrimaryIsNotReady() throws SubscriptionCacheReadException {
        var expected = List.of(new SubscriptionResource());
        when(primary.isReady()).thenReturn(false);
        when(fallback.findByEnvironmentAndEventType("production", "event-type")).thenReturn(expected);

        assertEquals(expected, reader.findByEnvironmentAndEventType("production", "event-type"));

        verify(primary, never()).findByEnvironmentAndEventType(any(), any());
    }

    @Test
    void shouldNotifyListenerOnlyForFallbackReads() throws SubscriptionCacheReadException {
        var fallbackReads = new java.util.concurrent.atomic.AtomicInteger();
        var localAllowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var countingReader = new FallbackSubscriptionCacheReader(primary, fallback, fallbackReads::incrementAndGet);
        when(primary.isReady()).thenAnswer(ignored -> localAllowed.get());
        when(primary.getById("subscription-id")).thenReturn(Optional.empty());
        when(fallback.getById("subscription-id")).thenReturn(Optional.empty());

        countingReader.getById("subscription-id");
        assertEquals(0, fallbackReads.get());

        localAllowed.set(false);
        countingReader.getById("subscription-id");
        countingReader.findByEnvironmentAndEventType("production", "event-type");
        assertEquals(2, fallbackReads.get());
    }

    @Test
    void shouldUseSharedReaderWhenLocalSnapshotIsNotInitialized() throws SubscriptionCacheReadException {
        var expected = Optional.of(new SubscriptionResource());
        when(primary.isReady()).thenReturn(false);
        when(fallback.getById("subscription-id")).thenReturn(expected);

        assertEquals(expected, reader.getById("subscription-id"));
        verify(primary, never()).getById(any());
    }

    @Test
    void shouldPropagateSharedFailureWithoutReadingStaleSnapshot() throws SubscriptionCacheReadException {
        var failure = new SubscriptionCacheReadException("shared unavailable");
        when(primary.isReady()).thenReturn(false);
        when(fallback.getById("subscription-id")).thenThrow(failure);

        assertSame(failure, assertThrows(SubscriptionCacheReadException.class,
                () -> reader.getById("subscription-id")));
        verify(primary, never()).getById(any());
    }

    @Test
    void shouldKeepGenuineEmptyLocalResultWhenFresh() throws SubscriptionCacheReadException {
        when(primary.isReady()).thenReturn(true);
        when(primary.getById("unknown-id")).thenReturn(Optional.empty());

        assertEquals(Optional.empty(), reader.getById("unknown-id"));
        verify(fallback, never()).getById(any());
    }

    @Test
    void shouldReportSharedReadinessWhenLocalSnapshotIsStale() {
        when(primary.isReady()).thenReturn(false);
        when(fallback.isReady()).thenReturn(false);

        assertFalse(reader.isReady());
    }

    @Test
    void shouldUseFallbackWhenPrimaryQueryFails() throws SubscriptionCacheReadException {
        var expected = List.of(new SubscriptionResource());
        when(primary.isReady()).thenReturn(true);
        when(primary.findByEnvironmentAndEventType("production", "event-type"))
                .thenThrow(new SubscriptionCacheReadException("primary unavailable", new RuntimeException()));
        when(fallback.findByEnvironmentAndEventType("production", "event-type")).thenReturn(expected);

        assertEquals(expected, reader.findByEnvironmentAndEventType("production", "event-type"));
    }

    @Test
    void shouldReturnPrimaryIdLookupResultWithoutFallback() throws SubscriptionCacheReadException {
        var expected = Optional.of(new SubscriptionResource());
        when(primary.isReady()).thenReturn(true);
        when(primary.getById("subscription-id")).thenReturn(expected);

        assertEquals(expected, reader.getById("subscription-id"));

        verify(fallback, never()).getById(any());
    }

    @Test
    void shouldUseFallbackWhenPrimaryReadinessCheckFails() throws SubscriptionCacheReadException {
        var expected = List.of(new SubscriptionResource());
        when(primary.isReady()).thenThrow(new IllegalStateException("primary unavailable"));
        when(fallback.findByEnvironmentAndEventType("production", "event-type")).thenReturn(expected);

        assertEquals(expected, reader.findByEnvironmentAndEventType("production", "event-type"));
    }

    @Test
    void shouldTranslateFallbackRuntimeFailure() throws SubscriptionCacheReadException {
        var cause = new IllegalStateException("fallback unavailable");
        when(primary.isReady()).thenReturn(false);
        when(fallback.findByEnvironmentAndEventType("production", "event-type")).thenThrow(cause);

        var exception = assertThrows(SubscriptionCacheReadException.class,
                () -> reader.findByEnvironmentAndEventType("production", "event-type"));

        assertSame(cause, exception.getCause());
    }

    @Test
    void shouldTranslateFallbackRuntimeFailureForIdLookup() throws SubscriptionCacheReadException {
        var cause = new IllegalStateException("fallback unavailable");
        when(primary.isReady()).thenReturn(false);
        when(fallback.getById("subscription-id")).thenThrow(cause);

        var exception = assertThrows(SubscriptionCacheReadException.class,
                () -> reader.getById("subscription-id"));

        assertSame(cause, exception.getCause());
    }

    @Test
    void shouldPropagateTypedFallbackFailureUnchanged() throws SubscriptionCacheReadException {
        var failure = new SubscriptionCacheReadException("fallback unavailable");
        when(primary.isReady()).thenReturn(false);
        when(fallback.getById("subscription-id")).thenThrow(failure);

        var exception = assertThrows(SubscriptionCacheReadException.class,
                () -> reader.getById("subscription-id"));

        assertSame(failure, exception);
    }

    @Test
    void shouldReportNotReadyWhenBothReadinessChecksFail() {
        when(primary.isReady()).thenThrow(new IllegalStateException("primary unavailable"));
        when(fallback.isReady()).thenThrow(new IllegalStateException("fallback unavailable"));

        assertFalse(reader.isReady());
    }

    @Test
    void shouldLogEachSwitchBetweenPrimaryAndFallbackOnce() {
        var primaryReady = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(primary.isReady()).thenAnswer(ignored -> primaryReady.get());
        when(fallback.isReady()).thenReturn(true);
        var appender = attachAppender();
        try {
            reader.isReady();
            reader.isReady();
            primaryReady.set(true);
            reader.isReady();
            reader.isReady();
            primaryReady.set(false);
            reader.isReady();
            reader.isReady();
            primaryReady.set(true);
            reader.isReady();

            assertThat(appender.list).extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .containsExactly(
                    tuple(Level.INFO, "Primary subscription cache is not ready yet; reads are served by the fallback"),
                    tuple(Level.INFO, "Primary subscription cache is ready; reads are served by the primary"),
                    tuple(Level.WARN, "Primary subscription cache is not ready; reads are served by the fallback"),
                    tuple(Level.INFO, "Primary subscription cache is ready again; reads are served by the primary"));
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void shouldLogRepeatedReadinessCheckFailuresOnlyOnceAtWarn() {
        var failing = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(primary.isReady()).thenAnswer(ignored -> {
            if (failing.get()) {
                throw new IllegalStateException("primary unavailable");
            }
            return true;
        });
        when(fallback.isReady()).thenReturn(true);
        var appender = attachAppender();
        try {
            reader.isReady();
            reader.isReady();
            reader.isReady();
            failing.set(false);
            reader.isReady();

            var checkFailures = appender.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("Failed to determine primary"))
                .toList();
            assertThat(checkFailures).extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.WARN, Level.DEBUG, Level.DEBUG);
            assertThat(checkFailures.get(0).getThrowableProxy()).isNotNull();
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .contains("Primary subscription cache readiness check recovered");
        } finally {
            detachAppender(appender);
        }
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        var logger = (Logger) LoggerFactory.getLogger(FallbackSubscriptionCacheReader.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        var logger = (Logger) LoggerFactory.getLogger(FallbackSubscriptionCacheReader.class);
        logger.detachAppender(appender);
        logger.setLevel(null);
        appender.stop();
    }
}
