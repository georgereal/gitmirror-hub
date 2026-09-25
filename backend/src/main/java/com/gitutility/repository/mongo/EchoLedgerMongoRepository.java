package com.gitutility.repository.mongo;

import com.gitutility.model.entity.EchoLedgerEntry;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EchoLedgerMongoRepository extends MongoRepository<EchoLedgerEntry, String> {
}
