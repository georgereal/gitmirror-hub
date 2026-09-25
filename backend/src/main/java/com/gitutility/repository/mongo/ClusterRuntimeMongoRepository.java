package com.gitutility.repository.mongo;

import com.gitutility.model.entity.ClusterRuntime;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB implementation backing the ClusterRuntimeRepository store facade
 * (active only when git-utility.persistence.provider=mongo).
 */
public interface ClusterRuntimeMongoRepository extends MongoRepository<ClusterRuntime, String> {
}
