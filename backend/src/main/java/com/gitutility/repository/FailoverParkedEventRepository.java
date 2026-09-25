package com.gitutility.repository;

import com.gitutility.model.entity.FailoverParkedEvent;

import java.util.List;
import java.util.Optional;

public interface FailoverParkedEventRepository {

    Optional<FailoverParkedEvent> findByMappingIdAndDeliveryId(String mappingId, String deliveryId);

    List<FailoverParkedEvent> findByMappingIdAndStatusOrderByReceivedAtAsc(String mappingId, String status);

    long countByMappingIdAndStatus(String mappingId, String status);

    FailoverParkedEvent save(FailoverParkedEvent entity);

    void deleteById(String id);
}
