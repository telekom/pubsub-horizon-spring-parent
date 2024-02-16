package de.telekom.eni.pandora.horizon.model.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StatusMessageTest {

    static StatusMessage statusMessage;

    static ObjectMapper objectMapper;

    @BeforeAll
    static void setup() {
        statusMessage = new StatusMessage("123", "456", Status.DELIVERED, DeliveryType.CALLBACK);
        objectMapper = new ObjectMapper();
    }

    @Test
    void testSerialization() {
        Assertions.assertDoesNotThrow(() -> {
            String expectation = "{\"uuid\":\"123\",\"event\":{\"id\":\"456\"},\"status\":\"DELIVERED\"}";
            String actual = objectMapper.writeValueAsString(statusMessage);
            Assertions.assertEquals(expectation, actual);
        });
    }

    @Test
    void testDeserialization() {
        Assertions.assertDoesNotThrow(() -> {
            String serialized = "{\"uuid\":\"123\",\"event\":{\"id\":\"456\"},\"status\":\"DELIVERED\"}";
            StatusMessage deserialized = objectMapper.readValue(serialized, StatusMessage.class);
            Assertions.assertEquals(serialized, objectMapper.writeValueAsString(deserialized));
        });
    }

}
