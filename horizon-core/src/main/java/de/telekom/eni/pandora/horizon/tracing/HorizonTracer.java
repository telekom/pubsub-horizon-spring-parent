package de.telekom.eni.pandora.horizon.tracing;

import brave.Span;
import brave.SpanCustomizer;
import de.telekom.eni.pandora.horizon.model.db.State;
import de.telekom.eni.pandora.horizon.model.event.EventMessage;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Headers;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static de.telekom.eni.pandora.horizon.tracing.Constants.*;

@Slf4j
public class HorizonTracer extends PandoraTracer {

    public HorizonTracer(Environment environment, brave.Tracing tracing, brave.Tracer tracer, TracingProperties tracingProperties) {
        super(environment, tracing, tracer, tracingProperties);
    }

    // start span from different sources

    public Span startSpanFromKafkaHeaders(Headers headers) {
        return startSpanFromKafkaHeaders(null, headers, null);
    }

    public Span startSpanFromKafkaHeaders(@Nullable String name, Headers headers) {
        return startSpanFromKafkaHeaders(name, headers, null);
    }

    public Span startSpanFromKafkaHeaders(@Nullable String name, Headers headers, @Nullable SamplingState samplingState) {
        Span span;

        var b3 = getHeaderValue(Constants.HTTP_HEADER_B3, headers);
        if (b3 != null) {
            span = createSpanFromSingleHeader(b3, samplingState);
        } else {
            var traceId = getHeaderValue(HTTP_HEADER_X_B3_TRACEID, headers);
            var spanId = getHeaderValue(Constants.HTTP_HEADER_X_B3_SPANID, headers);

            if (samplingState == null) {
                var debugFlag = getHeaderValue(Constants.HTTP_HEADER_X_B3_FLAGS, headers);
                var samplingFlag = getHeaderValue(Constants.HTTP_HEADER_X_B3_SAMPLED, headers);

                if (StringUtils.isNotBlank(debugFlag) && "1".equals(debugFlag)) {
                    samplingState = SamplingState.DEBUG;
                } else if (StringUtils.isNotBlank(samplingFlag)) {
                    samplingState = SamplingState.findByValue(samplingFlag);
                }
            }

            var parentSpanId = getHeaderValue(Constants.HTTP_HEADER_X_B3_PARENTSPANID, headers);

            var plungerHeader = getHeaderValue(HTTP_HEADER_PLUNGER, headers);
            if (Objects.nonNull(plungerHeader) && plungerHeader.equals("true")) {
                samplingState = SamplingState.NOT_SAMPLED;
            }

            span = createTraceContextAndSpan(traceId, spanId, samplingState, parentSpanId);
        }

        return withName(withCommonTags(span), name).start();
    }

    public Span startSpanFromState(State state) {
        return startSpanFromState(null, state, null);
    }

    public Span startSpanFromState(@Nullable String name, State state) {
        return startSpanFromState(name, state, null);
    }

    public Span startSpanFromState(@Nullable String name, State state, @Nullable SamplingState samplingState) {
        Span span;

        var b3 = (String) state.getProperties().getOrDefault(Constants.HTTP_HEADER_B3, null);
        if (b3 != null) {
            span = createSpanFromSingleHeader(b3, samplingState);
        } else {
            var traceId = (String) state.getProperties().getOrDefault(HTTP_HEADER_X_B3_TRACEID, null);
            var spanId  = (String) state.getProperties().getOrDefault(Constants.HTTP_HEADER_X_B3_SPANID, null);

            if (samplingState == null) {
                var debugFlag = (String) state.getProperties().getOrDefault(Constants.HTTP_HEADER_X_B3_FLAGS, null);
                var samplingFlag = (String) state.getProperties().getOrDefault(Constants.HTTP_HEADER_X_B3_SAMPLED, null);

                if (StringUtils.isNotBlank(debugFlag) && "1".equals(debugFlag)) {
                    samplingState = SamplingState.DEBUG;
                } else if (StringUtils.isNotBlank(samplingFlag)) {
                    samplingState = SamplingState.findByValue(samplingFlag);
                }
            }

            var parentSpanId = (String) state.getProperties().getOrDefault(Constants.HTTP_HEADER_X_B3_PARENTSPANID, null);

            span = createTraceContextAndSpan(traceId, spanId, samplingState, parentSpanId);
        }
        return withName(withCommonTags(span), name).start();
    }

