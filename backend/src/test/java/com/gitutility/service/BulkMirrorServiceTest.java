package com.gitutility.service;

import com.gitutility.model.dto.BulkMirrorRequest;
import com.gitutility.model.dto.BulkMirrorResponse;
import com.gitutility.model.dto.PermissionCheckReport;
import com.gitutility.model.entity.BulkSubmission;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.ScmCredential;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.RepoVisibility;
import com.gitutility.provider.ScmProviderFacade;
import com.gitutility.repository.BulkSubmissionRepository;
import com.gitutility.repository.RepoMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BulkMirrorServiceTest {

    @Mock
    private RepoMappingRepository mappingRepository;
    @Mock
    private BulkSubmissionRepository bulkSubmissionRepository;
    @Mock
    private RepoMappingService repoMappingService;
    @Mock
    private ScmProviderFacade scmProviderFacade;
    @Mock
    private ScmCredentialService scmCredentialService;
    @Mock
    private FeatureFlagsService featureFlagsService;

    private BulkMirrorService service;

    @BeforeEach
    void setUp() {
        ScmCredential destCredential = ScmCredential.builder()
                .id("7")
                .label("dest")
                .accountLogin("acme-org")
                .build();
        service = new BulkMirrorService(
                mappingRepository,
                bulkSubmissionRepository,
                repoMappingService,
                scmProviderFacade,
                scmCredentialService,
                featureFlagsService,
                JsonMapper.builder().build());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxItems", 1000);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "probeChunkSize", 250);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "probeConcurrency", 4);

        lenient().when(bulkSubmissionRepository.save(any(BulkSubmission.class))).thenAnswer(inv -> {
            BulkSubmission sub = inv.getArgument(0);
            sub.setId("42");
            return sub;
        });
        lenient().when(mappingRepository.save(any(RepoMapping.class))).thenAnswer(inv -> {
            RepoMapping m = inv.getArgument(0);
            m.setId("100");
            return m;
        });
        lenient().when(mappingRepository.findByName(anyString())).thenReturn(Optional.empty());
        lenient().when(mappingRepository.findAll()).thenReturn(List.of());
        lenient().when(repoMappingService.triggerInitialBootstrapSync(any(RepoMapping.class))).thenAnswer(inv -> {
            SyncJob job = new SyncJob();
            job.setId("555");
            return job;
        });
        lenient().when(scmCredentialService.require("7")).thenReturn(destCredential);
        lenient().when(scmProviderFacade.repositoryExists(anyString(), eq("7"))).thenReturn(false);
        lenient().when(scmProviderFacade.hasCommits(anyString(), anyString())).thenReturn(false);
        lenient().when(scmProviderFacade.testConnection(isNull(), any()))
                .thenReturn(PermissionCheckReport.builder().valid(true).build());
    }

    private BulkMirrorRequest.BulkItem source(String url) {
        return BulkMirrorRequest.BulkItem.builder()
                .sourceUrl(url)
                .sourceProvider("GITHUB")
                .sourceCredentialId("3")
                .build();
    }

    private BulkMirrorRequest createDestRequest(BulkMirrorRequest.BulkItem... items) {
        return BulkMirrorRequest.builder()
                .mode("CREATE_DEST")
                .items(List.of(items))
                .destCredentialId("7")
                .destPrivate(true)
                .build();
    }

    @Test
    void createDestHappyPathCreatesPairWithAutoCreateFlagAndQueuesJob() {
        BulkMirrorResponse res = service.submit(createDestRequest(source("https://github.com/acme/repo1.git")));

        assertEquals(1, res.getCreatedQueuedCount());
        assertEquals("42", res.getSubmissionId());
        BulkMirrorResponse.Row row = res.getRows().get(0);
        assertEquals(BulkMirrorResponse.Outcome.CREATED_QUEUED, row.getOutcome());
        assertEquals("555", row.getJobId());
        assertEquals("https://github.com/acme-org/repo1.git", row.getDestUrl());
        verify(mappingRepository).save(org.mockito.ArgumentMatchers.argThat((RepoMapping m) ->
                Boolean.TRUE.equals(m.getDestinationAutoCreate())
                        && m.getBulkSubmissionId().equals("42")
                        && m.getTargetVisibility() == RepoVisibility.PRIVATE));
    }

    @Test
    void createDestExistingDestinationIsSkippedNotMapped() {
        when(scmProviderFacade.repositoryExists(eq("https://github.com/acme-org/repo1.git"), eq("7"))).thenReturn(true);

        BulkMirrorResponse res = service.submit(createDestRequest(source("https://github.com/acme/repo1.git")));

        assertEquals(1, res.getSkippedCount());
        assertEquals(0, res.getCreatedQueuedCount());
        assertTrue(res.getRows().get(0).getReason().toLowerCase().contains("already exists"));
        verify(mappingRepository, never()).save(any(RepoMapping.class));
        verify(repoMappingService, never()).triggerInitialBootstrapSync(any());
    }

    @Test
    void duplicateSourcesAreDedupedWithSkip() {
        BulkMirrorResponse res = service.submit(createDestRequest(
                source("https://github.com/acme/repo1.git"),
                source("https://github.com/acme/repo1.git")));

        assertEquals(1, res.getCreatedQueuedCount());
        assertEquals(1, res.getSkippedCount());
        assertTrue(res.getRows().get(1).getReason().toLowerCase().contains("duplicate source"));
    }

    @Test
    void duplicateDestinationsAcrossRowsAreSkipped() {
        BulkMirrorResponse res = service.submit(createDestRequest(
                source("https://github.com/acme/alpha.git"),
                BulkMirrorRequest.BulkItem.builder()
                        .sourceUrl("https://github.com/acme/beta.git")
                        .sourceCredentialId("3")
                        .destName("alpha") // forces the same destination owner/name as row 1
                        .build()));

        assertEquals(1, res.getCreatedQueuedCount());
        assertEquals(1, res.getSkippedCount());
        assertTrue(res.getRows().get(1).getReason().toLowerCase().contains("duplicate destination"));
    }

    @Test
    void sourceAndDestinationCannotBeTheSameRepo() {
        BulkMirrorResponse res = service.submit(createDestRequest(
                BulkMirrorRequest.BulkItem.builder()
                        .sourceUrl("https://github.com/acme-org/same.git")
                        .sourceCredentialId("3")
                        .destName("same")
                        .build()));

        assertEquals(1, res.getSkippedCount());
        assertTrue(res.getRows().get(0).getReason().contains("same repository"));
    }

    @Test
    void collisionWithActivePairIsSkippedWithWarning() {
        RepoMapping existing = RepoMapping.builder()
                .id("9")
                .name("OpenMAIC")
                .repoAUrl("https://github.com/other/source.git")
                .repoBUrl("https://github.com/acme/repo1.git")
                .active(true)
                .build();
        when(mappingRepository.findAll()).thenReturn(List.of(existing));

        BulkMirrorResponse res = service.submit(createDestRequest(source("https://github.com/acme/repo1.git")));

        assertEquals(1, res.getSkippedCount());
        assertTrue(res.getRows().get(0).getReason().contains("OpenMAIC"));
        verify(mappingRepository, never()).save(any(RepoMapping.class));
    }

    @Test
    void useExistingHasCommitsExcludedUnlessOperatorIncludes() {
        when(scmProviderFacade.hasCommits(eq("https://github.com/target/one.git"), eq("5"))).thenReturn(true);
        BulkMirrorRequest.BulkItem excluded = BulkMirrorRequest.BulkItem.builder()
                .sourceUrl("https://github.com/acme/one.git")
                .sourceCredentialId("3")
                .destUrl("https://github.com/target/one.git")
                .destCredentialId("5")
                .includeNonEmptyDest(false)
                .build();
        BulkMirrorRequest.BulkItem included = BulkMirrorRequest.BulkItem.builder()
                .sourceUrl("https://github.com/acme/two.git")
                .sourceCredentialId("3")
                .destUrl("https://github.com/target/two.git")
                .destCredentialId("5")
                .includeNonEmptyDest(true)
                .build();

        BulkMirrorResponse res = service.submit(BulkMirrorRequest.builder()
                .mode("USE_EXISTING")
                .items(List.of(excluded, included))
                .build());

        assertEquals(1, res.getSkippedCount());
        assertEquals(1, res.getCreatedQueuedCount());
        assertTrue(res.getRows().get(0).getReason().contains("existing content"));
    }

    @Test
    void useExistingWithoutWriteAccessFailsValidation() {
        when(scmProviderFacade.testConnection(isNull(), any()))
                .thenReturn(PermissionCheckReport.builder().valid(false).build());

        BulkMirrorResponse res = service.submit(BulkMirrorRequest.builder()
                .mode("USE_EXISTING")
                .items(List.of(BulkMirrorRequest.BulkItem.builder()
                        .sourceUrl("https://github.com/acme/one.git")
                        .sourceCredentialId("3")
                        .destUrl("https://github.com/target/one.git")
                        .destCredentialId("5")
                        .build()))
                .build());

        assertEquals(1, res.getFailedValidationCount());
        assertTrue(res.getRows().get(0).getReason().contains("cannot write"));
        verify(mappingRepository, never()).save(any(RepoMapping.class));
    }

    @Test
    void softRequestGuardRejectsOversizedSubmission() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxItems", 2);
        assertThrows(IllegalArgumentException.class, () -> service.submit(createDestRequest(
                source("https://github.com/acme/a.git"),
                source("https://github.com/acme/b.git"),
                source("https://github.com/acme/c.git"))));
    }
}
