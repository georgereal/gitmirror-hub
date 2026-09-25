package com.gitutility.persistence.contract;

import com.gitutility.model.entity.PairLease;
import com.gitutility.repository.PairLeaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Store contract for the PairLease facade — H2 relies on a unique constraint + exception
 * translation, MongoDB on a unique key + {@code DuplicateKeyException} (a
 * {@link DataIntegrityViolationException} subclass); the takeover/renew ports are atomic
 * {@code updateOne} filters on MongoDB and bulk JPQL updates on H2. Both must behave alike.
 */
@Transactional
public abstract class PairLeaseStoreContract {

    protected abstract PairLeaseRepository repo();

    private static final Instant NOW = Instant.now();

    @BeforeEach
    void clean() {
        repo().deleteAll();
    }

    private PairLease lease(String mappingId, String owner, String jobId, Instant expiresAt) {
        return PairLease.builder()
                .mappingId(mappingId)
                .ownerInstance(owner)
                .jobId(jobId)
                .expiresAt(expiresAt)
                .updatedAt(NOW)
                .build();
    }

    @Test
    void saveRoundTripsWithMappingIdAsKey() {
        repo().save(lease("pair-1", "pod-a", "job-1", NOW.plus(90, ChronoUnit.SECONDS)));
        assertTrue(repo().findById("pair-1").isPresent());
        assertEquals("pod-a", repo().findById("pair-1").orElseThrow().getOwnerInstance());
    }

    @Test
    void saveWithExistingMappingIdReplacesWithoutCorruption() {
        // Both stores treat save-with-known-id as replace/merge (the TRUE concurrent-absent
        // race surfaces as an integrity violation inside PairLeaseService.acquire, whose
        // catch already handles it; Mongo additionally enforces the unique key — verified
        // in MongoStoreContractTest.rawDuplicateInsertIsRejected).
        repo().save(lease("pair-1", "pod-a", "job-1", NOW.plus(90, ChronoUnit.SECONDS)));
        repo().save(lease("pair-1", "pod-b", "job-2", NOW.plus(180, ChronoUnit.SECONDS)));

        assertEquals(1, repo().count());
        assertEquals("pod-b", repo().findById("pair-1").orElseThrow().getOwnerInstance());
    }

    @Test
    void renewOwnedCountsOnlyMatchingOwner() {
        repo().save(lease("pair-1", "pod-a", "job-1", NOW.plus(90, ChronoUnit.SECONDS)));

        assertEquals(1, repo().renewOwned("pair-1", "pod-a", "job-2", NOW.plus(180, ChronoUnit.SECONDS), NOW));
        assertEquals(0, repo().renewOwned("pair-1", "pod-b", "job-2", NOW.plus(180, ChronoUnit.SECONDS), NOW));
        assertEquals(0, repo().renewOwned("pair-missing", "pod-a", "job-2", NOW.plus(180, ChronoUnit.SECONDS), NOW));

        PairLease renewed = repo().findById("pair-1").orElseThrow();
        assertEquals("job-2", renewed.getJobId());
        assertEquals("pod-a", renewed.getOwnerInstance());
    }

    @Test
    void deleteOwnedCountsOnlyMatchingOwner() {
        repo().save(lease("pair-1", "pod-a", "job-1", NOW.plus(90, ChronoUnit.SECONDS)));

        assertEquals(0, repo().deleteOwned("pair-1", "pod-b"));
        assertEquals(1, repo().deleteOwned("pair-1", "pod-a"));
        assertTrue(repo().findById("pair-1").isEmpty());
    }
}
