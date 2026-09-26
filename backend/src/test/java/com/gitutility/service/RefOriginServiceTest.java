package com.gitutility.service;

import com.gitutility.model.entity.PrMapping;
import com.gitutility.model.entity.RefOrigin;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.enums.PairSide;
import com.gitutility.repository.PrMappingRepository;
import com.gitutility.repository.RefOriginRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefOriginServiceTest {

    @Mock
    private RefOriginRepository refOriginRepository;
    @Mock
    private PrMappingRepository prMappingRepository;

    private RefOriginService service;

    @BeforeEach
    void setUp() {
        service = new RefOriginService(refOriginRepository, prMappingRepository);
    }

    @Test
    void deletedShaDetectsGitHubZeroOid() {
        assertFalse(RefOriginService.isDeletedSha(null));
        assertTrue(RefOriginService.isDeletedSha("0000000000000000000000000000000000000000"));
        assertFalse(RefOriginService.isDeletedSha("abc123"));
    }

    @Test
    void protectedTrunkCoversMainAndMasterOnly() {
        assertTrue(RefOriginService.isProtectedTrunk("main"));
        assertTrue(RefOriginService.isProtectedTrunk("refs/heads/master"));
        assertFalse(RefOriginService.isProtectedTrunk("feature-x"));
    }

    @Test
    void syntheticForkPrHeadPrefix() {
        assertTrue(RefOriginService.isSyntheticForkPrHead("fork-pr-65"));
        assertTrue(RefOriginService.isSyntheticForkPrHead("refs/heads/fork-pr-1314"));
        assertFalse(RefOriginService.isSyntheticForkPrHead("main"));
        assertFalse(RefOriginService.isSyntheticForkPrHead("add-repocloud-deploy-button"));
    }

    @Test
    void recordIfAbsentSkipsForkPrHeads() {
        when(prMappingRepository.findByMappingId("4")).thenReturn(List.of(
                PrMapping.builder().headBranch("add-repocloud-deploy-button").forkPrHead(true).build()
        ));

        service.recordIfAbsent("4", "refs/heads/add-repocloud-deploy-button", PairSide.B);

        verify(refOriginRepository, never()).save(any());
    }

    @Test
    void shouldOmitHeadPushTowardAForForkPrHead() {
        RepoMapping mapping = RepoMapping.builder().id("4").build();
        when(prMappingRepository.findByMappingId("4")).thenReturn(List.of(
                PrMapping.builder().headBranch("add-repocloud-deploy-button").forkPrHead(true).build()
        ));

        assertTrue(service.shouldOmitHeadPush(mapping, PairSide.B, PairSide.A, "add-repocloud-deploy-button"));
        assertFalse(service.shouldOmitHeadPush(mapping, PairSide.A, PairSide.B, "add-repocloud-deploy-button"));
    }

    @Test
    void observeDestinationHeadsBackfillsForkFlagAndSeedsDestOrigin() {
        RepoMapping mapping = RepoMapping.builder().id("4").build();
        PrMapping pr = PrMapping.builder()
                .id("10")
                .mappingId("4")
                .sourcePrNumber(1287L)
                .headBranch("add-repocloud-deploy-button")
                .forkPrHead(false)
                .build();
        when(prMappingRepository.findByMappingId("4")).thenReturn(List.of(pr));
        when(prMappingRepository.save(any(PrMapping.class))).thenAnswer(i -> i.getArgument(0));
        when(refOriginRepository.findByMappingIdAndRefName(any(), any())).thenReturn(Optional.empty());

        service.observeDestinationHeads(
                mapping,
                Set.of("main", "dev"),
                Set.of("main", "add-repocloud-deploy-button", "dest-only-feature"),
                PairSide.B
        );

        ArgumentCaptor<PrMapping> prCaptor = ArgumentCaptor.forClass(PrMapping.class);
        verify(prMappingRepository).save(prCaptor.capture());
        assertTrue(prCaptor.getValue().isForkPrHead());

        ArgumentCaptor<RefOrigin> originCaptor = ArgumentCaptor.forClass(RefOrigin.class);
        verify(refOriginRepository).save(originCaptor.capture());
        assertEquals("refs/heads/dest-only-feature", originCaptor.getValue().getRefName());
        assertEquals("B", originCaptor.getValue().getOriginSide());
    }

    @Test
    void replicaEventAndDeletePolicy() {
        when(refOriginRepository.findByMappingIdAndRefName("4", "refs/heads/feat"))
                .thenReturn(Optional.of(RefOrigin.builder().originSide("A").refName("refs/heads/feat").build()));
        when(prMappingRepository.findByMappingId("4")).thenReturn(List.of());

        assertTrue(service.isReplicaEvent("4", "feat", PairSide.B));
        assertFalse(service.isReplicaEvent("4", "feat", PairSide.A));

        RepoMapping mapping = RepoMapping.builder().id("4").build();
        assertTrue(service.shouldPropagateDelete(mapping, PairSide.A, "feat"));
        assertFalse(service.shouldPropagateDelete(mapping, PairSide.B, "feat"));
        assertFalse(service.shouldPropagateDelete(mapping, PairSide.A, "main"));
        assertFalse(service.shouldPropagateDelete(mapping, PairSide.A, "sync-conflict/main-20260101-120000"));
    }

    @Test
    void omitsSyncConflictHeadsTowardRepoA() {
        RepoMapping mapping = RepoMapping.builder().id("4").build();
        assertTrue(service.shouldOmitHeadPush(mapping, PairSide.B, PairSide.A, "sync-conflict/main-1"));
        assertFalse(service.shouldOmitHeadPush(mapping, PairSide.A, PairSide.B, "sync-conflict/main-1"));
    }

    @Test
    void newMergeShaIsPushedAndARecordedTipIsOmitted() {
        RepoMapping mapping = RepoMapping.builder().id("4").build();
        assertFalse(service.shouldOmitHeadPush(mapping, PairSide.B, PairSide.A, "main",
                "05b3b4d30c2a8164e70f4379d2be541f3a657e8c", "7bceb28f99"));
        assertTrue(service.shouldOmitHeadPush(mapping, PairSide.B, PairSide.A, "main",
                "7bceb28f99", "7bceb28f99"));
    }

    @Test
    void automatedBotBranchPrefixesAreDetected() {
        assertTrue(RefOriginService.isAutomatedBotBranch("dependabot/npm_and_yarn/foo"));
        assertTrue(RefOriginService.isAutomatedBotBranch("renovate/actions-cache-6.x"));
        assertFalse(RefOriginService.isAutomatedBotBranch("feature/my-work"));
    }

    @Test
    void publicPrivateBackupBlocksUnknownReplicaOrigin() {
        RepoMapping mapping = RepoMapping.builder()
                .id("4")
                .sourceVisibility(com.gitutility.model.enums.RepoVisibility.PUBLIC)
                .targetVisibility(com.gitutility.model.enums.RepoVisibility.PRIVATE)
                .build();
        when(refOriginRepository.findByMappingIdAndRefName("4", "refs/heads/local-only")).thenReturn(Optional.empty());

        assertTrue(service.shouldBlockReplicaInboundWebhook(mapping, "local-only", PairSide.B));
        assertFalse(service.shouldBlockReplicaInboundWebhook(mapping, "local-only", PairSide.A));
    }

    @Test
    void publicPrivateBackupBlocksMirrorOriginatedRef() {
        RepoMapping mapping = RepoMapping.builder()
                .id("4")
                .sourceVisibility(com.gitutility.model.enums.RepoVisibility.PUBLIC)
                .targetVisibility(com.gitutility.model.enums.RepoVisibility.PRIVATE)
                .build();
        when(refOriginRepository.findByMappingIdAndRefName("4", "refs/heads/mirror-work"))
                .thenReturn(Optional.of(RefOrigin.builder().originSide(PairSide.B.name()).build()));

        assertTrue(service.shouldBlockReplicaInboundWebhook(mapping, "mirror-work", PairSide.B));
    }
}
