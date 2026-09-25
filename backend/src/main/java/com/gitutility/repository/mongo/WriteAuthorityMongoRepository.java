package com.gitutility.repository.mongo;

import com.gitutility.model.entity.WriteAuthorityRecord;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface WriteAuthorityMongoRepository extends MongoRepository<WriteAuthorityRecord, String> {

    Optional<WriteAuthorityRecord> findByRecordKey(String recordKey);

    List<WriteAuthorityRecord> findByMappingId(String mappingId);
}
