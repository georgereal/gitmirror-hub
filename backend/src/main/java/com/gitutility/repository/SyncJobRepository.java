package com.gitutility.repository;

import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.model.enums.TriggerType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Store facade for the sync job ledger.
 * Exactly one provider-backed implementation is active: H2 ({@code repository.h2}) or MongoDB ({@code repository.mongo}).
 * {@link #search} must keep its nullable-filter + lane semantics on every store.
 */
public interface SyncJobRepository {

    List<SyncJob> findTop20ByOrderByCreatedAtDesc();

    Page<SyncJob> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<SyncJob> findByMappingIdOrderByCreatedAtDesc(String mappingId, Pageable pageable);

    Page<SyncJob> findByStatusOrderByCreatedAtDesc(SyncStatus status, Pageable pageable);

    List<SyncJob> findByStatus(SyncStatus status);

    List<SyncJob> findByStatusAndMappingId(SyncStatus status, String mappingId);

    Optional<SyncJob> findByQueueMessageId(String queueMessageId);

    Page<SyncJob> search(SyncStatus status, String mappingId, TriggerType triggerType, String lane, Pageable pageable);

    long countByStatus(SyncStatus status);

    long countJobsSince(Instant since);

    long countSuccessJobsSince(Instant since);

    List<SyncJob> findForUsageWindow(Instant since);

    SyncJob save(SyncJob entity);

    List<SyncJob> saveAll(Iterable<SyncJob> entities);

    Optional<SyncJob> findById(String id);

    boolean existsById(String id);

    List<SyncJob> findAll();

    long count();

    void delete(SyncJob entity);

    void deleteById(String id);

    void deleteAll();
}
