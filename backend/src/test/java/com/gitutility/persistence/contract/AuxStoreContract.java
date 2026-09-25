package com.gitutility.persistence.contract;

import com.gitutility.model.entity.InstanceHeartbeat;
import com.gitutility.model.entity.SyncAuditLog;
import com.gitutility.model.entity.UnmappedWebhookEvent;
import com.gitutility.model.enums.LogLevel;
import com.gitutility.repository.InstanceHeartbeatRepository;
import com.gitutility.repository.SyncAuditLogRepository;
import com.gitutility.repository.UnmappedWebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Store contract for the auxiliary facades — fleet heartbeats, discarded webhook
 * records, and audit log lines, including the retention-delete ports
 * (Mongo TTL indexes coexist with, but never replace, the explicit deletes).
 */
@Transactional
public abstract class AuxStoreContract {

    protected abstract InstanceHeartbeatRepository heartbeats();

    protected abstract UnmappedWebhookEventRepository unmapped();

    protected abstract SyncAuditLogRepository auditLogs();

    @BeforeEach
    void clean() {
        heartbeats().deleteAll();
        unmapped().deleteAll();
        auditLogs().deleteAll();
    }

    @Test
    void heartbeatDeleteOlderThanPrunesOnlyExpiredRows() {
        Instant now = Instant.now();
        heartbeats().save(InstanceHeartbeat.builder()
                .instanceId("contract-hb-old").updatedAt(now.minusSeconds(7200)).payloadJson("{}").build());
        heartbeats().save(InstanceHeartbeat.builder()
                .instanceId("contract-hb-new").updatedAt(now).payloadJson("{}").build());

        int deleted = heartbeats().deleteOlderThan(now.minusSeconds(3600));
        assertTrue(deleted >= 1);
        assertTrue(heartbeats().findById("contract-hb-old").isEmpty());
        assertTrue(heartbeats().findById("contract-hb-new").isPresent());
    }

    @Test
    void unmappedDeleteOlderThanAndNewestFirst() {
        Instant now = Instant.now();
        unmapped().save(UnmappedWebhookEvent.builder()
                .provider("github").repoFullName("acme/old").eventType("push")
                .discardReason("UNMAPPED_REPOSITORY").receivedAt(now.minusSeconds(7200)).build());
        unmapped().save(UnmappedWebhookEvent.builder()
                .provider("github").repoFullName("acme/new").eventType("push")
                .discardReason("UNMAPPED_REPOSITORY").receivedAt(now).build());

        int deleted = unmapped().deleteOlderThan(now.minusSeconds(3600));
        assertTrue(deleted >= 1);
        List<UnmappedWebhookEvent> remaining = unmapped().findAllByOrderByReceivedAtDesc();
        assertEquals(1, remaining.size());
        assertEquals("acme/new", remaining.get(0).getRepoFullName());
        assertEquals(1, unmapped().findTop100ByOrderByReceivedAtDesc().size());
    }

    @Test
    void auditLogsGroupByJobAndDeleteByJob() {
        Instant now = Instant.now();
        auditLogs().save(SyncAuditLog.builder().jobId("job-1").level(LogLevel.INFO)
                .message("first").timestamp(now.minusSeconds(20)).build());
        auditLogs().save(SyncAuditLog.builder().jobId("job-1").level(LogLevel.WARN)
                .message("second").timestamp(now).build());
        auditLogs().save(SyncAuditLog.builder().jobId("job-2").level(LogLevel.ERROR)
                .message("other job").timestamp(now).build());

        List<SyncAuditLog> logs = auditLogs().findByJobIdOrderByTimestampAsc("job-1");
        assertEquals(2, logs.size());
        assertEquals("first", logs.get(0).getMessage());

        auditLogs().deleteByJobId("job-1");
        assertEquals(0, auditLogs().findByJobIdOrderByTimestampAsc("job-1").size());
        assertEquals(1, auditLogs().findByJobIdOrderByTimestampAsc("job-2").size());
    }
}
