package com.gitutility.repository.h2;

import com.gitutility.model.entity.FailoverParkedEvent;
import com.gitutility.persistence.PersistenceConditions.OnH2;
import com.gitutility.repository.FailoverParkedEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@OnH2
@RequiredArgsConstructor
public class H2FailoverParkedEventStore implements FailoverParkedEventRepository {

    private final FailoverParkedEventJpaRepository jpa;

    @Override
    public Optional<FailoverParkedEvent> findByMappingIdAndDeliveryId(String mappingId, String deliveryId) {
        return jpa.findByMappingIdAndDeliveryId(mappingId, deliveryId);
    }

    @Override
    public List<FailoverParkedEvent> findByMappingIdAndStatusOrderByReceivedAtAsc(String mappingId, String status) {
        return jpa.findByMappingIdAndStatusOrderByReceivedAtAsc(mappingId, status);
    }

    @Override
    public long countByMappingIdAndStatus(String mappingId, String status) {
        return jpa.countByMappingIdAndStatus(mappingId, status);
    }

    @Override
    public FailoverParkedEvent save(FailoverParkedEvent entity) {
        return jpa.save(entity);
    }

    @Override
    public void deleteById(String id) {
        jpa.deleteById(id);
    }
}
