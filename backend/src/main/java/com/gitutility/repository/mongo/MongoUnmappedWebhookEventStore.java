package com.gitutility.repository.mongo;

import com.gitutility.persistence.PersistenceConditions.OnMongo;
import com.gitutility.repository.UnmappedWebhookEventRepository;
import com.gitutility.model.entity.UnmappedWebhookEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MongoDB implementation of the UnmappedWebhookEventRepository store facade.
 * Active only when git-utility.persistence.provider=mongo;
 * the H2 implementation lives in repository.h2.
 */
@Repository
@OnMongo
@RequiredArgsConstructor
public class MongoUnmappedWebhookEventStore implements UnmappedWebhookEventRepository {

    private final UnmappedWebhookEventMongoRepository repo;
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    @Override
    public List<UnmappedWebhookEvent> findTop100ByOrderByReceivedAtDesc() {
return repo.findTop100ByOrderByReceivedAtDesc();
    }

    @Override
    public List<UnmappedWebhookEvent> findByDiscardReasonOrderByReceivedAtAsc(String discardReason) {
        return repo.findByDiscardReasonOrderByReceivedAtAsc(discardReason);
    }

    @Override
    public List<UnmappedWebhookEvent> findAllByOrderByReceivedAtDesc() {
return repo.findAllByOrderByReceivedAtDesc();
    }

    @Override
    public int deleteOlderThan(Instant cutoff) {
        return (int) mongoTemplate.remove(
                Query.query(Criteria.where("receivedAt").lt(cutoff)
                        .and("discardReason").ne("KAFKA_POISON")),
                UnmappedWebhookEvent.class).getDeletedCount();
    }

    @Override
    public UnmappedWebhookEvent save(UnmappedWebhookEvent entity) {
return repo.save(entity);
    }

    @Override
    public List<UnmappedWebhookEvent> saveAll(Iterable<UnmappedWebhookEvent> entities) {
return repo.saveAll(entities);
    }

    @Override
    public java.util.Optional<UnmappedWebhookEvent> findById(String id) {
return repo.findById(id);
    }

    @Override
    public boolean existsById(String id) {
return repo.existsById(id);
    }

    @Override
    public List<UnmappedWebhookEvent> findAll() {
return repo.findAll();
    }

    @Override
    public long count() {
return repo.count();
    }

    @Override
    public void delete(UnmappedWebhookEvent entity) {
repo.delete(entity);
    }

    @Override
    public void deleteById(String id) {
repo.deleteById(id);
    }

    @Override
    public void deleteAll() {
repo.deleteAll();
    }

}
