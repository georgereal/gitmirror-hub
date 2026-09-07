package com.gitutility.repository;

import com.gitutility.model.entity.ScmCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ScmCredentialRepository extends JpaRepository<ScmCredential, Long> {

    List<ScmCredential> findByProviderOrderByIdAsc(String provider);

    List<ScmCredential> findByEnabledTrueOrderByIdAsc();

    List<ScmCredential> findByProviderAndEnabledTrueOrderByIdAsc(String provider);

    List<ScmCredential> findByAppIdAndProvider(String appId, String provider);

    Optional<ScmCredential> findByInstallationIdAndProvider(String installationId, String provider);

    long countByProvider(String provider);
}
