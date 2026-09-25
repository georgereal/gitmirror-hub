package com.gitutility.repository.mongo;

import com.gitutility.model.entity.PairLease;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB implementation backing the PairLeaseRepository store facade
 * (active only when git-utility.persistence.provider=mongo).
 * The atomic lease takeover / release operations live in the store via MongoTemplate
 * (unique-key insert race + filtered updateOne, stricter than the JPA bulk semantics).
 */
public interface PairLeaseMongoRepository extends MongoRepository<PairLease, String> {
}
