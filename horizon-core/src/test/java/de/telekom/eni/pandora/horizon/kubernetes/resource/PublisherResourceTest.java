// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.kubernetes.resource;

import de.telekom.eni.pandora.horizon.mongo.model.PublisherMongoDocument;
import de.telekom.eni.pandora.horizon.utils.TestUtils;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PublisherResourceTest {

    private static final String SCHEMA = """
            {
                      "$id": "https://example.com/person.schema.json",
                      "$schema": "http://json-schema.org/draft-07/schema",
                      "title": "Person",
                      "type": "object",
                      "properties": {
                        "firstName": {
                          "type": "string",
                          "description": "The person's first name."
                        },
                        "lastName": {
                          "type": "string",
                          "description": "The person's last name."
                        },
                        "age": {
                          "description": "Age in years which must be equal to or greater than zero.",
                          "type": "integer",
                          "minimum": 0
                        }
                      }
                    }
            """;

    @Test
    void testParsePublisherSpecFromYaml() throws Exception {
        // ACT
        PublisherMongoDocument eventSpec = TestUtils.readObjectFromResources(
                "data/publisher-example.json",
                PublisherMongoDocument.class
        );

        // ASSERT
        assertNotNull(eventSpec, "'Event' is null");
        assertNotNull(eventSpec.spec, "'Event.spec' is null");
        assertEquals("horizon.test.v1", eventSpec.spec.getEventType(), "'Event.spec.eventType' does not match");
        assertEquals(SCHEMA.replaceAll("\\s+", ""), eventSpec.spec.getJsonSchema().replaceAll("\\s+", ""), "'Event.spec.jsonSchema' does not match");
        assertEquals("111-111-111-111", eventSpec.spec.getPublisherId(), "'Event.spec.publisherId' does not match");
        assertThat(eventSpec.spec.getAdditionalPublisherIds()).withFailMessage("'Event.spec.additionalPublisherIds' does not match").containsExactlyInAnyOrderElementsOf(Arrays.asList("222-222-222-222", "333-333-333-333"));
        assertEquals("111-111-111-111", eventSpec.spec.getPublisherId(), "'Event.spec.publisherId' does not match");
    }
}
