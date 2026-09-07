package com.gitutility.repository;

import com.gitutility.model.entity.SyncAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SyncAuditLogRepository extends JpaRepository<SyncAuditLog, Long> {

    List<SyncAuditLog> findByJobIdOrderByTimestampAsc(Long jobId);

    void deleteByJobId(Long jobId);
}
