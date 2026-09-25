package com.gitutility.repository.h2;

import com.gitutility.model.entity.ScmCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ScmCredentialJpaRepository extends JpaRepository<ScmCredential, String> {

    List<ScmCredential> findByProviderOrderByIdAsc(String provider);

    List<ScmCredential> findByEnabledTrueOrderByIdAsc();

    List<ScmCredential> findByProviderAndEnabledTrueOrderByIdAsc(String provider);

    List<ScmCredential> findByAppIdAndProvider(String appId, String provider);

    Optional<ScmCredential> findByInstallationIdAndProvider(String installationId, String provider);

    long countByProvider(String provider);
}
