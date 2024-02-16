package de.telekom.eni.pandora.horizon.kafka.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import de.telekom.eni.pandora.horizon.autoconfigure.kafka.KafkaAutoConfiguration;
import de.telekom.eni.pandora.horizon.model.event.DeliveryType;
import de.telekom.eni.pandora.horizon.model.event.Event;
import de.telekom.eni.pandora.horizon.model.event.MessageType;
import de.telekom.eni.pandora.horizon.model.event.SubscriptionEventMessage;
import de.telekom.eni.pandora.horizon.model.meta.EventRetentionTime;
import de.telekom.eni.pandora.horizon.model.meta.HorizonComponentId;
import de.telekom.eni.pandora.horizon.utils.EmbeddedKafkaHolder;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = {KafkaAutoConfiguration.class})
public class EventWriterTest {
    private static final String TOPIC_NAME = "subscribed";

    static {
        EmbeddedKafkaHolder.getEmbeddedKafka();
    }

    public static final EmbeddedKafkaBroker broker = EmbeddedKafkaHolder.getEmbeddedKafka();

    @Autowired
    private ConsumerFactory consumerFactory;

    @Autowired
    private EventWriter eventWriter;

    private final BlockingQueue<ConsumerRecord<String, String>> messageRecordsMap = new LinkedBlockingQueue<>();

    private KafkaMessageListenerContainer<String, String> container;

    @BeforeEach
    void setUp() {

        ContainerProperties containerProperties = new ContainerProperties(TOPIC_NAME);
        containerProperties.setGroupId("eventWriterTest");
        containerProperties.setAckMode(ContainerProperties.AckMode.RECORD);
        container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties);
        container.setupMessageListener((MessageListener<String, String>) record ->
                messageRecordsMap.add(record)
        );
        container.start();

        ContainerTestUtils.waitForAssignment(container, broker.getPartitionsPerTopic());
    }

    @AfterEach
    void tearDown() {
        container.stop();
    }

    @Test
    public void embeddedKafkaTest() throws InterruptedException {
        var event = new Event();
        event.setId("123");
        event.setType("foobar");

        var msg = new SubscriptionEventMessage(event, "env", DeliveryType.CALLBACK, "foo", "bar", EventRetentionTime.TTL_1_DAY);

        try {
            eventWriter.send(TOPIC_NAME, msg);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        ConsumerRecord<String, String> record = messageRecordsMap.poll(3, TimeUnit.SECONDS);
        assertNotNull(record);
        assertNotNull(record.headers());
        assertNotNull(record.headers().lastHeader("type"));
        assertEquals(MessageType.MESSAGE.name(), new String(record.headers().lastHeader("type").value(), StandardCharsets.UTF_8));
        assertNotNull(record.headers().lastHeader("clientId"));
        assertEquals(HorizonComponentId.UNSET, HorizonComponentId.fromGroupId(new String(record.headers().lastHeader("clientId").value(), StandardCharsets.UTF_8)));

    }

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("horizon.kafka.partitionCount", () -> 1);
        registry.add("horizon.kafka.autoCreateTopics", () -> true);
    }

}
