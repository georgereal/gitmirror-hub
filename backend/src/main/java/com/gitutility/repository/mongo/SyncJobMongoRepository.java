package com.gitutility.repository.mongo;

import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation backing the SyncJobRepository store facade
 * (active only when git-utility.persistence.provider=mongo).
 * The dynamic lane/filter search lives in the store via MongoTemplate.
 */
public interface SyncJobMongoRepository extends MongoRepository<SyncJob, String> {

    List<SyncJob> findTop20ByOrderByCreatedAtDesc();

    Page<SyncJob> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<SyncJob> findByMappingIdOrderByCreatedAtDesc(String mappingId, Pageable pageable);

    Page<SyncJob> findByStatusOrderByCreatedAtDesc(SyncStatus status, Pageable pageable);

    List<SyncJob> findByStatus(SyncStatus status);

    List<SyncJob> findByStatusAndMappingId(SyncStatus status, String mappingId);

    Optional<SyncJob> findByQueueMessageId(String queueMessageId);

    long countByStatus(SyncStatus status);

    long countByCreatedAtGreaterThanEqual(Instant since);

    long countByStatusAndCreatedAtGreaterThanEqual(SyncStatus status, Instant since);

    List<SyncJob> findByStartedAtGreaterThanEqualOrStatusInOrderByStartedAtDesc(Instant startedAt, Collection<SyncStatus> status);
}
