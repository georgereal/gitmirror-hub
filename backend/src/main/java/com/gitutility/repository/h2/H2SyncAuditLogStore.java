package com.gitutility.repository.h2;

import com.gitutility.persistence.PersistenceConditions.OnH2;
import com.gitutility.repository.SyncAuditLogRepository;
import com.gitutility.model.entity.SyncAuditLog;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * H2 (Spring Data JPA) implementation of the SyncAuditLogRepository store facade.
 * Active only when git-utility.persistence.provider=h2 (default);
 * the MongoDB implementation lives in repository.mongo.
 */
@Repository
@OnH2
@RequiredArgsConstructor
public class H2SyncAuditLogStore implements SyncAuditLogRepository {

    private final SyncAuditLogJpaRepository jpa;

    @Override
    public List<SyncAuditLog> findByJobIdOrderByTimestampAsc(String jobId) {
        return jpa.findByJobIdOrderByTimestampAsc(jobId);
    }

    @Override
    public void deleteByJobId(String jobId) {
        jpa.deleteByJobId(jobId);
    }

    @Override
    public SyncAuditLog save(SyncAuditLog entity) {
        return jpa.save(entity);
    }

    @Override
    public List<SyncAuditLog> saveAll(Iterable<SyncAuditLog> entities) {
        return jpa.saveAll(entities);
    }

    @Override
    public Optional<SyncAuditLog> findById(String id) {
        return jpa.findById(id);
    }

    @Override
    public boolean existsById(String id) {
        return jpa.existsById(id);
    }

    @Override
    public List<SyncAuditLog> findAll() {
        return jpa.findAll();
    }

    @Override
    public long count() {
        return jpa.count();
    }

    @Override
    public void delete(SyncAuditLog entity) {
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
