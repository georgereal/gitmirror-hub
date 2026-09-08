package com.gitutility.repository;

import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.model.enums.TriggerType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SyncJobRepository extends JpaRepository<SyncJob, Long> {

    List<SyncJob> findTop20ByOrderByCreatedAtDesc();

    Page<SyncJob> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<SyncJob> findByMappingIdOrderByCreatedAtDesc(Long mappingId, Pageable pageable);

    Page<SyncJob> findByStatusOrderByCreatedAtDesc(SyncStatus status, Pageable pageable);

    List<SyncJob> findByStatus(SyncStatus status);

    List<SyncJob> findByStatusAndMappingId(SyncStatus status, Long mappingId);

    Optional<SyncJob> findByQueueMessageId(String queueMessageId);

    @Query("""
            SELECT j FROM SyncJob j
            WHERE (:status IS NULL OR j.status = :status)
              AND (:mappingId IS NULL OR j.mappingId = :mappingId)
              AND (:triggerType IS NULL OR j.triggerType = :triggerType)
              AND (
                    :lane IS NULL
                    OR (:lane = 'FULL' AND (j.ref IS NULL OR TRIM(j.ref) = '' OR j.branch = '*'))
                    OR (:lane = 'INCREMENTAL' AND j.ref IS NOT NULL AND TRIM(j.ref) <> '' AND (j.branch IS NULL OR j.branch <> '*'))
                  )
            ORDER BY j.createdAt DESC
            """)
    Page<SyncJob> search(
            @Param("status") SyncStatus status,
            @Param("mappingId") Long mappingId,
            @Param("triggerType") TriggerType triggerType,
            @Param("lane") String lane,
            Pageable pageable);

    @Query("SELECT COUNT(j) FROM SyncJob j WHERE j.status = :status")
    long countByStatus(@Param("status") SyncStatus status);

    @Query("SELECT COUNT(j) FROM SyncJob j WHERE j.createdAt >= :since")
    long countJobsSince(@Param("since") java.time.Instant since);

    @Query("SELECT COUNT(j) FROM SyncJob j WHERE j.status = 'SUCCESS' AND j.createdAt >= :since")
    long countSuccessJobsSince(@Param("since") java.time.Instant since);

    @Query("""
            SELECT j FROM SyncJob j
            WHERE (j.startedAt IS NOT NULL AND j.startedAt >= :since)
               OR j.status IN ('IN_PROGRESS', 'QUEUED', 'PAUSED')
            ORDER BY j.startedAt DESC
            """)
    List<SyncJob> findForUsageWindow(@Param("since") java.time.Instant since);
}
