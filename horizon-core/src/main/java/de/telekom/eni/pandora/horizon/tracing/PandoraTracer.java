// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.tracing;

import brave.*;
import brave.propagation.SamplingFlags;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.propagation.TraceIdContext;
import io.micrometer.tracing.internal.EncodingUtils;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

@Slf4j
public class PandoraTracer {

    protected final Environment environment;

    protected final Tracing tracing;

    protected final Tracer tracer;

    private final TracingProperties tracingProperties;

    public PandoraTracer(Environment environment, Tracing tracing, Tracer tracer, TracingProperties tracingProperties) {
        this.environment = environment;
        this.tracing = tracing;
        this.tracer = tracer;
        this.tracingProperties = tracingProperties;
    }

    public Filter createTraceInformationResponseFilter() {
        return (request, response, chain) -> {
            Span currentSpan = tracer.currentSpan();
            if (currentSpan != null) {
                withCommonTags(currentSpan);
                var currentTraceContext = currentSpan.context();

                var isHeadRequest = HttpMethod.HEAD.name().equalsIgnoreCase(((HttpServletRequest) request).getMethod());

                var resp = (HttpServletResponse) response;
                resp.addHeader(Constants.HTTP_HEADER_X_B3_TRACEID, currentTraceContext.traceIdString());

                var isDebugTrace = currentTraceContext.debug();
                if (isDebugTrace) {
                    resp.addHeader(Constants.HTTP_HEADER_X_B3_FLAGS, "1");
                } else {
                    resp.addHeader(Constants.HTTP_HEADER_X_B3_SAMPLED, currentTraceContext.sampled() && !isHeadRequest ? "1" : "0");
                }
            }
            chain.doFilter(request, response);
        };
    }
    public Span startSpan(String name) {
        return withName(withCommonTags(tracer.nextSpan()), name).start();
    }

    public DebugSpanWrapper startDebugSpan(String name) {
        var shouldStartSpan =  tracingProperties.isDebugEnabled() || isDebugTrace(null);
        if (shouldStartSpan) {
            return new DebugSpanWrapper(startSpan(name));
        } else {
            return new DebugSpanWrapper(null);
        }
    }

    public ScopedSpan startScopedSpan(String name) {
        return withCommonTags(tracer.startScopedSpan(name));
    }

    public ScopedDebugSpanWrapper startScopedDebugSpan(String name) {
        var shouldStartSpan =  tracingProperties.isDebugEnabled() || isDebugTrace(null);
        if (shouldStartSpan) {
            return new ScopedDebugSpanWrapper(startScopedSpan(name));
        } else {
            return new ScopedDebugSpanWrapper(null);
        }
    }

    public Tracer.SpanInScope withSpanInScope(Span span) {
        return tracer.withSpanInScope(span);
    }

    public Tracer.SpanInScope withDebugSpanInScope(DebugSpanWrapper debugSpanWrapper) {
        var span = debugSpanWrapper.getSpan();
        if (Objects.nonNull(span)) {
            return tracer.withSpanInScope(span);
        } else {
            return null;
        }
    }

    public Span getCurrentSpan() {
        return tracer.currentSpan();
    }

    public void finishCurrentSpan() {
        var span = tracer.currentSpan();

        if (span != null) {
            span.finish();
        }
    }

    public Runnable withCurrentTraceContext(Runnable runnable) {
        return tracing.currentTraceContext().wrap(runnable);
    }

    public Callable<?> withCurrentTraceContext(Callable<?> callable) {
        return tracing.currentTraceContext().wrap(callable);
    }

    public void addTagsToSpan(SpanCustomizer span, List<Pair<String, String>> pairs) {
        for(var pair : pairs) {
            if (StringUtils.isNotBlank(pair.getValue())) {
                span.tag(pair.getKey(), pair.getValue());
            }
        }
    }

