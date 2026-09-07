package com.gitutility.service;

import com.gitutility.model.dto.SyncEventMessage;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.enums.SyncCheckpointStage;
import com.gitutility.repository.RepoMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncCheckpointServiceTest {

    @Mock
    private RepoMappingRepository repoMappingRepository;

    private SyncCheckpointService service;

    @BeforeEach
    void setUp() {
        service = new SyncCheckpointService(repoMappingRepository);
    }

    @Test
    void lfsObjectBlobRoundTrip() {
        var objects = java.util.List.of(
                new GitLfsSyncService.LfsObject("a".repeat(64), 1024L),
                new GitLfsSyncService.LfsObject("b".repeat(64), 2048L)
        );
        String blob = SyncCheckpointService.serializeLfsObjects(objects);
        var parsed = SyncCheckpointService.parseLfsObjects(blob);
        assertEquals(2, parsed.size());
        assertEquals(objects.get(0).oid(), parsed.get(0).oid());
        assertEquals(1024L, parsed.get(0).size());
    }

    @Test
    void incrementalFetchUsesSingleBranchRefspec() {
        SyncEventMessage event = SyncEventMessage.builder()
                .branch("feature/x")
                .ref("refs/heads/feature/x")
                .build();
        var specs = GitSyncEngine.sourceFetchRefSpecs(event);
        assertEquals(1, specs.length);
        assertTrue(specs[0].getSource().contains("feature/x"));
    }

    @Test
    void fullMirrorFetchUsesWildcards() {
        SyncEventMessage event = SyncEventMessage.builder().branch("*").build();
        var specs = GitSyncEngine.sourceFetchRefSpecs(event);
        assertTrue(specs.length >= 4);
    }

    @Test
    void resolveResumeStagePromotesCompletedLfsToGitSyncDone() {
        String oid = "a".repeat(64);
        RepoMapping mapping = new RepoMapping();
        mapping.setSyncCheckpointStage("LFS_TRANSFER_PARTIAL");
        mapping.setDiscoveredLfsOids(oid + "\t1024\n");
        mapping.setCompletedLfsOids(oid + "\n");

        assertEquals(SyncCheckpointStage.GIT_SYNC_DONE, service.resolveResumeStage(mapping));
    }

    @Test
    void resolveResumeStageKeepsPartialLfsTransfer() {
        RepoMapping mapping = new RepoMapping();
        mapping.setSyncCheckpointStage("LFS_TRANSFER_PARTIAL");
        mapping.setDiscoveredLfsOids("a".repeat(64) + "\t1024\nb".repeat(64) + "\t2048\n");
        mapping.setCompletedLfsOids("a".repeat(64) + "\n");

        assertEquals(SyncCheckpointStage.LFS_TRANSFER_PARTIAL, service.resolveResumeStage(mapping));
    }

    @Test
    void clearResumeCheckpointPreservesMirrorSnapshot() {
        RepoMapping mapping = new RepoMapping();
        mapping.setId(6L);
        mapping.setName("test");
        mapping.setSyncCheckpointStage("LFS_DISCOVERY_DONE");
        mapping.setCompletedPushRefs("refs/heads/main=abc\n");
        mapping.setLastMirrorLfsObjects(678);
        mapping.setLastMirrorJobId(5328L);

        when(repoMappingRepository.findById(6L)).thenReturn(Optional.of(mapping));

        service.clearResumeCheckpoint(6L);

        assertNull(mapping.getSyncCheckpointStage());
        assertNull(mapping.getCompletedPushRefs());
        assertEquals(678, mapping.getLastMirrorLfsObjects());
        assertEquals(5328L, mapping.getLastMirrorJobId());
        verify(repoMappingRepository).save(mapping);
    }

    @Test
    void resetPairProgressClearsStageLfsAndPushLedger() {
        RepoMapping mapping = new RepoMapping();
        mapping.setId(5L);
        mapping.setName("test");
        mapping.setSyncCheckpointStage("LFS_DISCOVERY_DONE");
        mapping.setCompletedPushRefs("refs/heads/main=abc\n");
        mapping.setDiscoveredLfsOids("aa".repeat(32) + "\t1024\n");
        mapping.setCompletedLfsOids("bb".repeat(32) + "\n");
        mapping.setLastMirrorLfsObjects(42);

        when(repoMappingRepository.findById(5L)).thenReturn(Optional.of(mapping));

        service.resetPairProgress(5L);

        assertNull(mapping.getSyncCheckpointStage());
        assertNull(mapping.getCompletedPushRefs());
        assertNull(mapping.getDiscoveredLfsOids());
        assertNull(mapping.getCompletedLfsOids());
        assertNull(mapping.getLastMirrorLfsObjects());
        verify(repoMappingRepository).save(mapping);
    }
}
