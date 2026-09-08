package com.gitutility.service;

import com.gitutility.model.dto.SyncDiffReport;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.repository.RepoMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PairCatchupLedgerTest {

    @Mock
    private RepoMappingRepository repoMappingRepository;

    private PairCatchupLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = new PairCatchupLedger(repoMappingRepository);
    }

    @Test
    void fingerprintTipsIsOrderIndependent() {
        String a = PairCatchupLedger.fingerprintTips(Map.of(
                "refs/heads/main", "abc",
                "refs/heads/dev", "def"));
        String b = PairCatchupLedger.fingerprintTips(Map.of(
                "refs/heads/dev", "DEF",
                "refs/heads/main", "ABC"));
        assertEquals(a, b);
        assertNotNull(a);
        assertEquals(64, a.length());
    }

    @Test
    void deltaCutoffSubtractsOverlap() {
        Instant completed = Instant.parse("2026-09-07T12:00:00Z");
        Instant cutoff = PairCatchupLedger.deltaCutoff(completed);
        assertEquals(Instant.parse("2026-09-07T11:58:00Z"), cutoff);
        assertNull(PairCatchupLedger.deltaCutoff(null));
    }

    @Test
    void allItemsOlderThanRequiresEveryUpdatedAt() {
        Instant cutoff = Instant.parse("2026-09-07T12:00:00Z");
        assertTrue(PairCatchupLedger.allItemsOlderThan(List.of(
                SyncDiffReport.PrSyncDetail.builder()
                        .sourcePrNumber(1L)
                        .updatedAt(Instant.parse("2026-09-07T11:00:00Z"))
                        .build()
        ), cutoff));
        assertFalse(PairCatchupLedger.allItemsOlderThan(List.of(
                SyncDiffReport.PrSyncDetail.builder()
                        .sourcePrNumber(1L)
                        .updatedAt(Instant.parse("2026-09-07T13:00:00Z"))
                        .build()
        ), cutoff));
        assertFalse(PairCatchupLedger.allItemsOlderThan(List.of(
                SyncDiffReport.PrSyncDetail.builder()
                        .sourcePrNumber(1L)
                        .updatedAt(null)
                        .build()
        ), cutoff));
    }

    @Test
    void shouldSkipForkFetchRespectsTtl() {
        RepoMapping mapping = new RepoMapping();
        mapping.setForkPrMissJson("42\t" + Instant.now().minusSeconds(60) + "\n");
        assertTrue(ledger.shouldSkipForkFetch(mapping, 42L));
        assertFalse(ledger.shouldSkipForkFetch(mapping, 99L));

        mapping.setForkPrMissJson("42\t" + Instant.now().minus(PairCatchupLedger.FORK_MISS_TTL).minusSeconds(10) + "\n");
        assertFalse(ledger.shouldSkipForkFetch(mapping, 42L));
    }

    @Test
    void recordPrListCompletedPersistsTimestamp() {
        RepoMapping mapping = new RepoMapping();
        mapping.setId(7L);
        when(repoMappingRepository.findById(7L)).thenReturn(Optional.of(mapping));

        ledger.recordPrListCompleted(7L);

        assertNotNull(mapping.getLastPrListCompletedAt());
        verify(repoMappingRepository).save(mapping);
    }
}
