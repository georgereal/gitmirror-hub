package com.gitutility.repository.mongo;

import com.gitutility.model.entity.BulkSubmission;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB implementation backing the BulkSubmissionRepository store facade
 * (active only when git-utility.persistence.provider=mongo).
 */
public interface BulkSubmissionMongoRepository extends MongoRepository<BulkSubmission, String> {
}
