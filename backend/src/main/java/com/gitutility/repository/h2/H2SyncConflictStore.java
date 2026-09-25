package com.gitutility.repository.h2;

import com.gitutility.persistence.PersistenceConditions.OnH2;
import com.gitutility.repository.SyncConflictRepository;
import com.gitutility.model.entity.SyncConflict;
import com.gitutility.model.enums.ConflictStatus;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * H2 (Spring Data JPA) implementation of the SyncConflictRepository store facade.
 * Active only when git-utility.persistence.provider=h2 (default);
 * the MongoDB implementation lives in repository.mongo.
 */
@Repository
@OnH2
@RequiredArgsConstructor
public class H2SyncConflictStore implements SyncConflictRepository {

    private final SyncConflictJpaRepository jpa;

    @Override
    public List<SyncConflict> findByMappingIdOrderByCreatedAtDesc(String mappingId) {
        return jpa.findByMappingIdOrderByCreatedAtDesc(mappingId);
    }

    @Override
    public List<SyncConflict> findByMappingIdAndStatusOrderByCreatedAtDesc(String mappingId, ConflictStatus status) {
        return jpa.findByMappingIdAndStatusOrderByCreatedAtDesc(mappingId, status);
    }

    @Override
    public Optional<SyncConflict> findByMappingIdAndRefNameAndDestShaAndStatus(String mappingId, String refName, String destSha, ConflictStatus status) {
        return jpa.findByMappingIdAndRefNameAndDestShaAndStatus(mappingId, refName, destSha, status);
    }

    @Override
    public SyncConflict save(SyncConflict entity) {
        return jpa.save(entity);
    }

    @Override
    public List<SyncConflict> saveAll(Iterable<SyncConflict> entities) {
        return jpa.saveAll(entities);
    }

    @Override
    public Optional<SyncConflict> findById(String id) {
        return jpa.findById(id);
    }

    @Override
    public boolean existsById(String id) {
        return jpa.existsById(id);
    }

    @Override
    public List<SyncConflict> findAll() {
        return jpa.findAll();
    }

    @Override
    public long count() {
        return jpa.count();
    }

    @Override
    public void delete(SyncConflict entity) {
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
