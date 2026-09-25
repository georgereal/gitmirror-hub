package com.gitutility.repository.h2;

import com.gitutility.persistence.PersistenceConditions.OnH2;
import com.gitutility.repository.BulkSubmissionRepository;
import com.gitutility.model.entity.BulkSubmission;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * H2 (Spring Data JPA) implementation of the BulkSubmissionRepository store facade.
 * Active only when git-utility.persistence.provider=h2 (default);
 * the MongoDB implementation lives in repository.mongo.
 */
@Repository
@OnH2
@RequiredArgsConstructor
public class H2BulkSubmissionStore implements BulkSubmissionRepository {

    private final BulkSubmissionJpaRepository jpa;

    @Override
    public BulkSubmission save(BulkSubmission entity) {
        return jpa.save(entity);
    }

    @Override
    public List<BulkSubmission> saveAll(Iterable<BulkSubmission> entities) {
        return jpa.saveAll(entities);
    }

    @Override
    public Optional<BulkSubmission> findById(String id) {
        return jpa.findById(id);
    }

    @Override
    public boolean existsById(String id) {
        return jpa.existsById(id);
    }

    @Override
    public List<BulkSubmission> findAll() {
        return jpa.findAll();
    }

    @Override
    public long count() {
        return jpa.count();
    }

    @Override
    public void delete(BulkSubmission entity) {
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
