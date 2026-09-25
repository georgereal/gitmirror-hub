package com.gitutility.repository.mongo;

import com.gitutility.model.entity.SyncAuditLog;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * MongoDB implementation backing the SyncAuditLogRepository store facade
 * (active only when git-utility.persistence.provider=mongo).
 */
public interface SyncAuditLogMongoRepository extends MongoRepository<SyncAuditLog, String> {

    List<SyncAuditLog> findByJobIdOrderByTimestampAsc(String jobId);

    void deleteByJobId(String jobId);
}
