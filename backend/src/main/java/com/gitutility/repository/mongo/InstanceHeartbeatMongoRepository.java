package com.gitutility.repository.mongo;

import com.gitutility.model.entity.InstanceHeartbeat;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB implementation backing the InstanceHeartbeatRepository store facade
 * (active only when git-utility.persistence.provider=mongo).
 * Retention cleanup (deleteOlderThan) lives in the store via MongoTemplate.
 */
public interface InstanceHeartbeatMongoRepository extends MongoRepository<InstanceHeartbeat, String> {
}
