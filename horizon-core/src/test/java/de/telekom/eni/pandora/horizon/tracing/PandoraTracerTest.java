// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.tracing;

import brave.ScopedSpan;
import brave.Span;
import brave.Tracer;
import brave.Tracing;
import brave.propagation.CurrentTraceContext;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PandoraTracerTest {

    @Mock
    Environment environment;

    @Mock
    Tracing tracing;

    @Mock
    Tracer tracer;

    @Mock
    ScopedSpan scopedSpan;

    @Mock
    Span span;

    @Mock
    ScopedDebugSpanWrapper scopedDebugSpanWrapper;

    @Mock
    CurrentTraceContext currentTraceContext;

    @Mock
    TraceContext traceContext;

    @Mock
    TracingProperties tracingProperties;

    @Captor
    ArgumentCaptor<TraceContextOrSamplingFlags> traceContextCaptor;

    @Mock
    Tracer.SpanInScope spanInScope;

    private static final String traceId = "c81c0251fbdb13d6ac9625822f0a931f";
    private static final String spanId = "ed0ef246062c027c";
    private static final SamplingState samplingState = SamplingState.SAMPLED;
    private static final String parentSpanId = "979c4f976953df7b";

    private static final String b3 = "c81c0251fbdb13d6ac9625822f0a931f-ed0ef246062c027c-1-979c4f976953df7b";

    @BeforeEach
    void init() {
    }

    @Test
    void testStartScopedSpan() {
        when(environment.getProperty("spring.application.name")).thenReturn("foobar");
        when(tracer.startScopedSpan(anyString())).thenReturn(scopedSpan);

        var pandoraTracer = new PandoraTracer(environment, tracing, tracer, tracingProperties);

        var name = "foobar";
        var span = pandoraTracer.startScopedSpan(name);

        assertNotNull(span);
        verify(span).tag("component", "foobar");
    }

    @Test
    void testStartScopedDebugSpanWhenDebugModeOn() {
        when(environment.getProperty("spring.application.name")).thenReturn("foobar");
        when(tracer.startScopedSpan(anyString())).thenReturn(scopedSpan);
        when(tracingProperties.isDebugEnabled()).thenReturn(true);

        var pandoraTracer = new PandoraTracer(environment, tracing, tracer, tracingProperties);

        var name = "foobar";
        var span = pandoraTracer.startScopedDebugSpan(name);

        verify(tracer).startScopedSpan(name);
    }

    @Test
    void testNotStartScopedDebugSpanWhenDebugModeOff() {
        when(tracingProperties.isDebugEnabled()).thenReturn(false);
        when(tracing.currentTraceContext()).thenReturn(currentTraceContext);
        when(currentTraceContext.get()).thenReturn(traceContext);
        when(traceContext.debug()).thenReturn(false);

        var pandoraTracer = new PandoraTracer(environment, tracing, tracer, tracingProperties);

        var name = "foobar";
        var span = pandoraTracer.startScopedDebugSpan(name);

        verify(tracer, never()).startScopedSpan(name);
    }

    @Test
    void testStartScopedDebugSpanDebugWhenModeOffAndDebugTrace() {
        when(environment.getProperty("spring.application.name")).thenReturn("foobar");
        when(tracer.startScopedSpan(anyString())).thenReturn(scopedSpan);
        when(tracing.currentTraceContext()).thenReturn(currentTraceContext);
        when(currentTraceContext.get()).thenReturn(traceContext);
        when(traceContext.debug()).thenReturn(true);

        var pandoraTracer = new PandoraTracer(environment, tracing, tracer, tracingProperties);

        var name = "foobar";
        var span = pandoraTracer.startScopedDebugSpan(name);

        verify(tracer).startScopedSpan(name);
    }

    @Test
    void testCreateTraceContextAndSpan() {
        when(tracer.nextSpan(any(TraceContextOrSamplingFlags.class))).thenReturn(span);

        var pandoraTracer = new PandoraTracer(environment, tracing, tracer, tracingProperties);
        var createdSpan = pandoraTracer.createTraceContextAndSpan(traceId, spanId, samplingState, parentSpanId);

        verify(tracer).nextSpan(traceContextCaptor.capture());

        var extractedContext = traceContextCaptor.getValue();

        assertNotNull(extractedContext);
        assertNotNull(extractedContext.context());
        assertEquals(traceId, extractedContext.context().traceIdString());
        assertEquals(spanId, extractedContext.context().spanIdString());
        assertTrue(extractedContext.context().sampled());
        assertEquals(parentSpanId, extractedContext.context().parentIdString());
    }

    @Test
    void testCreateTraceContextAndSpanDebug() {
        var debugSamplingState = SamplingState.DEBUG;
        when(tracer.nextSpan(any(TraceContextOrSamplingFlags.class))).thenReturn(span);

        var pandoraTracer = new PandoraTracer(environment, tracing, tracer, tracingProperties);
        var createdSpan = pandoraTracer.createTraceContextAndSpan(traceId, spanId, debugSamplingState, parentSpanId);

        verify(tracer).nextSpan(traceContextCaptor.capture());

        var extractedContext = traceContextCaptor.getValue();

        assertNotNull(extractedContext);
        assertNotNull(extractedContext.context());
        assertEquals(traceId, extractedContext.context().traceIdString());
        assertEquals(spanId, extractedContext.context().spanIdString());
        assertTrue(extractedContext.context().debug());
        assertEquals(parentSpanId, extractedContext.context().parentIdString());
    }

    @Test
    void testCreateTraceContextAndSpanTooShortTraceId() {
        var context = Mockito.mock(TraceContext.class);
        var expectedTraceId = "3759dd21cf61e871e4520eb2794365cc";

        when(context.traceIdString()).thenReturn(expectedTraceId);
        when(span.context()).thenReturn(context);
        when(tracer.newTrace()).thenReturn(span);

        var pandoraTracer = new PandoraTracer(environment, tracing, tracer, tracingProperties);
        var createdSpan = pandoraTracer.createTraceContextAndSpan("123", null, null, null);

        verify(tracer, times(0)).nextSpan(any());
        verify(tracer).newTrace();

        assertNotEquals("123", createdSpan.context().traceIdString());
        assertEquals(expectedTraceId, createdSpan.context().traceIdString());
    }

    @Test
    void testCreateTraceContextAndSpanTooLongTraceId() {
        var pandoraTracer = new PandoraTracer(environment, tracing, tracer, tracingProperties);
        var createdSpan = pandoraTracer.createTraceContextAndSpan(traceId + "123", null, null, null);

        verify(tracer).nextSpan(traceContextCaptor.capture());

        var extractedContext = traceContextCaptor.getValue();

        assertNotNull(extractedContext);
        assertNull(extractedContext.context());
        assertNotNull(extractedContext.traceIdContext());
        assertEquals(traceId, extractedContext.traceIdContext().traceIdString());
    }

    @Test
    void testCreateSpanFromSingleHeader() {
        when(tracer.nextSpan(any(TraceContextOrSamplingFlags.class))).thenReturn(span);

        var pandoraTracer = new PandoraTracer(environment, tracing, tracer, tracingProperties);
        pandoraTracer.createSpanFromSingleHeader(b3, samplingState);

        verify(tracer).nextSpan(traceContextCaptor.capture());

        var extractedContext = traceContextCaptor.getValue();

        assertNotNull(extractedContext);
        assertNotNull(extractedContext.context());
        assertEquals(traceId, extractedContext.context().traceIdString());
        assertEquals(spanId, extractedContext.context().spanIdString());
        assertEquals(true, extractedContext.context().sampled());
        assertEquals(parentSpanId, extractedContext.context().parentIdString());
    }

    @Test
    void testCreateSpanFromSingleHeaderDebug() {
        var debugB3 = "c81c0251fbdb13d6ac9625822f0a931f-ed0ef246062c027c-d-979c4f976953df7b";
        when(tracer.nextSpan(any(TraceContextOrSamplingFlags.class))).thenReturn(span);

        var pandoraTracer = new PandoraTracer(environment, tracing, tracer, tracingProperties);
        var createdSpan = pandoraTracer.createSpanFromSingleHeader(debugB3, null);

        verify(tracer).nextSpan(traceContextCaptor.capture());

        var extractedContext = traceContextCaptor.getValue();

        assertNotNull(extractedContext);
        assertNotNull(extractedContext.context());
        assertEquals(traceId, extractedContext.context().traceIdString());
        assertEquals(spanId, extractedContext.context().spanIdString());
        assertTrue(extractedContext.context().debug());
        assertEquals(parentSpanId, extractedContext.context().parentIdString());
    }

    @Test
    void testWithCurrentContext() throws Exception {
        var expectedListCallableResult = List.of("one", "two", "three");
        Callable<List<String>> expectedListCallable = () -> expectedListCallableResult;
        when(tracing.currentTraceContext()).thenReturn(currentTraceContext);
        when(currentTraceContext.wrap(eq(expectedListCallable))).thenReturn(expectedListCallable);

        var pandoraTracer = new PandoraTracer(environment, tracing, tracer, tracingProperties);
        Callable<List<String>> actualListCallable = pandoraTracer.withCurrentContext(expectedListCallable);
        assertEquals(expectedListCallable, actualListCallable);
        assertEquals(actualListCallable.call(), expectedListCallableResult);

        var expectedStringCallableResult = "expected result";
        Callable<String> expectedStringCallable = () -> expectedStringCallableResult;
        when(currentTraceContext.wrap(eq(expectedStringCallable))).thenReturn(expectedStringCallable);

        Callable<String> actualStringCallable =  pandoraTracer.withCurrentContext(expectedStringCallable);
        assertEquals(expectedStringCallable, actualStringCallable);
        var actualStringCallableResult = actualStringCallable.call();
        assertInstanceOf(String.class, actualStringCallableResult);
        assertEquals(expectedStringCallableResult, actualStringCallableResult);
    }

    @Test
    void testWithSpanInScopeDoesNotInvokeTracerOnCreation() {
        var pandoraTracer = new PandoraTracer(environment, tracing, tracer, tracingProperties);

        var wrapped = pandoraTracer.withSpanInScope((String s) -> s.toUpperCase(), span);

        assertNotNull(wrapped);
        verifyNoInteractions(tracer);
    }

    @Test
    void testWithSpanInScopeExecutesFunctionAndClosesScope() {
        Function<String, Integer> fn = mock(Function.class);
        when(tracer.withSpanInScope(span)).thenReturn(spanInScope);
        when(fn.apply("abc")).thenReturn(3);

        var pandoraTracer = new PandoraTracer(environment, tracing, tracer, tracingProperties);
        var result = pandoraTracer.withSpanInScope(fn, span).apply("abc");

        assertEquals(3, result);
        var inOrder = inOrder(tracer, fn, spanInScope);
        inOrder.verify(tracer).withSpanInScope(span);
        inOrder.verify(fn).apply("abc");
        inOrder.verify(spanInScope).close();
    }

    @Test
    void testWithSpanInScopeHandlesNullInputAndNullSpan() {
        when(tracer.withSpanInScope(null)).thenReturn(spanInScope);

        var pandoraTracer = new PandoraTracer(environment, tracing, tracer, tracingProperties);
        var result = pandoraTracer.withSpanInScope((String s) -> Objects.isNull(s), null).apply(null);

        assertTrue(result);
        verify(spanInScope).close();
    }

    @Test
    void testWithSpanInScopeWhenTracerReturnsNullScope() {
        when(tracer.withSpanInScope(span)).thenReturn(null);

        var pandoraTracer = new PandoraTracer(environment, tracing, tracer, tracingProperties);
        var result = pandoraTracer.withSpanInScope(s -> "ok", span).apply("in");

        assertEquals("ok", result);
    }

    @Test
    void testWithSpanInScopeWhenScopeActivationThrows() {
        Function<String, String> fn = mock(Function.class);
        when(tracer.withSpanInScope(span)).thenThrow(new IllegalStateException("scope failure"));

        var pandoraTracer = new PandoraTracer(environment, tracing, tracer, tracingProperties);
        var wrapped = pandoraTracer.withSpanInScope(fn, span);

        assertThrows(IllegalStateException.class, () -> wrapped.apply("in"));
        verifyNoInteractions(fn);
    }

    @Test
    void testWithSpanInScopeWhenFunctionIsNull() {
        when(tracer.withSpanInScope(span)).thenReturn(spanInScope);

        var pandoraTracer = new PandoraTracer(environment, tracing, tracer, tracingProperties);
        var wrapped = pandoraTracer.<String, String>withSpanInScope(null, span);

        assertThrows(NullPointerException.class, () -> wrapped.apply("in"));
        verify(spanInScope).close();
    }

    @Test
    void testWithSpanInScopeClosesScopeWhenFunctionThrows() {
        when(tracer.withSpanInScope(span)).thenReturn(spanInScope);
        Function<String, String> fn = s -> { throw new IllegalArgumentException("bad input"); };

        var pandoraTracer = new PandoraTracer(environment, tracing, tracer, tracingProperties);
        var wrapped = pandoraTracer.withSpanInScope(fn, span);

        assertThrows(IllegalArgumentException.class, () -> wrapped.apply("in"));
        verify(spanInScope).close();
    }

    @Test
    void testWithSpanInScopeSuppressesCloseExceptionWhenBothThrow() {
        when(tracer.withSpanInScope(span)).thenReturn(spanInScope);
        doThrow(new RuntimeException("close error")).when(spanInScope).close();
        Function<String, String> fn = s -> { throw new RuntimeException("primary error"); };

        var pandoraTracer = new PandoraTracer(environment, tracing, tracer, tracingProperties);
        var wrapped = pandoraTracer.withSpanInScope(fn, span);

        var ex = assertThrows(RuntimeException.class, () -> wrapped.apply("in"));
        assertEquals("primary error", ex.getMessage());
        assertEquals(1, ex.getSuppressed().length);
        assertEquals("close error", ex.getSuppressed()[0].getMessage());
    }
}
