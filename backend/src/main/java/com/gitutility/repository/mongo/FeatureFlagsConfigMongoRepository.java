package com.gitutility.repository.mongo;

import com.gitutility.model.entity.FeatureFlagsConfig;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * MongoDB implementation backing the FeatureFlagsConfigRepository store facade
 * (active only when git-utility.persistence.provider=mongo).
 */
public interface FeatureFlagsConfigMongoRepository extends MongoRepository<FeatureFlagsConfig, String> {

    Optional<FeatureFlagsConfig> findTopByOrderByIdAsc();
}
