package com.gitutility.repository.h2;

import com.gitutility.model.entity.SyncAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SyncAuditLogJpaRepository extends JpaRepository<SyncAuditLog, String> {

    List<SyncAuditLog> findByJobIdOrderByTimestampAsc(String jobId);

    void deleteByJobId(String jobId);
}
