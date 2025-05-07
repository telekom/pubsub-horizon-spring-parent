// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.eni.pandora.horizon.mongo.repository;

import de.telekom.eni.pandora.horizon.mongo.model.SubscriptionMongoDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;


public interface SubscriptionsMongoRepo extends MongoRepository<SubscriptionMongoDocument, String> {

    @Query(value = "{ \"spec.subscription.type\": ?0}")
    List<SubscriptionMongoDocument> findByType(String type);
}



