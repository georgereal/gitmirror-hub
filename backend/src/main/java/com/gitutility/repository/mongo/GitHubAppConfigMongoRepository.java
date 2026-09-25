package com.gitutility.repository.mongo;

import com.gitutility.model.entity.GitHubAppConfig;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * MongoDB implementation backing the GitHubAppConfigRepository store facade
 * (active only when git-utility.persistence.provider=mongo).
 */
public interface GitHubAppConfigMongoRepository extends MongoRepository<GitHubAppConfig, String> {

    Optional<GitHubAppConfig> findFirstByOrderByIdAsc();
}
