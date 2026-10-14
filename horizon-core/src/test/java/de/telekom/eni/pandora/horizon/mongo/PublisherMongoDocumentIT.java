// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.mongo;


import de.telekom.eni.pandora.horizon.autoconfigure.mongo.MongoAutoConfiguration;
import de.telekom.eni.pandora.horizon.extension.MongoExtension;
import de.telekom.eni.pandora.horizon.mongo.model.PublisherMongoDocument;
import de.telekom.eni.pandora.horizon.mongo.repository.PublisherMongoRepo;
import de.telekom.eni.pandora.horizon.utils.TestUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("mongodb")
@Tag("integration")
@SpringBootTest(classes = {MongoAutoConfiguration.class})
@ExtendWith(MongoExtension.class)
public class PublisherMongoDocumentIT {

    private final static String COLLECTION_NAME = "specifications.event.horizon.telekom.de.v1";

    @Autowired
    private PublisherMongoRepo mongoRepo;

    @Autowired
    private MongoTemplate mongoTemplate;

    private PublisherMongoDocument document = TestUtils.readObjectFromResources(
            "data/publisher-example.json",
            PublisherMongoDocument.class
    );

    public PublisherMongoDocumentIT() throws IOException {
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("provideTestData")
    void testPublisherMongoDocument(String testName, SearchType searchType) throws Exception {
        // ARRANGE
        PublisherMongoDocument document = TestUtils.readObjectFromResources(
                "data/publisher-example.json",
                PublisherMongoDocument.class
        );

        String testId = UUID.randomUUID().toString();
        document.setId(testId);

        PublisherMongoDocument insertedDocument = mongoTemplate.insert(document, COLLECTION_NAME);
        assertNotNull(insertedDocument);
        assertNotNull(insertedDocument.getId());

        // ACT & ASSERT
        List<PublisherMongoDocument> foundDoc = new ArrayList<>();

        switch (searchType) {
            case EVENT_TYPE:
                foundDoc = mongoRepo.findByEventType(document.getSpec().getEventType());
                break;

            case PUBLISHER_ID:
                foundDoc = mongoRepo.findByPublisherId(insertedDocument.getSpec().getPublisherId());
                break;

            case ID:
                mongoRepo.findById(testId).ifPresent(foundDoc::add);
                break;
        }

        assertThat(foundDoc)
                .withFailMessage("The previous stored EventSpecification-Document could not be found by '" + searchType + "' in the repository")
                .isNotEmpty();
    }

    static Stream<Arguments> provideTestData() {
        return Stream.of(
                Arguments.of(
                        "Retrieve PublisherDocument by id",
                        SearchType.ID
                ),
                Arguments.of(
                        "Retrieve PublisherDocument by eventType",
                        SearchType.EVENT_TYPE
                ),
                Arguments.of(
                        "Retrieve PublisherDocument by publisherId",
                        SearchType.PUBLISHER_ID
                )
        );
    }

    enum SearchType {
        EVENT_TYPE,
        PUBLISHER_ID,
        ID
    }

}

