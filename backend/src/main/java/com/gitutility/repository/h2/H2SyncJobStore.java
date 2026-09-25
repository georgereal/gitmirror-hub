package com.gitutility.repository.h2;

import com.gitutility.persistence.PersistenceConditions.OnH2;
import com.gitutility.repository.SyncJobRepository;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.model.enums.TriggerType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * H2 (Spring Data JPA) implementation of the SyncJobRepository store facade.
 * Active only when git-utility.persistence.provider=h2 (default);
 * the MongoDB implementation lives in repository.mongo.
 */
@Repository
@OnH2
@RequiredArgsConstructor
public class H2SyncJobStore implements SyncJobRepository {

    private final SyncJobJpaRepository jpa;

    @Override
    public List<SyncJob> findTop20ByOrderByCreatedAtDesc() {
        return jpa.findTop20ByOrderByCreatedAtDesc();
    }

    @Override
    public Page<SyncJob> findAllByOrderByCreatedAtDesc(Pageable pageable) {
        return jpa.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Override
    public Page<SyncJob> findByMappingIdOrderByCreatedAtDesc(String mappingId, Pageable pageable) {
        return jpa.findByMappingIdOrderByCreatedAtDesc(mappingId, pageable);
    }

    @Override
    public Page<SyncJob> findByStatusOrderByCreatedAtDesc(SyncStatus status, Pageable pageable) {
        return jpa.findByStatusOrderByCreatedAtDesc(status, pageable);
    }

    @Override
    public List<SyncJob> findByStatus(SyncStatus status) {
        return jpa.findByStatus(status);
    }

    @Override
    public List<SyncJob> findByStatusAndMappingId(SyncStatus status, String mappingId) {
        return jpa.findByStatusAndMappingId(status, mappingId);
    }

    @Override
    public Optional<SyncJob> findByQueueMessageId(String queueMessageId) {
        return jpa.findByQueueMessageId(queueMessageId);
    }

    @Override
    public Page<SyncJob> search(SyncStatus status, String mappingId, TriggerType triggerType, String lane, Pageable pageable) {
        return jpa.search(status, mappingId, triggerType, lane, pageable);
    }

    @Override
    public long countByStatus(SyncStatus status) {
        return jpa.countByStatus(status);
    }

    @Override
    public long countJobsSince(Instant since) {
        return jpa.countJobsSince(since);
    }

    @Override
    public long countSuccessJobsSince(Instant since) {
        return jpa.countSuccessJobsSince(since);
    }

    @Override
    public List<SyncJob> findForUsageWindow(Instant since) {
        return jpa.findForUsageWindow(since);
    }

    @Override
    public SyncJob save(SyncJob entity) {
        return jpa.save(entity);
    }

    @Override
    public List<SyncJob> saveAll(Iterable<SyncJob> entities) {
        return jpa.saveAll(entities);
    }

    @Override
    public Optional<SyncJob> findById(String id) {
        return jpa.findById(id);
    }

    @Override
    public boolean existsById(String id) {
        return jpa.existsById(id);
    }

    @Override
    public List<SyncJob> findAll() {
        return jpa.findAll();
    }

    @Override
    public long count() {
        return jpa.count();
    }

    @Override
    public void delete(SyncJob entity) {
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
