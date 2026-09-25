package com.gitutility.persistence.contract;

import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.model.enums.TriggerType;
import com.gitutility.repository.SyncJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Store contract for the SyncJob facade — must hold for H2 and MongoDB alike, most
 * importantly the dynamic {@code search} port (nullable filters + FULL/INCREMENTAL lane
 * logic with TRIM blank-ref semantics) and the usage/count queries.
 */
@Transactional
public abstract class SyncJobStoreContract {

    protected abstract SyncJobRepository repo();

    private static final Instant NOW = Instant.now();

    @BeforeEach
    void clean() {
        repo().deleteAll();
    }

    private SyncJob job(String pair, String branch, String ref, SyncStatus status, TriggerType trigger,
                        String mappingId, String queueMessageId, Instant createdAt) {
        return repo().save(SyncJob.builder()
                .pairName(pair)
                .branch(branch)
                .ref(ref)
                .status(status)
                .triggerType(trigger)
                .mappingId(mappingId)
                .queueMessageId(queueMessageId)
                .createdAt(createdAt)
                .build());
    }

    @Test
    void saveAssignsStringIdAndRoundTrips() {
        SyncJob saved = job("pair", "main", "refs/heads/main", SyncStatus.SUCCESS,
                TriggerType.WEBHOOK, "m-1", "qm-1", NOW);
        assertNotNull(saved.getId());
        assertFalse(saved.getId().isBlank());
        assertEquals("qm-1", repo().findById(saved.getId()).orElseThrow().getQueueMessageId());
    }

    @Test
    void findByQueueMessageId() {
        job("pair", "main", "refs/heads/main", SyncStatus.QUEUED, TriggerType.WEBHOOK, "m-1", "qm-unique", NOW);
        job("pair", "dev", "refs/heads/dev", SyncStatus.QUEUED, TriggerType.WEBHOOK, "m-1", "qm-other", NOW);

        assertTrue(repo().findByQueueMessageId("qm-unique").isPresent());
        assertTrue(repo().findByQueueMessageId("qm-missing").isEmpty());
    }

    @Test
    void top20AndPagedFinders() {
        job("p-1", "main", "refs/heads/main", SyncStatus.SUCCESS, TriggerType.WEBHOOK, "m-1", null, NOW.minusSeconds(60));
        job("p-2", "dev", "refs/heads/dev", SyncStatus.FAILED, TriggerType.MANUAL, "m-2", null, NOW);

        assertEquals(2, repo().findTop20ByOrderByCreatedAtDesc().size());
        Page<SyncJob> all = repo().findAllByOrderByCreatedAtDesc(PageRequest.of(0, 1));
        assertEquals(2, all.getTotalElements());
        assertEquals("p-2", all.getContent().get(0).getPairName());
        assertEquals(1, repo().findByMappingIdOrderByCreatedAtDesc("m-1", PageRequest.of(0, 10)).getContent().size());
        assertEquals(1, repo().findByStatusOrderByCreatedAtDesc(SyncStatus.FAILED, PageRequest.of(0, 10)).getContent().size());
    }

    @Test
    void derivedStatusFinders() {
        job("p-1", "main", "refs/heads/main", SyncStatus.FAILED, TriggerType.WEBHOOK, "m-1", null, NOW);
        job("p-2", "dev", "refs/heads/dev", SyncStatus.QUEUED, TriggerType.WEBHOOK, "m-1", null, NOW);

        assertEquals(1, repo().findByStatus(SyncStatus.FAILED).size());
        assertEquals(1, repo().findByStatusAndMappingId(SyncStatus.QUEUED, "m-1").size());
        assertEquals(0, repo().findByStatusAndMappingId(SyncStatus.FAILED, "m-2").size());
    }

    @Test
    void deleteByIdRemovesRow() {
        SyncJob saved = job("p-1", "main", "refs/heads/main", SyncStatus.SUCCESS, TriggerType.WEBHOOK, "m-1", null, NOW);
        repo().deleteById(saved.getId());
        assertTrue(repo().findById(saved.getId()).isEmpty());
    }

    @Test
    void searchWithoutFiltersReturnsEverythingNewestFirst() {
        job("p-old", "main", "refs/heads/main", SyncStatus.SUCCESS, TriggerType.WEBHOOK, "m-1", null, NOW.minusSeconds(120));
        job("p-new", "dev", "refs/heads/dev", SyncStatus.FAILED, TriggerType.MANUAL, "m-2", null, NOW);

        Page<SyncJob> page = repo().search(null, null, null, null, PageRequest.of(0, 10));
        assertEquals(2, page.getTotalElements());
        assertEquals("p-new", page.getContent().get(0).getPairName());
        assertEquals("p-old", page.getContent().get(1).getPairName());
    }

