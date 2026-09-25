package com.gitutility.repository.mongo;

import com.gitutility.model.entity.FailoverParkedEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface FailoverParkedEventMongoRepository extends MongoRepository<FailoverParkedEvent, String> {
    Optional<FailoverParkedEvent> findByMappingIdAndDeliveryId(String mappingId, String deliveryId);

    List<FailoverParkedEvent> findByMappingIdAndStatusOrderByReceivedAtAsc(String mappingId, String status);

    long countByMappingIdAndStatus(String mappingId, String status);
}
