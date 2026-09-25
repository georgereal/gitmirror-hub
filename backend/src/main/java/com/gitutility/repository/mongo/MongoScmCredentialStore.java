package com.gitutility.repository.mongo;

import com.gitutility.persistence.PersistenceConditions.OnMongo;
import com.gitutility.repository.ScmCredentialRepository;
import com.gitutility.model.entity.ScmCredential;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MongoDB implementation of the ScmCredentialRepository store facade.
 * Active only when git-utility.persistence.provider=mongo;
 * the H2 implementation lives in repository.h2.
 */
@Repository
@OnMongo
@RequiredArgsConstructor
public class MongoScmCredentialStore implements ScmCredentialRepository {

    private final ScmCredentialMongoRepository repo;

    @Override
    public List<ScmCredential> findByProviderOrderByIdAsc(String provider) {
return repo.findByProviderOrderByIdAsc(provider);
    }

    @Override
    public List<ScmCredential> findByEnabledTrueOrderByIdAsc() {
return repo.findByEnabledTrueOrderByIdAsc();
    }

    @Override
    public List<ScmCredential> findByProviderAndEnabledTrueOrderByIdAsc(String provider) {
return repo.findByProviderAndEnabledTrueOrderByIdAsc(provider);
    }

    @Override
    public List<ScmCredential> findByAppIdAndProvider(String appId, String provider) {
return repo.findByAppIdAndProvider(appId, provider);
    }

    @Override
    public Optional<ScmCredential> findByInstallationIdAndProvider(String installationId, String provider) {
return repo.findByInstallationIdAndProvider(installationId, provider);
    }

    @Override
    public long countByProvider(String provider) {
return repo.countByProvider(provider);
    }

    @Override
    public ScmCredential save(ScmCredential entity) {
return repo.save(entity);
    }

    @Override
    public List<ScmCredential> saveAll(Iterable<ScmCredential> entities) {
return repo.saveAll(entities);
    }

    @Override
    public Optional<ScmCredential> findById(String id) {
return repo.findById(id);
    }

    @Override
    public boolean existsById(String id) {
return repo.existsById(id);
    }

    @Override
    public List<ScmCredential> findAll() {
return repo.findAll();
    }

    @Override
    public long count() {
return repo.count();
    }

    @Override
    public void delete(ScmCredential entity) {
repo.delete(entity);
    }

    @Override
    public void deleteById(String id) {
repo.deleteById(id);
    }

    @Override
    public void deleteAll() {
repo.deleteAll();
    }

}