    private String getHeaderValue(String key, Headers headers) {
        var header = headers.lastHeader(key);
        if (header != null && header.value() != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }

        return null;
    }


    // add tags to current span from different sources

    public void addTagsToSpanFromEventMessage(SpanCustomizer span, EventMessage eventMessage) {
        span.tag("uuid", eventMessage.getUuid());
        span.tag("environment", eventMessage.getEnvironment());
        span.tag("eventId", eventMessage.getEvent().getId());
        span.tag("eventType", eventMessage.getEvent().getType());
    }

    public void addTagsToSpanFromSubscriptionEventMessage(SpanCustomizer span, SubscriptionEventMessage message) {
        addTagsToSpanFromEventMessage(span, message);

        span.tag("subscriptionId", message.getSubscriptionId());
        span.tag("deliveryType", message.getDeliveryType().getValue());

        if (message.getAppliedScopes() != null) {
            span.tag("appliedScopes", String.join(", ", message.getAppliedScopes()));
        }
    }

    public void addTagsToSpanFromRecordMetadata(SpanCustomizer span, RecordMetadata metadata) {
        span.tag("partition", String.valueOf(metadata.partition()));
        span.tag("offset", String.valueOf(metadata.offset()));
        span.tag("topic", metadata.topic());
    }

    public void addTagsToSpanFromState(SpanCustomizer span, State state) {
        span.tag("partition", String.valueOf(state.getCoordinates().partition()));
        span.tag("offset", String.valueOf(state.getCoordinates().offset()));
    }

    // add trace information to headers

    public void addCurrentTracingInformationToKafkaHeaders(Headers headers) {
        var currentTraceInformation = getCurrentTracingHeaders();

        for (var entry : currentTraceInformation.entrySet()) {
            headers.add(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
    }

    public HashMap<String, List<String>> getTracingInformationFromKafkaHeaders(Headers kafkaHeaders) {
        var newHttpHeaders = new HashMap<String, List<String>>();

        var traceId = Optional.ofNullable(getHeaderValue(HTTP_HEADER_X_B3_TRACEID, kafkaHeaders));
        var spanId = Optional.ofNullable(getHeaderValue(HTTP_HEADER_X_B3_SPANID, kafkaHeaders));
        var parentSpanId = Optional.ofNullable(getHeaderValue(HTTP_HEADER_X_B3_PARENTSPANID, kafkaHeaders));
        var debugFlag = Optional.ofNullable(getHeaderValue(HTTP_HEADER_X_B3_FLAGS, kafkaHeaders));
        var samplingState = Optional.ofNullable(getHeaderValue(HTTP_HEADER_X_B3_SAMPLED, kafkaHeaders));

        traceId.ifPresent(s -> newHttpHeaders.put(HTTP_HEADER_X_B3_TRACEID.toLowerCase(), List.of(s)));
        spanId.ifPresent(s -> newHttpHeaders.put(HTTP_HEADER_X_B3_SPANID.toLowerCase(), List.of(s)));
        parentSpanId.ifPresent(s -> newHttpHeaders.put(HTTP_HEADER_X_B3_PARENTSPANID.toLowerCase(), List.of(s)));
        if (debugFlag.isPresent() && debugFlag.get().equals("1")) {
            newHttpHeaders.put(HTTP_HEADER_X_B3_FLAGS.toLowerCase(), List.of(debugFlag.get()));
        } else samplingState.ifPresent(s -> newHttpHeaders.put(HTTP_HEADER_X_B3_SAMPLED.toLowerCase(), List.of(s)));

        return newHttpHeaders;
    }
}
