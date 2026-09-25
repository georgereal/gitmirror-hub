package com.gitutility.repository.h2;

import com.gitutility.model.entity.FailoverParkedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FailoverParkedEventJpaRepository extends JpaRepository<FailoverParkedEvent, String> {
    Optional<FailoverParkedEvent> findByMappingIdAndDeliveryId(String mappingId, String deliveryId);

    List<FailoverParkedEvent> findByMappingIdAndStatusOrderByReceivedAtAsc(String mappingId, String status);

    long countByMappingIdAndStatus(String mappingId, String status);
}
