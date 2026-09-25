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
import org.springframework.dao.DuplicateKeyException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the store contract suites against the MongoDB provider
 * (GIT_PERSISTENCE_PROVIDER=mongo) using a real MongoDB supplied via
 * {@code MONGO_CONTRACT_URI} — no Docker / Testcontainers. Skipped automatically
 * when that variable is unset, keeping plain {@code mvn test} green everywhere.
 */
@EnabledIf(value = "com.gitutility.persistence.contract.StoreContractEnvironment#mongoContractAvailable",
        disabledReason = "MONGO_CONTRACT_URI not set — mongo contract fixtures need a real MongoDB")
@Transactional
@SpringBootTest(properties = {
        "git-utility.persistence.provider=mongo",
        "git-utility.messaging.provider=none",
        "git-utility.security.encryption-key=contract-test-encryption-key-0123456789",
        "git-utility.workspace-dir=${java.io.tmpdir}/git-utility-contract-workspace",
        "git-utility.queue.pause-consumers-on-startup=true",
        "git-utility.cluster.heartbeat-interval-ms=3600000"
})
class MongoStoreContractTest {

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
    @Autowired MongoTemplate mongoTemplate;


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
        syncConflictRepository.deleteAll();
        assertEquals(0, syncConflictRepository.findByMappingIdOrderByCreatedAtDesc("m-c").size());
    }

    @Test
    void rawDuplicateInsertIsRejected() {
        // Proves the unique _id key that PairLeaseService's concurrent-acquire race relies on
        // (the store-level save() replaces, but a raw concurrent insert must lose).
        com.gitutility.model.entity.PairLease lease = com.gitutility.model.entity.PairLease.builder()
                .mappingId("pair-race")
                .ownerInstance("pod-a")
                .jobId("job-1")
                .expiresAt(Instant.now().plusSeconds(90))
                .updatedAt(Instant.now())
                .build();
        mongoTemplate.insert(lease);
        assertThrows(DuplicateKeyException.class, () -> mongoTemplate.insert(lease));
    }
}
