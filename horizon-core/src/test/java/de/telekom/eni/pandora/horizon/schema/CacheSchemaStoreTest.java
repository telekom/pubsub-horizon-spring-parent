// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.telekom.eni.pandora.horizon.cache.service.JsonCacheService;
import de.telekom.eni.pandora.horizon.exception.JsonCacheException;
import de.telekom.eni.pandora.horizon.kubernetes.resource.PublisherResource;
import de.telekom.eni.pandora.horizon.kubernetes.resource.PublisherSpec;
import lombok.extern.slf4j.Slf4j;
import org.everit.json.schema.Schema;
import org.everit.json.schema.ValidationException;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@Slf4j
@ExtendWith(MockitoExtension.class)
public class CacheSchemaStoreTest {

    @Mock
    private JsonCacheService<PublisherResource> publisherCache;

    private CacheSchemaStore testInstance;

    private PublisherResource publisherResource;

    @BeforeEach
    void setUp() throws Exception {
        testInstance = new CacheSchemaStore(publisherCache);

        // Load test data from JSON file
        ObjectMapper objectMapper = new ObjectMapper();
        publisherResource = objectMapper.readValue(
                new File("src/test/resources/data/publisher-example.json"),
                PublisherResource.class
        );
    }

    static Stream<Arguments> provideValidationTestData() {
        return Stream.of(
                Arguments.of(
                        "Valid JSON matching schema",
                        "{\"firstName\": \"John\", \"lastName\": \"Doe\", \"age\": 30}",
                        true,
                        "Should successfully validate JSON that matches the Person schema"
                ),
                Arguments.of(
                        "Invalid JSON - age is negative",
                        "{\"firstName\": \"John\", \"lastName\": \"Doe\", \"age\": -5}",
                        false,
                        "Should fail validation when age is negative (minimum is 0)"
                ),
                Arguments.of(
                        "Invalid JSON - age is string",
                        "{\"firstName\": \"John\", \"lastName\": \"Doe\", \"age\": \"thirty\"}",
                        false,
                        "Should fail validation when age is a string instead of integer"
                ),
                Arguments.of(
                        "Valid JSON - minimal fields",
                        "{\"firstName\": \"Jane\"}",
                        true,
                        "Should successfully validate JSON with only required/optional fields"
                )
        );
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("provideValidationTestData")
    void testGetSchemaAndValidateJson(String testName, String jsonToValidate, boolean shouldBeValid, String description) throws JsonCacheException {
        // Arrange
        String environment = "environment";
        String eventType = "horizon-test-v1";
        String hub = "example-hub";
        String team = "team";
        String key = String.format("%s--%s--%s--%s", environment, eventType, hub, team);

        when(publisherCache.getByKey(key)).thenReturn(Optional.of(publisherResource));

        // Act
        Schema schema = testInstance.getSchemaForEventType(environment, eventType, hub, team);

        // Assert
        assertNotNull(schema, "Schema should not be null");
        assertEquals("Person", schema.getTitle(), "Schema title should be 'Person'");

        JSONObject jsonObject = new JSONObject(jsonToValidate);

        if (shouldBeValid) {
            assertDoesNotThrow(
                    () -> schema.validate(jsonObject),
                    description + " - Validation should not throw exception"
            );
        } else {
            assertThrows(
                    ValidationException.class,
                    () -> schema.validate(jsonObject),
                    description + " - Validation should throw ValidationException"
            );
        }
    }

    @ParameterizedTest(name = "Missing cache entry: {0}")
    @MethodSource("provideMissingCacheTestData")
    void testGetSchemaWithMissingCacheEntry(String testName, String environment, String eventType, String hub, String team) throws JsonCacheException {
        // Arrange
        String key = String.format("%s--%s--%s--%s", environment, eventType, hub, team);
        when(publisherCache.getByKey(key)).thenReturn(Optional.empty());

        // Act
        Schema schema = testInstance.getSchemaForEventType(environment, eventType, hub, team);

        // Assert
        assertNull(schema, "Schema should be null when cache entry is missing");
    }

    static Stream<Arguments> provideMissingCacheTestData() {
        return Stream.of(
                Arguments.of("Non-existent environment", "prod", "horizon-test-v1", "example-hub", "team"),
                Arguments.of("Non-existent event type", "environment", "unknown-event", "example-hub", "team"),
                Arguments.of("Non-existent hub", "environment", "horizon-test-v1", "unknown-hub", "team"),
                Arguments.of("Non-existent team", "environment", "horizon-test-v1", "example-hub", "unknown-team")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("provideNullJsonSchemaTestData")
    void testGetSchemaWithNullJsonSchema(String testName, PublisherResource resource) throws JsonCacheException {
        // Arrange
        String environment = "environment";
        String eventType = "horizon-test-v1";
        String hub = "example-hub";
        String team = "team";
        String key = String.format("%s--%s--%s--%s", environment, eventType, hub, team);

        when(publisherCache.getByKey(key)).thenReturn(Optional.of(resource));

        // Act
        Schema schema = testInstance.getSchemaForEventType(environment, eventType, hub, team);

        // Assert
        assertNull(schema, "Schema should be null when specification is null");
    }

    static Stream<Arguments> provideNullJsonSchemaTestData() {
        PublisherResource publisherWithNullSpec = new PublisherResource();
        publisherWithNullSpec.setSpec(null);

        PublisherResource publisherWithNullJsonSchema = new PublisherResource();
        PublisherSpec spec = new PublisherSpec();
        spec.setJsonSchema(null);
        publisherWithNullJsonSchema.setSpec(spec);

        return Stream.of(
                Arguments.of("Null spec object", publisherWithNullSpec),
                Arguments.of("Null jsonSchema string", publisherWithNullJsonSchema)
        );
    }
}
