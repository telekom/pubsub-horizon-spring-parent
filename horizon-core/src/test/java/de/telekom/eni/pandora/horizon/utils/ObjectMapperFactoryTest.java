package de.telekom.eni.pandora.horizon.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.SubscriptionTrigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
public class ObjectMapperFactoryTest {

    @Test
    @DisplayName("Create a new ObjectMapper with registered modules")
    void testCreateNewObjectMapperWithModules() {
        var objectMapper = ObjectMapperFactory.create();

        var expectedModules = List.of("JSON-Filter");

        assertTrue(objectMapper.getRegisteredModuleIds().containsAll(expectedModules));
    }

    @Test
    @DisplayName("Serialization/deserialization for JsonPath filters is supported")
    void testJsonPathOperatorSerdesSupport() {
        var objectMapper = ObjectMapperFactory.create();

        var data = """
                advancedSelectionFilter:
                  eq:
                    field: $.foo
                    value: bar
                """;

        try {
            var trigger = objectMapper.readValue(data, SubscriptionTrigger.class);

            assertNotNull(trigger);
            assertNotNull(trigger.getAdvancedSelectionFilter());
        } catch (JsonProcessingException e) {
            fail(e);
        }
    }
}