    @Test
    void searchFiltersByStatusMappingIdAndTriggerType() {
        job("p-1", "main", "refs/heads/main", SyncStatus.FAILED, TriggerType.WEBHOOK, "m-1", null, NOW);
        job("p-2", "dev", "refs/heads/dev", SyncStatus.QUEUED, TriggerType.MANUAL, "m-2", null, NOW);

        assertEquals(1, repo().search(SyncStatus.FAILED, null, null, null, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, repo().search(null, "m-2", null, null, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, repo().search(null, null, TriggerType.MANUAL, null, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(0, repo().search(SyncStatus.QUEUED, "m-1", null, null, PageRequest.of(0, 10)).getTotalElements(),
                "no row is both QUEUED and mapped to m-1");
        assertEquals(1, repo().search(SyncStatus.QUEUED, "m-2", null, null, PageRequest.of(0, 10)).getTotalElements());
    }

    @Test
    void searchFullLaneMatchesWildcardBranchAndBlankOrMissingRef() {
        job("p-star", "*", "refs/heads/ignored", SyncStatus.QUEUED, TriggerType.WEBHOOK, "m-1", null, NOW);
        job("p-blank", "dev", "   ", SyncStatus.QUEUED, TriggerType.WEBHOOK, "m-1", null, NOW);
        job("p-nullref", "dev", null, SyncStatus.QUEUED, TriggerType.WEBHOOK, "m-1", null, NOW);
        job("p-incr", "feat", "refs/heads/feat", SyncStatus.QUEUED, TriggerType.WEBHOOK, "m-1", null, NOW);

        Page<SyncJob> page = repo().search(null, null, null, "FULL", PageRequest.of(0, 10));
        assertEquals(3, page.getTotalElements(),
                "FULL lane = TRIM(ref) blank/missing OR branch '*'; the incremental row must be excluded");
    }

    @Test
    void searchIncrementalLaneMatchesNonBlankRefAndNonWildcardBranch() {
        job("p-star", "*", "refs/heads/ignored", SyncStatus.QUEUED, TriggerType.WEBHOOK, "m-1", null, NOW);
        job("p-blank", "dev", "   ", SyncStatus.QUEUED, TriggerType.WEBHOOK, "m-1", null, NOW);
        job("p-nullref", "dev", null, SyncStatus.QUEUED, TriggerType.WEBHOOK, "m-1", null, NOW);
        job("p-incr", "feat", "refs/heads/feat", SyncStatus.QUEUED, TriggerType.WEBHOOK, "m-1", null, NOW);
        job("p-nobranch", null, "refs/heads/x", SyncStatus.QUEUED, TriggerType.WEBHOOK, "m-1", null, NOW);

        Page<SyncJob> page = repo().search(null, null, null, "INCREMENTAL", PageRequest.of(0, 10));
        assertEquals(2, page.getTotalElements(),
                "INCREMENTAL lane = non-blank TRIM(ref) AND (branch null OR branch != '*')");
        assertTrue(page.getContent().stream().allMatch(j -> j.getPairName().equals("p-incr")
                || j.getPairName().equals("p-nobranch")));
    }

    @Test
    void countQueries() {
        job("p-old", "main", "refs/heads/main", SyncStatus.SUCCESS, TriggerType.WEBHOOK, "m-1", null, NOW.minusSeconds(7200));
        job("p-new", "dev", "refs/heads/dev", SyncStatus.QUEUED, TriggerType.MANUAL, "m-2", null, NOW);

        assertEquals(1, repo().countByStatus(SyncStatus.SUCCESS));
        assertEquals(1, repo().countByStatus(SyncStatus.QUEUED));
        assertEquals(1, repo().countJobsSince(NOW.minusSeconds(60)), "only p-new was created inside the window");
        assertEquals(1, repo().countSuccessJobsSince(NOW.minusSeconds(7200)));
    }

    @Test
    void findForUsageWindowReturnsRecentOrActive() {
        job("p-old", "main", "refs/heads/main", SyncStatus.SUCCESS, TriggerType.WEBHOOK, "m-1", null, NOW.minusSeconds(7200));
        SyncJob recent = job("p-recent", "dev", "refs/heads/dev", SyncStatus.SUCCESS, TriggerType.WEBHOOK,
                "m-2", null, NOW);
        recent.setStartedAt(NOW.minusSeconds(30));
        repo().save(recent);
        job("p-queued", "x", "refs/heads/x", SyncStatus.QUEUED, TriggerType.WEBHOOK, "m-3", null, NOW.minusSeconds(7200));

        List<SyncJob> window = repo().findForUsageWindow(NOW.minusSeconds(60));
        assertEquals(2, window.size(), "started within window OR status in (IN_PROGRESS, QUEUED, PAUSED)");
        assertTrue(window.stream().anyMatch(j -> j.getPairName().equals("p-recent")));
        assertTrue(window.stream().anyMatch(j -> j.getPairName().equals("p-queued")));
    }
}
