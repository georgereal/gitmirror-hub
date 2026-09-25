package com.gitutility.repository;

import com.gitutility.model.entity.SyncAuditLog;

import java.util.List;
import java.util.Optional;

/**
 * Store facade for per-job audit log lines.
 * Exactly one provider-backed implementation is active: H2 ({@code repository.h2}) or MongoDB ({@code repository.mongo}).
 */
public interface SyncAuditLogRepository {

    List<SyncAuditLog> findByJobIdOrderByTimestampAsc(String jobId);

    void deleteByJobId(String jobId);

    SyncAuditLog save(SyncAuditLog entity);

    List<SyncAuditLog> saveAll(Iterable<SyncAuditLog> entities);

    Optional<SyncAuditLog> findById(String id);

    boolean existsById(String id);

    List<SyncAuditLog> findAll();

    long count();

    void delete(SyncAuditLog entity);

    void deleteById(String id);

    void deleteAll();
}
