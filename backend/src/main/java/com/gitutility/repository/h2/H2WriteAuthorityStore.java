package com.gitutility.repository.h2;

import com.gitutility.model.entity.WriteAuthorityRecord;
import com.gitutility.persistence.PersistenceConditions.OnH2;
import com.gitutility.repository.WriteAuthorityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@OnH2
@RequiredArgsConstructor
public class H2WriteAuthorityStore implements WriteAuthorityRepository {

    private final WriteAuthorityJpaRepository jpa;

    @Override
    public WriteAuthorityRecord save(WriteAuthorityRecord row) {
        return jpa.save(row);
    }

    @Override
    public Optional<WriteAuthorityRecord> findByRecordKey(String recordKey) {
        return jpa.findByRecordKey(recordKey);
    }

    @Override
    public List<WriteAuthorityRecord> findByMappingId(String mappingId) {
        return jpa.findByMappingId(mappingId);
    }

    @Override
    public List<WriteAuthorityRecord> findAll() {
        return jpa.findAll();
    }
}
