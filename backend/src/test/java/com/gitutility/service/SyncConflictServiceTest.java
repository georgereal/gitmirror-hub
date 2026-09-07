package com.gitutility.service;

import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.SyncConflict;
import com.gitutility.model.enums.ConflictKind;
import com.gitutility.model.enums.ConflictStatus;
import com.gitutility.model.enums.TrunkConflictPolicy;
import com.gitutility.provider.ScmProviderAdapter;
import com.gitutility.provider.ScmProviderFacade;
import com.gitutility.repository.SyncConflictRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncConflictServiceTest {

    @Mock
    private SyncConflictRepository conflictRepository;
    @Mock
    private ScmProviderFacade scmProviderFacade;
    @Mock
    private ActionsTriggerSuppressionService actionsTriggerSuppressionService;
    @Mock
    private ScmProviderAdapter adapter;

    private SyncConflictService service;

    @BeforeEach
    void setUp() {
        service = new SyncConflictService(conflictRepository, scmProviderFacade, actionsTriggerSuppressionService);
        lenient().when(conflictRepository.save(any(SyncConflict.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void recordsGitRefConflictWhenNoneOpen() {
        when(conflictRepository.findByMappingIdAndRefNameAndDestShaAndStatus(eq(4L), any(), any(), eq(ConflictStatus.OPEN)))
                .thenReturn(Optional.empty());
        when(conflictRepository.findByMappingIdAndRefNameAndDestShaAndStatus(eq(4L), any(), any(), eq(ConflictStatus.PR_OPENED)))
                .thenReturn(Optional.empty());

        SyncConflict row = service.recordGitRefConflict(4L, 9L, ConflictKind.GIT_REF, TrunkConflictPolicy.ISOLATE,
                "refs/heads/main", "aaa", "bbb", "sync-conflict/main-1", "https://github.com/org/dest", "isolated");

        assertEquals(ConflictStatus.OPEN, row.getStatus());
        assertEquals("sync-conflict/main-1", row.getIsolatedBranch());
    }

    @Test
    void openConflictPrCreatesDestinationPullRequest() {
        RepoMapping mapping = RepoMapping.builder().id(4L).repoBUrl("https://github.com/org/dest").build();
        SyncConflict conflict = SyncConflict.builder()
                .mappingId(4L)
                .refName("refs/heads/main")
                .isolatedBranch("sync-conflict/main-1")
                .sourceSha("abcdef1")
                .destSha("bbbbbb2")
                .status(ConflictStatus.OPEN)
                .build();
        when(scmProviderFacade.parseRepoFullName("https://github.com/org/dest")).thenReturn("org/dest");
        when(scmProviderFacade.getAdapterForUrl("https://github.com/org/dest")).thenReturn(adapter);
        when(adapter.createPullRequest(eq("org/dest"), anyString(), anyString(), eq("sync-conflict/main-1"), eq("main")))
                .thenReturn(12L);

        SyncConflict updated = service.openConflictPr(conflict, mapping, "https://github.com/org/dest");

        assertEquals(12L, updated.getConflictPrNumber());
        assertEquals(ConflictStatus.PR_OPENED, updated.getStatus());
    }

    @Test
    void replicaMatchesLastPushDetectsDivergence() {
        var pm = com.gitutility.model.entity.PrMapping.builder()
                .lastPushedTitle("a")
                .lastPushedBody("b")
                .build();
        var snap = com.gitutility.model.dto.PullRequestSnapshot.builder().title("a").body("b").build();
        assertTrue(PullRequestSyncService.replicaMatchesLastPush(pm, snap));
        snap.setTitle("changed");
        assertFalse(PullRequestSyncService.replicaMatchesLastPush(pm, snap));
    }
}
