package com.gitutility.repository.h2;

import com.gitutility.persistence.PersistenceConditions.OnH2;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.model.entity.RepoMapping;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * H2 (Spring Data JPA) implementation of the RepoMappingRepository store facade.
 * Active only when git-utility.persistence.provider=h2 (default);
 * the MongoDB implementation lives in repository.mongo.
 */
@Repository
@OnH2
@RequiredArgsConstructor
public class H2RepoMappingStore implements RepoMappingRepository {

    private final RepoMappingJpaRepository jpa;

    @Override
    public Optional<RepoMapping> findByName(String name) {
        return jpa.findByName(name);
    }

    @Override
    public List<RepoMapping> findActiveMatchingRepo(String url, String repoPath) {
        return jpa.findActiveMatchingRepo(url, repoPath);
    }

    @Override
    public long countBySourceCredentialIdOrTargetCredentialId(String sourceCredentialId, String targetCredentialId) {
        return jpa.countBySourceCredentialIdOrTargetCredentialId(sourceCredentialId, targetCredentialId);
    }

    @Override
    public List<RepoMapping> findByBulkSubmissionId(String bulkSubmissionId) {
        return jpa.findByBulkSubmissionId(bulkSubmissionId);
    }

    @Override
    public RepoMapping save(RepoMapping entity) {
        return jpa.save(entity);
    }

    @Override
    public List<RepoMapping> saveAll(Iterable<RepoMapping> entities) {
        return jpa.saveAll(entities);
    }

    @Override
    public Optional<RepoMapping> findById(String id) {
        return jpa.findById(id);
    }

    @Override
    public boolean existsById(String id) {
        return jpa.existsById(id);
    }

    @Override
    public List<RepoMapping> findAll() {
        return jpa.findAll();
    }

    @Override
    public long count() {
        return jpa.count();
    }

    @Override
    public void delete(RepoMapping entity) {
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
