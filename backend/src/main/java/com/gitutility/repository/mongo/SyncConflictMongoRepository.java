package com.gitutility.repository.mongo;

import com.gitutility.model.entity.SyncConflict;
import com.gitutility.model.enums.ConflictStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation backing the SyncConflictRepository store facade
 * (active only when git-utility.persistence.provider=mongo).
 */
public interface SyncConflictMongoRepository extends MongoRepository<SyncConflict, String> {

    List<SyncConflict> findByMappingIdOrderByCreatedAtDesc(String mappingId);

    List<SyncConflict> findByMappingIdAndStatusOrderByCreatedAtDesc(String mappingId, ConflictStatus status);

    Optional<SyncConflict> findByMappingIdAndRefNameAndDestShaAndStatus(
            String mappingId, String refName, String destSha, ConflictStatus status);
}
