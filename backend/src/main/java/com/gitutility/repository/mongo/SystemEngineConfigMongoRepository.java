package com.gitutility.repository.mongo;

import com.gitutility.model.entity.SystemEngineConfig;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * MongoDB implementation backing the SystemEngineConfigRepository store facade
 * (active only when git-utility.persistence.provider=mongo).
 */
public interface SystemEngineConfigMongoRepository extends MongoRepository<SystemEngineConfig, String> {

    Optional<SystemEngineConfig> findTopByOrderByIdAsc();
}
