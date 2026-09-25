package com.gitutility.repository.h2;

import com.gitutility.persistence.PersistenceConditions.OnH2;
import com.gitutility.repository.UnmappedWebhookEventRepository;
import com.gitutility.model.entity.UnmappedWebhookEvent;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * H2 (Spring Data JPA) implementation of the UnmappedWebhookEventRepository store facade.
 * Active only when git-utility.persistence.provider=h2 (default);
 * the MongoDB implementation lives in repository.mongo.
 */
@Repository
@OnH2
@RequiredArgsConstructor
public class H2UnmappedWebhookEventStore implements UnmappedWebhookEventRepository {

    private final UnmappedWebhookEventJpaRepository jpa;

    @Override
    public List<UnmappedWebhookEvent> findTop100ByOrderByReceivedAtDesc() {
        return jpa.findTop100ByOrderByReceivedAtDesc();
    }

    @Override
    public List<UnmappedWebhookEvent> findByDiscardReasonOrderByReceivedAtAsc(String discardReason) {
        return jpa.findByDiscardReasonOrderByReceivedAtAsc(discardReason);
    }

    @Override
    public List<UnmappedWebhookEvent> findAllByOrderByReceivedAtDesc() {
        return jpa.findAllByOrderByReceivedAtDesc();
    }

    @Override
    public int deleteOlderThan(Instant cutoff) {
        return jpa.deleteOlderThan(cutoff);
    }

    @Override
    public UnmappedWebhookEvent save(UnmappedWebhookEvent entity) {
        return jpa.save(entity);
    }

    @Override
    public List<UnmappedWebhookEvent> saveAll(Iterable<UnmappedWebhookEvent> entities) {
        return jpa.saveAll(entities);
    }

    @Override
    public java.util.Optional<UnmappedWebhookEvent> findById(String id) {
        return jpa.findById(id);
    }

    @Override
    public boolean existsById(String id) {
        return jpa.existsById(id);
    }

    @Override
    public List<UnmappedWebhookEvent> findAll() {
        return jpa.findAll();
    }

    @Override
    public long count() {
        return jpa.count();
    }

    @Override
    public void delete(UnmappedWebhookEvent entity) {
        jpa.delete(entity);
    }

    @Override
    public void deleteById(String id) {
        jpa.deleteById(id);
    }

    @Override
    public void deleteAll() {
        jpa.deleteAll();
    }

}
