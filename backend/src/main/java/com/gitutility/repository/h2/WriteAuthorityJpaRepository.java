package com.gitutility.repository.h2;

import com.gitutility.model.entity.WriteAuthorityRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WriteAuthorityJpaRepository extends JpaRepository<WriteAuthorityRecord, String> {

    Optional<WriteAuthorityRecord> findByRecordKey(String recordKey);

    List<WriteAuthorityRecord> findByMappingId(String mappingId);
}
