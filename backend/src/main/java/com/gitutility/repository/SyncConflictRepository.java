package com.gitutility.repository;

import com.gitutility.model.entity.SyncConflict;
import com.gitutility.model.enums.ConflictStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SyncConflictRepository extends JpaRepository<SyncConflict, Long> {

    List<SyncConflict> findByMappingIdOrderByCreatedAtDesc(Long mappingId);

    List<SyncConflict> findByMappingIdAndStatusOrderByCreatedAtDesc(Long mappingId, ConflictStatus status);

    Optional<SyncConflict> findByMappingIdAndRefNameAndDestShaAndStatus(
            Long mappingId, String refName, String destSha, ConflictStatus status);
}
