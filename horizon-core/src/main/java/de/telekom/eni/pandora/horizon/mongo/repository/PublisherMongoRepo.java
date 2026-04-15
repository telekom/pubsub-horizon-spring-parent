// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.mongo.repository;

import de.telekom.eni.pandora.horizon.mongo.model.PublisherMongoDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;


public interface PublisherMongoRepo extends MongoRepository<PublisherMongoDocument, String> {

    @Query(value = "{ \"spec.eventType\": ?0}")
    List<PublisherMongoDocument> findByEventType(String type);

    @Query(value = "{ \"spec.publisherId\": ?0}")
    List<PublisherMongoDocument> findByPublisherId(String publisherId);

}