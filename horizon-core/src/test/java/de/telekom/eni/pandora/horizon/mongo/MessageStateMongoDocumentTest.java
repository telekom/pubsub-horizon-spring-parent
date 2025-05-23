// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.mongo;


import de.telekom.eni.pandora.horizon.autoconfigure.mongo.MongoAutoConfiguration;
import de.telekom.eni.pandora.horizon.model.db.Coordinates;
import de.telekom.eni.pandora.horizon.model.db.PartialEvent;
import de.telekom.eni.pandora.horizon.model.db.StateProperty;
import de.telekom.eni.pandora.horizon.model.event.DeliveryType;
import de.telekom.eni.pandora.horizon.model.event.Status;
import de.telekom.eni.pandora.horizon.model.meta.EventRetentionTime;
import de.telekom.eni.pandora.horizon.mongo.model.MessageStateMongoDocument;
import de.telekom.eni.pandora.horizon.mongo.repository.MessageStateMongoRepo;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit4.SpringRunner;

import java.sql.Time;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

@RunWith(SpringRunner.class)
@SpringBootTest(classes={MongoAutoConfiguration.class})
@EnableAutoConfiguration
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(MongoTestServerConfiguration.class)
public class MessageStateMongoDocumentTest {

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("horizon.mongo.enabled", () -> true);
    }

    @Autowired
    private MessageStateMongoRepo messageStateMongoRepo;

    @Autowired
    private MongoTemplate mongoTemplate;

    static int testPartition;

    static DeliveryType testDeliveryType;

    static MessageStateMongoDocument testMessageStateMongoDocumentProcessed;

    static MessageStateMongoDocument testMessageStateMongoDocumentDropped;

    static MessageStateMongoDocument testMessageStateMongoDocumentWaiting;

    static MessageStateMongoDocument testMessageStateMongoDocumentDelivered;

    @BeforeAll
    static void initContainer() {
        //mongoDBContainer.start();

        testPartition = 123;
        testDeliveryType = DeliveryType.CALLBACK;


        testMessageStateMongoDocumentProcessed = createDummyStateWithStatus(Status.PROCESSED, "abcd-published-uuid");
        var multiplexedFrom = testMessageStateMongoDocumentProcessed.getMultiplexedFrom();

        testMessageStateMongoDocumentDropped = createDummyStateWithStatus(Status.DROPPED, multiplexedFrom);
        testMessageStateMongoDocumentWaiting = createDummyStateWithStatus(Status.WAITING, multiplexedFrom);
        testMessageStateMongoDocumentDelivered = createDummyStateWithStatus(Status.DELIVERED, multiplexedFrom);
    }

    @Test
    @Order(1)
    @DisplayName("Verify that messageStateMongoRepo is running")
    void testContainerRun() {
//        assertNotNull(embeddedMongoServer);
        var collectionName = mongoTemplate.getCollectionName(MessageStateMongoDocument.class);
        var collection = mongoTemplate.getCollection(collectionName);
        assertNotNull(collection);
    }

    @Test
    @Order(2)
    @DisplayName("Save StatusMessage in database")
    void testCreateStatusMessage() {
        List<MessageStateMongoDocument> savedMessage = messageStateMongoRepo.saveAll(List.of(testMessageStateMongoDocumentWaiting, testMessageStateMongoDocumentProcessed, testMessageStateMongoDocumentDropped, testMessageStateMongoDocumentDelivered));
        assertNotNull(savedMessage);
    }

    @Test
    @Order(3)
    @DisplayName("Search for inserted StatusMessage by Status")
    void testFindByStatus() {
        List<MessageStateMongoDocument> foundMessages = messageStateMongoRepo.findByStatus(Status.PROCESSED);
        assertNotNull(foundMessages);
        assertEquals(1, foundMessages.size());
        assertEquals(Status.PROCESSED, foundMessages.getFirst().getStatus());
    }

    @Test
    @Order(4)
    @DisplayName("Search for inserted StatusMessage by Status and subscriptionIds")
    void testFindByStatusInPlusCallbackUrlNotFoundExceptionAsc() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "timestamp"));
        var subscriptionIds = List.of(testMessageStateMongoDocumentWaiting.getSubscriptionId());
        var ts = new Date();
        var foundMessages = messageStateMongoRepo.findByStatusInPlusCallbackUrlNotFoundExceptionAsc(List.of(Status.WAITING, Status.FAILED), subscriptionIds, ts, pageable);
        assertNotNull(foundMessages);
        assertEquals(1, foundMessages.getNumberOfElements());
        assertEquals(Status.WAITING, foundMessages.getContent().getFirst().getStatus());
        assertTrue(foundMessages.getContent().getFirst().getModified().getTime() <= ts.getTime());

    }

    @Test
    @Order(5)
    @DisplayName("Search for inserted StatusMessage by various Status")
    void testFindByStatusIn() {
        final List<Status> requiredStatus = List.of(Status.PROCESSED, Status.DROPPED);
        List<MessageStateMongoDocument> foundMessages = messageStateMongoRepo.findByStatusIn(requiredStatus);
        assertNotNull(foundMessages);
        assertEquals(2, foundMessages.size());
        for (MessageStateMongoDocument message : foundMessages) {
            assertTrue(requiredStatus.contains(message.getStatus()));
            assertNotNull(message.getUuid());
        }
    }

    @Test
    @Order(6)
    @DisplayName("Search for inserted StatusMessage by various Status with limit")
    void testFindByStatusInLimit() {
        final List<Status> requiredStatus = List.of(Status.PROCESSED, Status.WAITING);
        Slice<MessageStateMongoDocument> foundMessages = messageStateMongoRepo.findByStatusIn(requiredStatus, Pageable.ofSize(1));
        assertNotNull(foundMessages);
        assertEquals(1, foundMessages.getNumberOfElements());
        for (MessageStateMongoDocument message : foundMessages) {
            assertTrue(requiredStatus.contains(message.getStatus()));
        }
    }

    @Test
    @Order(7)
    @DisplayName("Search for inserted StatusMessage by Status, DeliveryType and SubscriptionId")
    void testFindByStatusAndDeliveryTypeAndSubscriptionId() {
        final List<Status> requiredStatus = List.of(Status.PROCESSED, Status.DELIVERED);
        var subscriptionId = String.valueOf(testMessageStateMongoDocumentDelivered.getSubscriptionId());

        List<MessageStateMongoDocument> foundMessages = messageStateMongoRepo.findByStatusInAndDeliveryTypeAndSubscriptionIdAsc(requiredStatus, testDeliveryType,subscriptionId);

        assertNotNull(foundMessages);
        assertEquals(1, foundMessages.size());
        for (MessageStateMongoDocument message : foundMessages) {
            assertTrue(requiredStatus.contains(message.getStatus()));
        }

        var firstMessage = foundMessages.stream().findFirst();
        assertTrue(firstMessage.isPresent());

        assertEquals(String.valueOf(testMessageStateMongoDocumentDelivered.getProperty(StateProperty.SUBSCRIBER_ID)), String.valueOf(foundMessages.getFirst().getProperty(StateProperty.SUBSCRIBER_ID)));
    }

    @Test
    @Order(8)
    @DisplayName("Search for inserted StatusMessage by MultiplexedFrom")
    void testFindByMultiplexedFrom() {
        var testPublishedMessageId = testMessageStateMongoDocumentProcessed.getMultiplexedFrom();
        List<MessageStateMongoDocument> foundMessages = messageStateMongoRepo.findByMultiplexedFrom(testPublishedMessageId);
        assertNotNull(foundMessages);
        assertEquals(4, foundMessages.size());

        foundMessages.forEach(msg -> {
            var publishedMessageId = msg.getMultiplexedFrom();
            assertNotNull(publishedMessageId);
            assertEquals(testPublishedMessageId, publishedMessageId);
        });
    }

    @Test
    @Order(9)
    @DisplayName("Search for inserted StatusMessage by Status and Partition")
    void testFindByPartitionAndStatus() {
        final List<Status> requiredStatus = List.of(Status.PROCESSED, Status.DELIVERED);
        List<MessageStateMongoDocument> foundMessages = messageStateMongoRepo.findByPartitionAndStatus(testPartition, requiredStatus);
        assertNotNull(foundMessages);
        assertEquals(2, foundMessages.size());
        for (MessageStateMongoDocument message : foundMessages) {
            assertTrue(requiredStatus.contains(message.getStatus()));
        }
    }

    @Test
    @Order(10)
    @DisplayName("Search for inserted StatusMessage by Status and Partition and DeliveryType")
    void testFindByPartitionAndStatusAndDeliveryType() {
        final List<Status> requiredStatus = List.of(Status.PROCESSED, Status.DELIVERED);
        List<MessageStateMongoDocument> foundMessages = messageStateMongoRepo.findByPartitionAndStatusAndDeliveryType(testPartition, requiredStatus, testDeliveryType);
        assertNotNull(foundMessages);
        assertEquals(2, foundMessages.size());
        for (MessageStateMongoDocument message : foundMessages) {
            assertTrue(requiredStatus.contains(message.getStatus()));
            assertEquals(testDeliveryType, message.getDeliveryType());
        }
    }

    @ParameterizedTest
    @Order(11)
    @ValueSource(booleans = {false, true})
    @DisplayName("Search for any inserted StatusMessage by DeliveryType and SubscriptionId that has been created after a given timestamp")
    void findByDeliveryTypeAndSubscriptionIdAndTimestampGreaterThanAsc(boolean pagable) throws InterruptedException {
        var subscriptionId = UUID.randomUUID().toString();

        var docCount = 10;

        // first, we will create a bunch of documents for the given subscriptionId
        var documents = new ArrayList<MessageStateMongoDocument>();
        for (var i = 0; i < docCount; i++) {
            var index = ThreadLocalRandom.current().nextInt(0, Status.values().length);
            // create document with random status
            var document = createDummyStateWithStatus(subscriptionId, Status.values()[index], UUID.randomUUID().toString());
            Thread.sleep(1); // sleep 1 ms otherwise timestamps would be all the same.
            documents.add(document);
        }
        messageStateMongoRepo.saveAll(documents);

        var doc = documents.get(3); // pick 4th document

        var timestamp = doc.getTimestamp();

        // find all messages for the given subscription that are newer than the 4th message
        List<MessageStateMongoDocument> foundMessages = null;

        if (pagable) {
            Pageable pageable = PageRequest.of(0, docCount, Sort.by(Sort.Direction.ASC, "timestamp"));
            foundMessages = messageStateMongoRepo.findByDeliveryTypeAndSubscriptionIdAndTimestampGreaterThanAsc(testDeliveryType, subscriptionId, timestamp, pageable).getContent();
        } else {
            foundMessages = messageStateMongoRepo.findByDeliveryTypeAndSubscriptionIdAndTimestampGreaterThanAsc(testDeliveryType, subscriptionId, timestamp);
        }

        assertNotNull(foundMessages);

        // we expect to get 6 messages that are newer (total of 10)
        var expectedDocsCount = documents.size() - (documents.indexOf(doc) +1);
        assertEquals(expectedDocsCount, foundMessages.size());

        // let's check if all filter criteria apply to the entries found
        var result = foundMessages.stream()
                .filter(m -> testDeliveryType.equals(m.getDeliveryType()))
                .filter(m -> subscriptionId.equals(m.getSubscriptionId()))
                .filter(m -> timestamp.toInstant().isBefore(m.getTimestamp().toInstant()))
                .toList();

        assertEquals(expectedDocsCount, result.size());
    }

    static MessageStateMongoDocument createDummyStateWithStatus(Status status, String multiplexedFrom) {
        return createDummyStateWithStatus(UUID.randomUUID().toString(), status, multiplexedFrom);
    }


    static MessageStateMongoDocument createDummyStateWithStatus(String subscriptionId, Status status, String multiplexedFrom) {
        return new MessageStateMongoDocument( //
                UUID.randomUUID().toString(), // String uuid,
                new Coordinates(testPartition, 458), // Coordinates,
                status, // Status status,
                "testEnvironment", // String environment,
                testDeliveryType, // DeliveryType deliveryType,
                subscriptionId, // String subscriptionId,
                new PartialEvent(UUID.randomUUID().toString(), "tardis.horizon.loadtest.v1"), // PartialEvent partialEvent,
                new HashMap<>(), // Map<String, String> properties,
                multiplexedFrom,
                EventRetentionTime.TTL_7_DAYS,
                Time.from(Instant.now()), // Date timestamp,
                Time.from(Instant.now()), // Date modified,
                null, // StateError error,
                Collections.emptyList(), // List<String> appliedScopes,
                null, // EvaluationResult scopeEvaluationResult,
                null // EvaluationResult consumerEvaluationResult
        );
    }
}
