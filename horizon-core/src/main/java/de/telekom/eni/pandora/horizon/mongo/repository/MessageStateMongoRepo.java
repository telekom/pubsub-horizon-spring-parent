package de.telekom.eni.pandora.horizon.mongo.repository;

import de.telekom.eni.pandora.horizon.model.event.DeliveryType;
import de.telekom.eni.pandora.horizon.model.event.Status;
import de.telekom.eni.pandora.horizon.mongo.model.MessageStateMongoDocument;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.Date;
import java.util.List;

public interface MessageStateMongoRepo extends MongoRepository<MessageStateMongoDocument, String> {

    List<MessageStateMongoDocument> findByStatus(Status status);
    Slice<MessageStateMongoDocument> findByStatus(Status status, Pageable pageable);

    List<MessageStateMongoDocument> findByDeliveryTypeAndStatus(DeliveryType deliveryType, Status status);
    Slice<MessageStateMongoDocument> findByDeliveryTypeAndStatus(DeliveryType deliveryType, Status status, Pageable pageable);

    List<MessageStateMongoDocument> findByStatusIn(List<Status> status);
    Slice<MessageStateMongoDocument> findByStatusIn(List<Status> status, Pageable pageable);

    Slice<MessageStateMongoDocument> findByDeliveryTypeAndStatusAndTimestampLessThanEqual(DeliveryType deliveryType, Status status, Date upperTimestampThreshold);
    Slice<MessageStateMongoDocument> findByDeliveryTypeAndStatusAndTimestampLessThanEqual(DeliveryType deliveryType, Status status, Date upperTimestampThreshold, Pageable pageable);

    Slice<MessageStateMongoDocument> findByDeliveryTypeAndStatusAndModifiedLessThanEqual(DeliveryType deliveryType, Status status, Date upperTimestampThreshold);
    Slice<MessageStateMongoDocument> findByDeliveryTypeAndStatusAndModifiedLessThanEqual(DeliveryType deliveryType, Status status, Date upperTimestampThreshold, Pageable pageable);


    @Query(value = "{ \"error.type\": \"de.telekom.horizon.dude.exception.CallbackUrlNotFoundException\" , \"status\": { \"$eq\": \"FAILED\" } }", sort = "{timestamp: 1}")
    Slice<MessageStateMongoDocument> findStatusFailedWithCallbackExceptionAsc();
    @Query(value = "{ \"error.type\": \"de.telekom.horizon.dude.exception.CallbackUrlNotFoundException\" , \"status\": { \"$eq\": \"FAILED\" } }", sort = "{timestamp: 1}")
    Slice<MessageStateMongoDocument> findStatusFailedWithCallbackExceptionAsc(Pageable pageable);


    @Query(value = "{status: {$in:  ?0}, deliveryType: ?1, subscriptionId: ?2}", sort = "{timestamp: 1}")
    List<MessageStateMongoDocument> findByStatusInAndDeliveryTypeAndSubscriptionIdAsc(List<Status> status, DeliveryType deliveryType, String subscriptionId);

    @Query(value = "{status: {$in:  ?0}, deliveryType: ?1, subscriptionId: ?2}", sort = "{timestamp: 1}")
    Slice<MessageStateMongoDocument> findByStatusInAndDeliveryTypeAndSubscriptionIdAsc(List<Status> status, DeliveryType deliveryType, String subscriptionId, Pageable pageable);

    @Query(value = "{status: {$in:  ?0}, deliveryType: ?1, subscriptionId: {$in:  ?2}}", sort = "{timestamp: 1}")
    List<MessageStateMongoDocument> findByStatusInAndDeliveryTypeAndSubscriptionIdsAsc(List<Status> status, DeliveryType deliveryType, List<String> subscriptionIds);

    @Query(value = "{status: {$in:  ?0}, deliveryType: ?1, subscriptionId: {$in:  ?2}}", sort = "{timestamp: 1}")
    Slice<MessageStateMongoDocument> findByStatusInAndDeliveryTypeAndSubscriptionIdsAsc(List<Status> status, DeliveryType deliveryType, List<String> subscriptionIds, Pageable pageable);

    @Query(value = "{$or:[{\"error.type\":{ $exists: false}},{\"error.type\":\"de.telekom.horizon.dude.exception.CallbackUrlNotFoundException\"}], status:{ $in: ?0 }, subscriptionId:{ $in: ?1 }, modified: { $lte: ?2 }}", sort = "{timestamp: 1}")
    List<MessageStateMongoDocument> findByStatusInPlusCallbackUrlNotFoundExceptionAsc(List<Status> status, List<String> subscriptionIds, Date timestampOlderThan);

    @Query(value = "{$or:[{\"error.type\":{ $exists: false}},{\"error.type\":\"de.telekom.horizon.dude.exception.CallbackUrlNotFoundException\"}], status:{ $in: ?0 }, subscriptionId:{ $in: ?1 }, modified: { $lte: ?2 } }", sort = "{timestamp: 1}")
    Slice<MessageStateMongoDocument> findByStatusInPlusCallbackUrlNotFoundExceptionAsc(List<Status> status, List<String> subscriptionIds, Date timestampOlderThan, Pageable pageable);


    List<MessageStateMongoDocument> findByMultiplexedFrom(String multiplexedFromId);

    Slice<MessageStateMongoDocument> findByMultiplexedFrom(String multiplexedFromId, Pageable pageable);

    @Query("{\"coordinates.partition\": ?0, status: {$in:  ?1}}")
    List<MessageStateMongoDocument> findByPartitionAndStatus(long partition, List<Status> status);

    @Query("{\"coordinates.partition\": ?0, status: {$in:  ?1}}")
    Slice<MessageStateMongoDocument> findByPartitionAndStatus(long partition, List<Status> status, Pageable pageable);

    @Query("{\"coordinates.partition\": ?0, status:  {$in:  ?1}, deliveryType: ?2}")
    List<MessageStateMongoDocument> findByPartitionAndStatusAndDeliveryType(long partition, List<Status> status, DeliveryType deliveryType);

    @Query("{\"coordinates.partition\": ?0, status:  {$in:  ?1}, deliveryType: ?2}")
    Slice<MessageStateMongoDocument> findByPartitionAndStatusAndDeliveryType(long partition, List<Status> status, DeliveryType deliveryType, Pageable pageable);
}

