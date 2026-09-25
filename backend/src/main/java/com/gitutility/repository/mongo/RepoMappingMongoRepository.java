package com.gitutility.repository.mongo;

import com.gitutility.model.entity.RepoMapping;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation backing the RepoMappingRepository store facade
 * (active only when git-utility.persistence.provider=mongo).
 * findActiveMatchingRepo (URL contains / regex matching) lives in the store via MongoTemplate.
 */
public interface RepoMappingMongoRepository extends MongoRepository<RepoMapping, String> {

    Optional<RepoMapping> findByName(String name);

    long countBySourceCredentialIdOrTargetCredentialId(String sourceCredentialId, String targetCredentialId);

    List<RepoMapping> findByBulkSubmissionId(String bulkSubmissionId);
}
