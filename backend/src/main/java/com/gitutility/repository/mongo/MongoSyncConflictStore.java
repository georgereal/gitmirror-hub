package com.gitutility.repository.mongo;

import com.gitutility.persistence.PersistenceConditions.OnMongo;
import com.gitutility.repository.SyncConflictRepository;
import com.gitutility.model.entity.SyncConflict;
import com.gitutility.model.enums.ConflictStatus;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MongoDB implementation of the SyncConflictRepository store facade.
 * Active only when git-utility.persistence.provider=mongo;
 * the H2 implementation lives in repository.h2.
 */
@Repository
@OnMongo
@RequiredArgsConstructor
public class MongoSyncConflictStore implements SyncConflictRepository {

    private final SyncConflictMongoRepository repo;

    @Override
    public List<SyncConflict> findByMappingIdOrderByCreatedAtDesc(String mappingId) {
return repo.findByMappingIdOrderByCreatedAtDesc(mappingId);
    }

    @Override
    public List<SyncConflict> findByMappingIdAndStatusOrderByCreatedAtDesc(String mappingId, ConflictStatus status) {
return repo.findByMappingIdAndStatusOrderByCreatedAtDesc(mappingId, status);
    }

    @Override
    public Optional<SyncConflict> findByMappingIdAndRefNameAndDestShaAndStatus(String mappingId, String refName, String destSha, ConflictStatus status) {
return repo.findByMappingIdAndRefNameAndDestShaAndStatus(mappingId, refName, destSha, status);
    }

    @Override
    public SyncConflict save(SyncConflict entity) {
return repo.save(entity);
    }

    @Override
    public List<SyncConflict> saveAll(Iterable<SyncConflict> entities) {
return repo.saveAll(entities);
    }

    @Override
    public Optional<SyncConflict> findById(String id) {
return repo.findById(id);
    }

    @Override
    public boolean existsById(String id) {
return repo.existsById(id);
    }

    @Override
    public List<SyncConflict> findAll() {
return repo.findAll();
    }

    @Override
    public long count() {
return repo.count();
    }

    @Override
    public void delete(SyncConflict entity) {
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
