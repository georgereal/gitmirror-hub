package com.gitutility.repository.h2;

import com.gitutility.persistence.PersistenceConditions.OnH2;
import com.gitutility.repository.ScmCredentialRepository;
import com.gitutility.model.entity.ScmCredential;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * H2 (Spring Data JPA) implementation of the ScmCredentialRepository store facade.
 * Active only when git-utility.persistence.provider=h2 (default);
 * the MongoDB implementation lives in repository.mongo.
 */
@Repository
@OnH2
@RequiredArgsConstructor
public class H2ScmCredentialStore implements ScmCredentialRepository {

    private final ScmCredentialJpaRepository jpa;

    @Override
    public List<ScmCredential> findByProviderOrderByIdAsc(String provider) {
        return jpa.findByProviderOrderByIdAsc(provider);
    }

    @Override
    public List<ScmCredential> findByEnabledTrueOrderByIdAsc() {
        return jpa.findByEnabledTrueOrderByIdAsc();
    }

    @Override
    public List<ScmCredential> findByProviderAndEnabledTrueOrderByIdAsc(String provider) {
        return jpa.findByProviderAndEnabledTrueOrderByIdAsc(provider);
    }

    @Override
    public List<ScmCredential> findByAppIdAndProvider(String appId, String provider) {
        return jpa.findByAppIdAndProvider(appId, provider);
    }

    @Override
    public Optional<ScmCredential> findByInstallationIdAndProvider(String installationId, String provider) {
        return jpa.findByInstallationIdAndProvider(installationId, provider);
    }

    @Override
    public long countByProvider(String provider) {
        return jpa.countByProvider(provider);
    }

    @Override
    public ScmCredential save(ScmCredential entity) {
        return jpa.save(entity);
    }

    @Override
    public List<ScmCredential> saveAll(Iterable<ScmCredential> entities) {
        return jpa.saveAll(entities);
    }

    @Override
    public Optional<ScmCredential> findById(String id) {
        return jpa.findById(id);
    }

    @Override
    public boolean existsById(String id) {
        return jpa.existsById(id);
    }

    @Override
    public List<ScmCredential> findAll() {
        return jpa.findAll();
    }

    @Override
    public long count() {
        return jpa.count();
    }

    @Override
    public void delete(ScmCredential entity) {
        jpa.delete(entity);
    }

    @Override
    public void deleteById(String id) {
        jpa.deleteById(id);
    }

    @Override
    public void deleteAll() {
        jpa.deleteAll();
    }

}
