package com.gitutility.service;

import com.gitutility.model.dto.PairDiffSnapshot;
import com.gitutility.model.dto.SyncDiffReport;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.repository.PrMappingRepository;
import com.gitutility.repository.RepoMappingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PairDiffSnapshotServiceTest {

    @Mock private RepoMappingRepository repoMappingRepository;
    @Mock private PrMappingRepository prMappingRepository;
    @InjectMocks private PairDiffSnapshotService service;

    @Test
    void persistFromReportStoresJsonOnMapping() {
        RepoMapping mapping = new RepoMapping();
        mapping.setId(7L);
        when(repoMappingRepository.findById(7L)).thenReturn(Optional.of(mapping));

        SyncDiffReport report = SyncDiffReport.builder()
                .overallStatus("IN_SYNC")
                .totalBranchesCount(100)
                .inSyncBranchesCount(98)
                .pendingBranchesCount(2)
                .lfs(SyncDiffReport.LfsSyncSummary.builder().totalDiscovered(10).syncedCount(8).pendingCount(2).inSync(false).build())
                .tags(SyncDiffReport.TagSyncSummary.builder().sourceTagsCount(5).targetTagsCount(5).inSync(true).build())
                .pullRequests(List.of())
                .build();

        service.persistFromReport(7L, report, "FULL_REFRESH");

        ArgumentCaptor<RepoMapping> captor = ArgumentCaptor.forClass(RepoMapping.class);
        verify(repoMappingRepository).save(captor.capture());
        assertNotNull(captor.getValue().getDiffSnapshotJson());
        assertNotNull(captor.getValue().getDiffSnapshotAt());
        assertEquals(100, service.load(captor.getValue()).getTotalBranchesCount());
        assertEquals(10, service.load(captor.getValue()).getLfsTotal());
        assertEquals(8, service.load(captor.getValue()).getLfsSynced());
        assertEquals(10, captor.getValue().getLastMirrorLfsObjects());
    }

    @Test
    void pageLoadLfsPrefersCompletedMirrorOverPartialSnapshot() {
        RepoMapping mapping = new RepoMapping();
        mapping.setLastMirrorLfsObjects(678);
        PairDiffSnapshot snapshot = PairDiffSnapshot.builder()
                .lfsTotal(98)
                .lfsSynced(0)
                .build();
        SyncDiffReport.SyncDiffReportBuilder builder = SyncDiffReport.builder();

        PairDiffSnapshotService.applyStoredLfs(builder, mapping, snapshot);

        SyncDiffReport.LfsSyncSummary lfs = builder.build().getLfs();
        assertEquals(678, lfs.getTotalDiscovered());
        assertEquals(678, lfs.getSyncedCount());
        assertEquals(0, lfs.getPendingCount());
    }

    @Test
    void updateFromGitResultKeepsSourceAndDestBranchCounts() {
        RepoMapping mapping = new RepoMapping();
        mapping.setId(7L);
        when(repoMappingRepository.findById(7L)).thenReturn(Optional.of(mapping));

        GitSyncEngine.SyncResult result = new GitSyncEngine.SyncResult();
        result.success = true;
        result.sourceBranchesCount = 4973;
        result.destBranchesCount = 6133;
        result.inSyncBranchesCount = 4973;
        result.destOnlyBranchesCount = 1160;
        result.pendingBranchesCount = 0;
        result.branchesCount = 4973;
        result.sourceTagsCount = 386;
        result.destTagsCount = 386;
        result.lfsObjectsCount = 98;
        result.lfsSyncedCount = 98;

        service.updateFromGitResult(7L, result);

        PairDiffSnapshot snapshot = service.load(mapping);
        assertEquals(4973, snapshot.getSourceBranchesCount());
        assertEquals(6133, snapshot.getDestBranchesCount());
        assertEquals(4973, snapshot.getInSyncBranchesCount());
        assertEquals(1160, snapshot.getDestOnlyBranchesCount());
        assertEquals(98, snapshot.getLfsTotal());
        assertEquals(98, snapshot.getLfsSynced());
        assertEquals(386, snapshot.getTagsSourceCount());
        assertEquals(386, snapshot.getTagsTargetCount());
    }

    @Test
    void updateLfsStoresDiscoveredAndSyncedSeparately() {
        RepoMapping mapping = new RepoMapping();
        mapping.setId(3L);
        when(repoMappingRepository.findById(3L)).thenReturn(Optional.of(mapping));

        service.updateLfs(3L, 98, 98);

        PairDiffSnapshot snapshot = service.load(mapping);
        assertEquals(98, snapshot.getLfsTotal());
        assertEquals(98, snapshot.getLfsSynced());
        assertEquals(0, snapshot.getLfsPending());
    }
}
