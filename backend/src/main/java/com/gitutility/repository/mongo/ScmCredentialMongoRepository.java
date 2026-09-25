package com.gitutility.repository.mongo;

import com.gitutility.model.entity.ScmCredential;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation backing the ScmCredentialRepository store facade
 * (active only when git-utility.persistence.provider=mongo).
 */
public interface ScmCredentialMongoRepository extends MongoRepository<ScmCredential, String> {

    List<ScmCredential> findByProviderOrderByIdAsc(String provider);

    List<ScmCredential> findByEnabledTrueOrderByIdAsc();

    List<ScmCredential> findByProviderAndEnabledTrueOrderByIdAsc(String provider);

    List<ScmCredential> findByAppIdAndProvider(String appId, String provider);

    Optional<ScmCredential> findByInstallationIdAndProvider(String installationId, String provider);

    long countByProvider(String provider);
}
