package com.gitutility.repository.mongo;

import com.gitutility.persistence.PersistenceConditions.OnMongo;
import com.gitutility.repository.BulkSubmissionRepository;
import com.gitutility.model.entity.BulkSubmission;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MongoDB implementation of the BulkSubmissionRepository store facade.
 * Active only when git-utility.persistence.provider=mongo;
 * the H2 implementation lives in repository.h2.
 */
@Repository
@OnMongo
@RequiredArgsConstructor
public class MongoBulkSubmissionStore implements BulkSubmissionRepository {

    private final BulkSubmissionMongoRepository repo;

    @Override
    public BulkSubmission save(BulkSubmission entity) {
return repo.save(entity);
    }

    @Override
    public List<BulkSubmission> saveAll(Iterable<BulkSubmission> entities) {
return repo.saveAll(entities);
    }

    @Override
    public Optional<BulkSubmission> findById(String id) {
return repo.findById(id);
    }

    @Override
    public boolean existsById(String id) {
return repo.existsById(id);
    }

    @Override
    public List<BulkSubmission> findAll() {
return repo.findAll();
    }

    @Override
    public long count() {
return repo.count();
    }

    @Override
    public void delete(BulkSubmission entity) {
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
