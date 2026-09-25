package com.gitutility.persistence.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gitutility.repository.FeatureFlagsConfigRepository;
import com.gitutility.repository.GitHubAppConfigRepository;
import com.gitutility.repository.InstanceHeartbeatRepository;
import com.gitutility.repository.PairLeaseRepository;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncAuditLogRepository;
import com.gitutility.repository.SyncConflictRepository;
import com.gitutility.repository.SyncJobRepository;
import com.gitutility.repository.SystemEngineConfigRepository;
import com.gitutility.repository.UnmappedWebhookEventRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the store contract suites against the H2 provider (in-memory H2, full context,
 * GIT_PERSISTENCE_PROVIDER=h2 — the default).
 */
@Transactional
@SpringBootTest(properties = {
        "git-utility.persistence.provider=h2",
        "git-utility.messaging.provider=none",
        "git-utility.security.encryption-key=contract-test-encryption-key-0123456789",
        "git-utility.workspace-dir=${java.io.tmpdir}/git-utility-contract-workspace",
        "spring.datasource.url=jdbc:h2:mem:storecontract;DB_CLOSE_DELAY=-1",
        "git-utility.queue.pause-consumers-on-startup=true",
        "git-utility.cluster.heartbeat-interval-ms=3600000"
})
class H2StoreContractTest {

    @Autowired RepoMappingRepository repoMappingRepository;
    @Autowired SyncJobRepository syncJobRepository;
    @Autowired PairLeaseRepository pairLeaseRepository;
    @Autowired InstanceHeartbeatRepository instanceHeartbeatRepository;
    @Autowired UnmappedWebhookEventRepository unmappedWebhookEventRepository;
    @Autowired SyncAuditLogRepository syncAuditLogRepository;
    @Autowired FeatureFlagsConfigRepository featureFlagsConfigRepository;
    @Autowired GitHubAppConfigRepository gitHubAppConfigRepository;
    @Autowired SystemEngineConfigRepository systemEngineConfigRepository;
    @Autowired SyncConflictRepository syncConflictRepository;

    @Nested
    class RepoMappings extends RepoMappingStoreContract {
        protected RepoMappingRepository repo() {
            return repoMappingRepository;
        }
    }

    @Nested
    class SyncJobs extends SyncJobStoreContract {
        protected SyncJobRepository repo() {
            return syncJobRepository;
        }
    }

    @Nested
    class PairLeases extends PairLeaseStoreContract {
        protected PairLeaseRepository repo() {
            return pairLeaseRepository;
        }
    }

    @Nested
    class AuxStores extends AuxStoreContract {
        protected InstanceHeartbeatRepository heartbeats() {
            return instanceHeartbeatRepository;
        }

        protected UnmappedWebhookEventRepository unmapped() {
            return unmappedWebhookEventRepository;
        }

        protected SyncAuditLogRepository auditLogs() {
            return syncAuditLogRepository;
        }
    }

    @Nested
    class ConfigStores extends ConfigStoreContract {
        protected FeatureFlagsConfigRepository featureFlags() {
            return featureFlagsConfigRepository;
        }

        protected GitHubAppConfigRepository providerConfig() {
            return gitHubAppConfigRepository;
        }

        protected SystemEngineConfigRepository systemEngine() {
            return systemEngineConfigRepository;
        }
    }

    @Test
    void conflictDerivedQueries() {
        // quick parity probe for the remaining conflict facade surface
        syncConflictRepository.deleteAll();
        assertEquals(0, syncConflictRepository.findByMappingIdOrderByCreatedAtDesc("m-c").size());
    }
}
