package com.gitutility.repository;

import com.gitutility.model.entity.RepoMapping;

import java.util.List;
import java.util.Optional;

/**
 * Store facade for repository pairs.
 * Exactly one provider-backed implementation is active: H2 ({@code repository.h2}) or MongoDB ({@code repository.mongo}).
 */
public interface RepoMappingRepository {

    Optional<RepoMapping> findByName(String name);

    List<RepoMapping> findActiveMatchingRepo(String url, String repoPath);

    long countBySourceCredentialIdOrTargetCredentialId(String sourceCredentialId, String targetCredentialId);

    List<RepoMapping> findByBulkSubmissionId(String bulkSubmissionId);

    RepoMapping save(RepoMapping entity);

    List<RepoMapping> saveAll(Iterable<RepoMapping> entities);

    Optional<RepoMapping> findById(String id);

    boolean existsById(String id);

    List<RepoMapping> findAll();

    long count();

    void delete(RepoMapping entity);

    void deleteById(String id);

    void deleteAll();
}
