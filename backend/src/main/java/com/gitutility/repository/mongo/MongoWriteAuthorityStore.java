package com.gitutility.repository.mongo;

import com.gitutility.model.entity.WriteAuthorityRecord;
import com.gitutility.persistence.PersistenceConditions.OnMongo;
import com.gitutility.repository.WriteAuthorityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@OnMongo
@RequiredArgsConstructor
public class MongoWriteAuthorityStore implements WriteAuthorityRepository {

    private final WriteAuthorityMongoRepository repo;

    @Override
    public WriteAuthorityRecord save(WriteAuthorityRecord row) {
        return repo.save(row);
    }

    @Override
    public Optional<WriteAuthorityRecord> findByRecordKey(String recordKey) {
        return repo.findByRecordKey(recordKey);
    }

    @Override
    public List<WriteAuthorityRecord> findByMappingId(String mappingId) {
        return repo.findByMappingId(mappingId);
    }

    @Override
    public List<WriteAuthorityRecord> findAll() {
        return repo.findAll();
    }
}
