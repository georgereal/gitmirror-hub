package com.gitutility.repository.mongo;

import com.gitutility.model.entity.RefOrigin;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * MongoDB implementation backing the RefOriginRepository store facade
 * (active only when git-utility.persistence.provider=mongo).
 */
public interface RefOriginMongoRepository extends MongoRepository<RefOrigin, String> {

    Optional<RefOrigin> findByMappingIdAndRefName(String mappingId, String refName);
}
