package com.gitutility.repository;

import com.gitutility.model.entity.BulkSubmission;

import java.util.List;
import java.util.Optional;

/**
 * Store facade for bulk-migration submissions (Phase 2B of future-work/multi-store-persistence-h2-mongo.md).
 * Exactly one provider-backed implementation is active: H2 ({@code repository.h2}) or MongoDB ({@code repository.mongo}).
 */
public interface BulkSubmissionRepository {

    BulkSubmission save(BulkSubmission entity);

    List<BulkSubmission> saveAll(Iterable<BulkSubmission> entities);

    Optional<BulkSubmission> findById(String id);

    boolean existsById(String id);

    List<BulkSubmission> findAll();

    long count();

    void delete(BulkSubmission entity);

    void deleteById(String id);

    void deleteAll();
}
