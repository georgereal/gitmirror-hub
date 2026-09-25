package com.gitutility.repository;

import com.gitutility.model.entity.WriteAuthorityRecord;

import java.util.List;
import java.util.Optional;

/**
 * Store facade for write-authority placements.
 * Exactly one provider-backed implementation is active: H2 or MongoDB.
 */
public interface WriteAuthorityRepository {

    WriteAuthorityRecord save(WriteAuthorityRecord row);

    Optional<WriteAuthorityRecord> findByRecordKey(String recordKey);

    List<WriteAuthorityRecord> findByMappingId(String mappingId);

    List<WriteAuthorityRecord> findAll();
}
