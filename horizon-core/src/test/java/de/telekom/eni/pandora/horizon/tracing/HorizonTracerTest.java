package de.telekom.eni.pandora.horizon.tracing;

import brave.ScopedSpan;
import brave.Span;
import brave.Tracer;
import brave.Tracing;
import brave.propagation.TraceContextOrSamplingFlags;
import de.telekom.eni.pandora.horizon.model.event.DeliveryType;
import de.telekom.eni.pandora.horizon.model.event.Event;
import de.telekom.eni.pandora.horizon.model.event.PublishedEventMessage;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HorizonTracerTest {

    @Mock
    Environment environment;

    @Mock
    Tracing tracing;

    @Mock
    Tracer tracer;

    @Mock
    TracingProperties tracingProperties;

    @Mock
    ScopedSpan scopedSpan;

    @Mock
    Span span;

    @Captor
    ArgumentCaptor<TraceContextOrSamplingFlags> traceContextCaptor;

    private static final String traceId = "c81c0251fbdb13d6ac9625822f0a931f";
    private static final String spanId = "ed0ef246062c027c";
    private static final SamplingState sampleState = SamplingState.SAMPLED;
    private static final String parentSpanId = "979c4f976953df7b";

    private static final String b3 = "c81c0251fbdb13d6ac9625822f0a931f-ed0ef246062c027c-1-979c4f976953df7b";

    @Test
    @DisplayName("Start span from kafka-headers")
    void testStartSpanFromKafkaHeaders() {
        when(environment.getProperty("spring.application.name")).thenReturn("foobar");

        var headers = new RecordHeaders();
        headers.add("", "".getBytes(StandardCharsets.UTF_8));

        headers.add(Constants.HTTP_HEADER_X_B3_TRACEID, traceId.getBytes(StandardCharsets.UTF_8));
        headers.add(Constants.HTTP_HEADER_X_B3_SPANID, spanId.getBytes(StandardCharsets.UTF_8));
        headers.add(Constants.HTTP_HEADER_X_B3_SAMPLED, sampleState.getValue().getBytes(StandardCharsets.UTF_8));
        headers.add(Constants.HTTP_HEADER_X_B3_PARENTSPANID, parentSpanId.getBytes(StandardCharsets.UTF_8));

        when(tracer.nextSpan(any(TraceContextOrSamplingFlags.class))).thenReturn(span);

        var horizonTracer = new HorizonTracer(environment, tracing, tracer, tracingProperties);
        horizonTracer.startSpanFromKafkaHeaders("test", headers);

        verify(tracer).nextSpan(traceContextCaptor.capture());

        var extractedContext = traceContextCaptor.getValue();

        assertNotNull(extractedContext);
        assertNotNull(extractedContext.context());
        assertEquals(extractedContext.context().traceIdString(), traceId);
        assertEquals(extractedContext.context().spanIdString(), spanId);
        assertTrue(extractedContext.context().sampled());
        assertEquals(extractedContext.context().parentIdString(), parentSpanId);
    }

    @Test
    @DisplayName("Start span from kafka-headers debug")
    void testStartSpanFromKafkaHeadersDebug() {
        when(environment.getProperty("spring.application.name")).thenReturn("foobar");

        var headers = new RecordHeaders();
        headers.add("", "".getBytes(StandardCharsets.UTF_8));

        headers.add(Constants.HTTP_HEADER_X_B3_TRACEID, traceId.getBytes(StandardCharsets.UTF_8));
        headers.add(Constants.HTTP_HEADER_X_B3_SPANID, spanId.getBytes(StandardCharsets.UTF_8));
        headers.add(Constants.HTTP_HEADER_X_B3_FLAGS, "1".getBytes(StandardCharsets.UTF_8));
        headers.add(Constants.HTTP_HEADER_X_B3_PARENTSPANID, parentSpanId.getBytes(StandardCharsets.UTF_8));

        when(tracer.nextSpan(any(TraceContextOrSamplingFlags.class))).thenReturn(span);

        var horizonTracer = new HorizonTracer(environment, tracing, tracer, tracingProperties);
        var createdSpan = horizonTracer.startSpanFromKafkaHeaders("test", headers);

        verify(tracer).nextSpan(traceContextCaptor.capture());

        var extractedContext = traceContextCaptor.getValue();

        assertNotNull(extractedContext);
        assertNotNull(extractedContext.context());
        assertEquals(extractedContext.context().traceIdString(), traceId);
        assertEquals(extractedContext.context().spanIdString(), spanId);
        assertTrue(extractedContext.context().debug());
        assertEquals(extractedContext.context().parentIdString(), parentSpanId);
    }

    @Test
    @DisplayName("Add tags from event-message")
    void testAddTagsToSpanFromEventMessage() {
        var horizonTracer = new HorizonTracer(environment, tracing, tracer, tracingProperties);

        PublishedEventMessage message = new PublishedEventMessage();
        message.setUuid(UUID.randomUUID().toString());
        message.setEnvironment("integration");

        Event event = new Event();
        event.setId("123");
        event.setType("foobar");

        message.setEvent(event);

        horizonTracer.addTagsToSpanFromEventMessage(span, message);

        verify(span).tag("uuid", message.getUuid());
        verify(span).tag("environment", message.getEnvironment());
        verify(span).tag("eventId", message.getEvent().getId());
        verify(span).tag("eventType", message.getEvent().getType());
    }

    @Test
    @DisplayName("Add tags from subscription-event-message")
    void testAddTagsToSpanFromSubscriptionEventMessage() {
        var horizonTracer = new HorizonTracer(environment, tracing, tracer, tracingProperties);

        SubscriptionEventMessage message = new SubscriptionEventMessage();
        Event event = new Event();

        event.setId("01");
        event.setType("test");

        message.setUuid(UUID.randomUUID().toString());
        message.setEnvironment("integration");
        message.setSubscriptionId("foobar");
        message.setDeliveryType(DeliveryType.fromString("callback"));
        message.setAppliedScopes(Arrays.asList("default", "billing"));
        message.setEvent(event);

        horizonTracer.addTagsToSpanFromSubscriptionEventMessage(span, message);

        verify(span).tag("uuid", message.getUuid());
        verify(span).tag("environment", message.getEnvironment());
        verify(span).tag("subscriptionId", message.getSubscriptionId());
        verify(span).tag("deliveryType", message.getDeliveryType().getValue());
        verify(span).tag("appliedScopes", String.join(", ", message.getAppliedScopes()));
    }

    @Test
    @DisplayName("Add tags from record-metadata")
    void testAddTagsToSpanFromRecordMetadata() {
        var horizonTracer = new HorizonTracer(environment, tracing, tracer, tracingProperties);

        var topicPartition = new TopicPartition("foobar", 0);
        var meta = new RecordMetadata(topicPartition, -1, -1, new Date().getTime(), 0, 0);

        horizonTracer.addTagsToSpanFromRecordMetadata(span, meta);

        verify(span).tag("partition", String.valueOf(meta.partition()));
        verify(span).tag("offset", String.valueOf(meta.offset()));
        verify(span).tag("topic", meta.topic());
    }

    /*@Test
    @DisplayName("Add tags from logged-message")
    void addTagsToSpanFromLoggedMessage() {
        var horizonTracer = new HorizonTracer(environment, tracing, tracer, tracingProperties);

        var message = new EventMetaMessage();
        message.setPartition(-1);
        message.setOffset(-1);
        message.setTopic("foobar");

        horizonTracer.addTagsToSpanFromEventMetaMessage(span, message);

        verify(span).tag("partition", String.valueOf(message.getPartition()));
        verify(span).tag("offset", String.valueOf(message.getOffset()));
        verify(span).tag("topic", message.getTopic());
    }*/
}
