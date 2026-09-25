package com.gitutility.repository;

import com.gitutility.model.entity.ScmCredential;

import java.util.List;
import java.util.Optional;

/**
 * Store facade for SCM credentials (GitHub App installations / PATs).
 * Exactly one provider-backed implementation is active: H2 ({@code repository.h2}) or MongoDB ({@code repository.mongo}).
 */
public interface ScmCredentialRepository {

    List<ScmCredential> findByProviderOrderByIdAsc(String provider);

    List<ScmCredential> findByEnabledTrueOrderByIdAsc();

    List<ScmCredential> findByProviderAndEnabledTrueOrderByIdAsc(String provider);

    List<ScmCredential> findByAppIdAndProvider(String appId, String provider);

    Optional<ScmCredential> findByInstallationIdAndProvider(String installationId, String provider);

    long countByProvider(String provider);

    ScmCredential save(ScmCredential entity);

    List<ScmCredential> saveAll(Iterable<ScmCredential> entities);

    Optional<ScmCredential> findById(String id);

    boolean existsById(String id);

    List<ScmCredential> findAll();

    long count();

    void delete(ScmCredential entity);

    void deleteById(String id);

    void deleteAll();
}
