package com.gitutility.repository.mongo;

import com.gitutility.persistence.PersistenceConditions.OnMongo;
import com.gitutility.repository.SyncAuditLogRepository;
import com.gitutility.model.entity.SyncAuditLog;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MongoDB implementation of the SyncAuditLogRepository store facade.
 * Active only when git-utility.persistence.provider=mongo;
 * the H2 implementation lives in repository.h2.
 */
@Repository
@OnMongo
@RequiredArgsConstructor
public class MongoSyncAuditLogStore implements SyncAuditLogRepository {

    private final SyncAuditLogMongoRepository repo;

    @Override
    public List<SyncAuditLog> findByJobIdOrderByTimestampAsc(String jobId) {
return repo.findByJobIdOrderByTimestampAsc(jobId);
    }

    @Override
    public void deleteByJobId(String jobId) {
repo.deleteByJobId(jobId);
    }

    @Override
    public SyncAuditLog save(SyncAuditLog entity) {
return repo.save(entity);
    }

    @Override
    public List<SyncAuditLog> saveAll(Iterable<SyncAuditLog> entities) {
return repo.saveAll(entities);
    }

    @Override
    public Optional<SyncAuditLog> findById(String id) {
return repo.findById(id);
    }

    @Override
    public boolean existsById(String id) {
return repo.existsById(id);
    }

    @Override
    public List<SyncAuditLog> findAll() {
return repo.findAll();
    }

    @Override
    public long count() {
return repo.count();
    }

    @Override
    public void delete(SyncAuditLog entity) {
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
