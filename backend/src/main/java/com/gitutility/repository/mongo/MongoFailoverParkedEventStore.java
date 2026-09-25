package com.gitutility.repository.mongo;

import com.gitutility.model.entity.FailoverParkedEvent;
import com.gitutility.persistence.PersistenceConditions.OnMongo;
import com.gitutility.repository.FailoverParkedEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@OnMongo
@RequiredArgsConstructor
public class MongoFailoverParkedEventStore implements FailoverParkedEventRepository {

    private final FailoverParkedEventMongoRepository repo;

    @Override
    public Optional<FailoverParkedEvent> findByMappingIdAndDeliveryId(String mappingId, String deliveryId) {
        return repo.findByMappingIdAndDeliveryId(mappingId, deliveryId);
    }

    @Override
    public List<FailoverParkedEvent> findByMappingIdAndStatusOrderByReceivedAtAsc(String mappingId, String status) {
        return repo.findByMappingIdAndStatusOrderByReceivedAtAsc(mappingId, status);
    }

    @Override
    public long countByMappingIdAndStatus(String mappingId, String status) {
        return repo.countByMappingIdAndStatus(mappingId, status);
    }

    @Override
    public FailoverParkedEvent save(FailoverParkedEvent entity) {
        return repo.save(entity);
    }

    @Override
    public void deleteById(String id) {
        repo.deleteById(id);
    }
}
