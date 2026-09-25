package com.gitutility.repository;

import com.gitutility.model.entity.SyncConflict;
import com.gitutility.model.enums.ConflictStatus;

import java.util.List;
import java.util.Optional;

/**
 * Store facade for git-ref / tag / PR-metadata conflicts.
 * Exactly one provider-backed implementation is active: H2 ({@code repository.h2}) or MongoDB ({@code repository.mongo}).
 */
public interface SyncConflictRepository {

    List<SyncConflict> findByMappingIdOrderByCreatedAtDesc(String mappingId);

    List<SyncConflict> findByMappingIdAndStatusOrderByCreatedAtDesc(String mappingId, ConflictStatus status);

    Optional<SyncConflict> findByMappingIdAndRefNameAndDestShaAndStatus(String mappingId, String refName, String destSha, ConflictStatus status);

    SyncConflict save(SyncConflict entity);

    List<SyncConflict> saveAll(Iterable<SyncConflict> entities);

    Optional<SyncConflict> findById(String id);

    boolean existsById(String id);

    List<SyncConflict> findAll();

    long count();

    void delete(SyncConflict entity);

    void deleteById(String id);

    void deleteAll();
}
