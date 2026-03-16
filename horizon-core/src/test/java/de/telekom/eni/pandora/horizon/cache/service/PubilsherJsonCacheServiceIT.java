// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.cache.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.client.HazelcastClientOfflineException;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import de.telekom.eni.pandora.horizon.autoconfigure.cache.JsonCacheAutoConfiguration;
import de.telekom.eni.pandora.horizon.autoconfigure.mongo.MongoAutoConfiguration;
import de.telekom.eni.pandora.horizon.extension.HazelcastExtension;
import de.telekom.eni.pandora.horizon.extension.MongoExtension;
import de.telekom.eni.pandora.horizon.kubernetes.resource.PublisherResource;
import de.telekom.eni.pandora.horizon.mongo.model.PublisherMongoDocument;
import de.telekom.eni.pandora.horizon.mongo.repository.PublisherMongoRepo;
import de.telekom.eni.pandora.horizon.utils.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;

import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@Tag("mongodb")
@Tag("hazelcast")
@Tag("integration")
@SpringBootTest(
        classes = {MongoAutoConfiguration.class, JsonCacheAutoConfiguration.class},
        properties = {
                "horizon.cache.enabled=true"
        }
)
@ExtendWith(MongoExtension.class)
@ExtendWith(HazelcastExtension.class)
@Import(HazelcastExtension.HazelcastConfig.class)
public class PubilsherJsonCacheServiceIT {

    private final static String MAP_NAME = "specifications.event.horizon.telekom.de.v1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private PublisherMongoRepo mongoRepo;
    @SpyBean
    private HazelcastInstance hazelcastInstance;
    @Autowired
    private JsonCacheService<PublisherResource> testInstance;


    @AfterEach
    void tearDown() {
        reset(hazelcastInstance);
        mongoRepo.deleteAll();
        hazelcastInstance.getMap(MAP_NAME).clear();
    }

    static Stream<Arguments> provideGetByKeyTestData() {
        return Stream.of(
                Arguments.of(
                        "Retrieve from Hazelcast",
                        CacheSource.HAZELCAST,
                        true,
                        "EventSpecification should be found in Hazelcast"
                ),
                Arguments.of(
                        "Retrieve from MongoDB fallback",
                        CacheSource.MONGODB_FALLBACK,
                        true,
                        "EventSpecification should be found in MongoDB"
                ),
                Arguments.of(
                        "Not found in any cache",
                        CacheSource.NONE,
                        false,
                        "EventSpecification should not be found: neither Hazelcast nor MongoDB"
                )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("provideGetByKeyTestData")
    void testGetByKey(String testName, CacheSource source, boolean shouldBePresent, String assertionMessage) throws Exception {
        // ARRANGE
        String testId;
        PublisherMongoDocument document = TestUtils.readObjectFromResources(
                "data/publisher-example.json",
                PublisherMongoDocument.class
        );
        testId = "cit--" + document.getSpec().getEventType() + "--hub--team";
        document.setId(testId);


        switch (source) {
            case HAZELCAST:
                String jsonString = objectMapper.writeValueAsString(document);
                IMap<String, HazelcastJsonValue> map = hazelcastInstance.getMap(MAP_NAME);
                HazelcastJsonValue jsonValue = new HazelcastJsonValue(jsonString);
                map.put(testId, jsonValue);
                break;

            case MONGODB_FALLBACK:
                when(hazelcastInstance.getMap(anyString()))
                        .thenThrow(new HazelcastClientOfflineException());
                mongoRepo.save(document);
                break;

            case NONE:
                // No setup needed - data doesn't exist
                break;
        }

        // ACT
        Optional<PublisherResource> result = testInstance.getByKey(testId);

        // ASSERT
        assertEquals(shouldBePresent, result.isPresent(), assertionMessage);

        if (shouldBePresent) {
            assertEquals(document.getSpec().getEventType(), result.get().getSpec().getEventType());
        }
    }

    enum CacheSource {
        HAZELCAST,
        MONGODB_FALLBACK,
        NONE
    }

}
