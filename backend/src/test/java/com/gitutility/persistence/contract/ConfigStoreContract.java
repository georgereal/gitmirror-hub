package com.gitutility.persistence.contract;

import com.gitutility.model.entity.FeatureFlagsConfig;
import com.gitutility.model.entity.GitHubAppConfig;
import com.gitutility.model.entity.SystemEngineConfig;
import com.gitutility.repository.FeatureFlagsConfigRepository;
import com.gitutility.repository.GitHubAppConfigRepository;
import com.gitutility.repository.SystemEngineConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Store contract for the singleton configuration facades (feature flags, provider
 * configuration, system engine): first-row semantics must hold on both stores.
 */
@Transactional
public abstract class ConfigStoreContract {

    protected abstract FeatureFlagsConfigRepository featureFlags();

    protected abstract GitHubAppConfigRepository providerConfig();

    protected abstract SystemEngineConfigRepository systemEngine();

    @BeforeEach
    void clean() {
        featureFlags().deleteAll();
        providerConfig().deleteAll();
        systemEngine().deleteAll();
    }

    @Test
    void featureFlagsFirstRowSemantics() {
        FeatureFlagsConfig saved = featureFlags().save(FeatureFlagsConfig.builder().build());
        assertNotNull(saved.getId());
        assertTrue(featureFlags().findTopByOrderByIdAsc().isPresent());
    }

    @Test
    void providerConfigFirstRowSemantics() {
        GitHubAppConfig saved = providerConfig().save(GitHubAppConfig.builder().build());
        assertNotNull(saved.getId());
        assertTrue(providerConfig().findFirstByOrderByIdAsc().isPresent());
    }

    @Test
    void systemEngineFirstRowSemantics() {
        SystemEngineConfig saved = systemEngine().save(SystemEngineConfig.builder().build());
        assertNotNull(saved.getId());
        assertTrue(systemEngine().findTopByOrderByIdAsc().isPresent());
    }
}
