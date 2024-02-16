package de.telekom.eni.pandora.horizon.kafka.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.tracing.Constants;
import de.telekom.eni.pandora.horizon.model.event.IdentifiableMessage;
import de.telekom.eni.pandora.horizon.model.event.MessageType;
import de.telekom.eni.pandora.horizon.model.event.StatusMessage;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import de.telekom.eni.pandora.horizon.model.meta.HorizonComponentId;
import de.telekom.eni.pandora.horizon.tracing.HorizonTracer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class EventWriter {

    private final KafkaTemplate<String, String> kafkaTemplate;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final HorizonComponentId clientId;

    /**
     * Initiate EventWriter Object
     * @deprecated
     * Please use {@link EventWriter(KafkaTemplate, HorizonComponentId )}
     * to have a strict value for an origin of a message.
     *
     * @param kafkaTemplate kafkaTemplate
     */
    @Deprecated(forRemoval = true)
    public EventWriter(KafkaTemplate<String, String> kafkaTemplate) {
        this(kafkaTemplate, HorizonComponentId.UNSET);
    }

    public EventWriter(KafkaTemplate<String, String> kafkaTemplate, HorizonComponentId clientId) {
        this.kafkaTemplate = kafkaTemplate;
        this.clientId = clientId;
    }

    public CompletableFuture<SendResult<String, String>> send(String kafkaTopic, IdentifiableMessage message) throws JsonProcessingException {
        return send(kafkaTopic, message, null);
    }

    public CompletableFuture<SendResult<String, String>> send(String kafkaTopic, IdentifiableMessage message, HorizonTracer tracer) throws JsonProcessingException {
        var key = message.getUuid();
        var value = objectMapper.writeValueAsString(message);
        

        var msg = new ProducerRecord<>(kafkaTopic, key, value);
        var msgType = message instanceof StatusMessage ? MessageType.METADATA : MessageType.MESSAGE;
        msg.headers().add("type", msgType.name().getBytes(StandardCharsets.UTF_8));
        msg.headers().add("clientId", clientId.getClientId().getBytes(StandardCharsets.UTF_8));
        log.debug("Writing message with id {} and content: {}", message.getUuid(), value);

        Optional.ofNullable(tracer).ifPresent(t -> t.addCurrentTracingInformationToKafkaHeaders(msg.headers()));
        var future = kafkaTemplate.send(msg);
        future.exceptionally(ex -> {
            log.error("Could not write message with id {} into kafka", message.getUuid(), ex);
            return null;
        });
        future.thenAccept(result -> log.debug("Successfully wrote message with id {}", message.getUuid()));
        return future;
    }

    private void addTracingInformationToHeadersFromSubscriptionEventMessage(Headers headers, SubscriptionEventMessage subscriptionEventMessage) {
        var httpHeader = subscriptionEventMessage.getHttpHeaders();

        headers.add(Constants.HTTP_HEADER_X_B3_TRACEID, httpHeader.get(Constants.HTTP_HEADER_X_B3_TRACEID.toLowerCase()).get(0).getBytes(StandardCharsets.UTF_8));

        var spanId = httpHeader.get(Constants.HTTP_HEADER_X_B3_SPANID.toLowerCase()).get(0);
        if (!Objects.isNull(spanId)) {
            headers.add(Constants.HTTP_HEADER_X_B3_SPANID, spanId.getBytes(StandardCharsets.UTF_8));
        }

        var currentParentSpanId = httpHeader.get(Constants.HTTP_HEADER_X_B3_PARENTSPANID.toLowerCase()).get(0);
        if (!Objects.isNull(currentParentSpanId)) {
            headers.add(Constants.HTTP_HEADER_X_B3_PARENTSPANID, currentParentSpanId.getBytes(StandardCharsets.UTF_8));
        }

        var debugFlagHeader = Optional.ofNullable(httpHeader.get(Constants.HTTP_HEADER_X_B3_FLAGS.toLowerCase()));
        if (debugFlagHeader.isPresent()) {
            var debugFlag = debugFlagHeader.get().get(0);
            if (!Objects.isNull(debugFlag) && debugFlag.equals("1")) {
                headers.add(Constants.HTTP_HEADER_X_B3_FLAGS, "1".getBytes(StandardCharsets.UTF_8));
            }
        } else {
            var sampledFlagHeader = Optional.ofNullable(httpHeader.get(Constants.HTTP_HEADER_X_B3_SAMPLED.toLowerCase()));
            if (sampledFlagHeader.isPresent()) {
                var sampledFlag = sampledFlagHeader.get().get(0);
                if (!Objects.isNull(sampledFlag)) {
                    headers.add(Constants.HTTP_HEADER_X_B3_SAMPLED, sampledFlag.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }
}