    protected Span createTraceContextAndSpan(@Nullable String traceId, @Nullable String spanId, @Nullable SamplingState samplingState, @Nullable String parentSpanId) {
        var b1 = TraceContext.newBuilder();
        var b2 = TraceIdContext.newBuilder();

        if (StringUtils.isNotBlank(traceId) && traceId.length() >= 16) {
            var traceIdHigh = 0L;
            var traceIdLow = 0L;

            if (traceId.length() >= 32) {
                traceIdHigh = EncodingUtils.longFromBase16String(traceId.substring(0, 16));
                traceIdLow = EncodingUtils.longFromBase16String(traceId.substring(16, 32));

                b1.traceIdHigh(traceIdHigh).traceId(traceIdLow);
                b2.traceIdHigh(traceIdHigh).traceId(traceIdLow);
            } else {
                traceIdLow = EncodingUtils.longFromBase16String(traceId.substring(0, 16));

                b1.traceId(traceIdLow);
                b2.traceId(traceIdLow);
            }

            if (samplingState != null) {
                switch (samplingState) {
                    case DEBUG -> {
                        b1.debug(true);
                        b2.debug(true);
                    }
                    case SAMPLED -> {
                        b1.sampled(true);
                        b2.sampled(true);
                    }
                    case NOT_SAMPLED -> {
                        b1.sampled(false);
                        b2.sampled(false);
                    }
                }
            }

            if (StringUtils.isNotBlank(spanId) && spanId.length() == 16) {
                b1.spanId(EncodingUtils.longFromBase16String(spanId.substring(0, 16)));

                if (StringUtils.isNotBlank(parentSpanId) && parentSpanId.length() == 16) {
                    b1.parentId(EncodingUtils.longFromBase16String(parentSpanId.substring(0, 16)));
                }

                return tracer.nextSpan(TraceContextOrSamplingFlags.create(b1.build()));
            } else {
                return tracer.nextSpan(TraceContextOrSamplingFlags.create(b2.build()));
            }
        }

        if (samplingState != null) {
            switch (samplingState) {
                case DEBUG -> {
                    return tracer.nextSpan(TraceContextOrSamplingFlags.create(SamplingFlags.DEBUG));
                }
                case SAMPLED -> {
                    return tracer.nextSpan(TraceContextOrSamplingFlags.create(SamplingFlags.SAMPLED));
                }
                case NOT_SAMPLED -> {
                    return tracer.nextSpan(TraceContextOrSamplingFlags.create(SamplingFlags.NOT_SAMPLED));
                }
            }
        }

        return tracer.newTrace();
    }

    protected Span createSpanFromSingleHeader(String b3, @Nullable SamplingState samplingState) {
        String traceId = null;
        String spanId = null;
        String parentSpanId = null;

        var b3SingleHeaderParts = b3.split("-");

        if (b3SingleHeaderParts.length >= 1) {
            traceId = b3SingleHeaderParts[0];

            if (b3SingleHeaderParts.length >= 2) {
                spanId = b3SingleHeaderParts[1];

                if (samplingState == null && b3SingleHeaderParts.length >= 3) {
                    samplingState = SamplingState.findByValue(b3SingleHeaderParts[2]);
                }

                if (b3SingleHeaderParts.length >= 4) {
                    parentSpanId = b3SingleHeaderParts[3];
                }
            }
        }
        return createTraceContextAndSpan(traceId, spanId, samplingState, parentSpanId);
    }

    public Map<String, String> propagateTracingInformation(Map<String, String> properties) {
        Map<String, String> merged = new HashMap<>();
        if (properties != null) {
            merged.putAll(properties);
        }

        merged.putAll(this.getCurrentTracingHeaders());
        return merged;
    }

    protected <T extends SpanCustomizer> T withCommonTags(T span) {
        span.tag("component", environment.getProperty("spring.application.name"));
        return span;
    }

    protected <T extends SpanCustomizer> T withName(T span, @Nullable String name) {
        if (StringUtils.isNotBlank(name)) {
            span.name(name);
        }

        return span;
    }


    // GETTER
    public boolean isDebugTrace(@Nullable Span span) {
        var currentContext = getCurrentTraceContext();
        if (currentContext == null && span != null) {
            currentContext = span.context();
        }

        return currentContext != null && currentContext.debug();
    }

    public Map<String, String> getCurrentTracingHeaders() {
        var headers = new HashMap<String, String>();

        headers.put(Constants.HTTP_HEADER_X_B3_TRACEID, getCurrentTraceId());

        var currentParentSpanId = getCurrentParentSpanId();
        if (Objects.nonNull(currentParentSpanId)) {
            headers.put(Constants.HTTP_HEADER_X_B3_PARENTSPANID, currentParentSpanId);
        }

        var currentSpanId = getCurrentSpanId();
        if (Objects.nonNull(currentSpanId)) {
            headers.put(Constants.HTTP_HEADER_X_B3_SPANID, currentSpanId);
        }

        if (isDebugTrace(null)) {
            headers.put(Constants.HTTP_HEADER_X_B3_FLAGS, "1");
        } else {
            headers.put(Constants.HTTP_HEADER_X_B3_SAMPLED, (getCurrentTraceContext().sampled() ? "1" : "0"));
        }

        return headers;
    }

    public String getCurrentTraceId() {
        var currentTraceContext = getCurrentTraceContext();
        if (currentTraceContext != null) {
            return getCurrentTraceContext().traceIdString();
        }
        return null;
    }

    public String getCurrentSpanId() {
        var currentTraceContext = getCurrentTraceContext();
        if (currentTraceContext != null) {
            return getCurrentTraceContext().spanIdString();
        }
        return null;
    }

    public String getCurrentParentSpanId() {
        var currentTraceContext = getCurrentTraceContext();
        if (currentTraceContext != null) {
            return getCurrentTraceContext().parentIdString();
        }
        return null;
    }

    public TraceContext getCurrentTraceContext() {
        return tracing.currentTraceContext().get();
    }
}