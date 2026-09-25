package com.gitutility.repository.h2;

import com.gitutility.model.entity.SyncConflict;
import com.gitutility.model.enums.ConflictStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SyncConflictJpaRepository extends JpaRepository<SyncConflict, String> {

    List<SyncConflict> findByMappingIdOrderByCreatedAtDesc(String mappingId);

    List<SyncConflict> findByMappingIdAndStatusOrderByCreatedAtDesc(String mappingId, ConflictStatus status);

    Optional<SyncConflict> findByMappingIdAndRefNameAndDestShaAndStatus(
            String mappingId, String refName, String destSha, ConflictStatus status);
}
