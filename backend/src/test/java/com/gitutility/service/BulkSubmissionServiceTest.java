package com.gitutility.service;

import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.entity.BulkSubmission;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.repository.BulkSubmissionRepository;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BulkSubmissionServiceTest {

    @Mock
    private BulkSubmissionRepository bulkSubmissionRepository;
    @Mock
    private RepoMappingRepository mappingRepository;
    @Mock
    private SyncJobRepository syncJobRepository;
    @Mock
    private SyncJobService syncJobService;
    @Mock
    private JobCancellationService jobCancellationService;

    private BulkSubmissionService service;
    private JobCancellationService realCancellation = new JobCancellationService();

    private RepoMapping failedMapping;
    private RepoMapping sibling;
    private RepoMapping otherCredMapping;

    @BeforeEach
    void setUp() {
        service = new BulkSubmissionService(
                bulkSubmissionRepository,
                mappingRepository,
                syncJobRepository,
                syncJobService,
                realCancellation);

        failedMapping = RepoMapping.builder()
                .id(1L).name("a").bulkSubmissionId(7L).targetCredentialId(3L)
                .repoAUrl("https://github.com/acme/a.git").repoBUrl("https://github.com/target/a.git")
                .build();
        sibling = RepoMapping.builder()
                .id(2L).name("b").bulkSubmissionId(7L).targetCredentialId(3L)
                .repoAUrl("https://github.com/acme/b.git").repoBUrl("https://github.com/target/b.git")
                .build();
        otherCredMapping = RepoMapping.builder()
                .id(3L).name("c").bulkSubmissionId(7L).targetCredentialId(9L)
                .repoAUrl("https://github.com/acme/c.git").repoBUrl("https://github.com/target2/c.git")
                .build();

        lenient().when(mappingRepository.findByBulkSubmissionId(7L))
                .thenReturn(List.of(failedMapping, sibling, otherCredMapping));
    }

    @Test
    void accessFailureStopsQueuedSiblingsOfSameSubmissionAndCredential() {
        SyncJob queuedSibling = SyncJob.builder().id(22L).mappingId(2L).pairName("b").status(SyncStatus.QUEUED).build();
        when(syncJobRepository.findByStatusAndMappingId(SyncStatus.QUEUED, 2L)).thenReturn(List.of(queuedSibling));
        when(syncJobRepository.findByStatusAndMappingId(SyncStatus.QUEUED, 3L)).thenReturn(List.of());

        int cancelled = service.stopRemainingQueuedOnAccessFailure(failedMapping, "Destination creation failed (access) — batch stopped");

        assertEquals(1, cancelled);
        assertEquals(SyncStatus.CANCELLED, queuedSibling.getStatus());
        assertTrue(queuedSibling.getErrorMessage().contains("batch stopped"));
        // The failed mapping itself is untouched (its own job is handled by the engine)
        verify(syncJobRepository, never()).findByStatusAndMappingId(SyncStatus.QUEUED, 1L);
        // Other credentials keep their queued jobs (they may not share the access problem)
        verify(syncJobRepository, never()).findByStatusAndMappingId(SyncStatus.QUEUED, 3L);
    }

    @Test
    void accessFailureWithoutSubmissionLinkIsNoOp() {
        failedMapping.setBulkSubmissionId(null);
        assertEquals(0, service.stopRemainingQueuedOnAccessFailure(failedMapping, "reason"));
        verify(syncJobRepository, never()).findByStatusAndMappingId(any(), any());
    }

    @Test
    void cancelSubmissionCancelsQueuedJobsAndFlagsInProgress() {
        BulkSubmission submission = BulkSubmission.builder().id(7L).mode("CREATE_DEST").itemCount(3).build();
        when(bulkSubmissionRepository.findById(7L)).thenReturn(Optional.of(submission));
        when(syncJobService.cancelQueuedJobs(1L)).thenReturn(2);
        when(syncJobService.cancelQueuedJobs(2L)).thenReturn(0);
        when(syncJobService.cancelQueuedJobs(3L)).thenReturn(0);
        SyncJob inProgress = SyncJob.builder().id(31L).mappingId(3L).pairName("c").status(SyncStatus.IN_PROGRESS).build();
        when(syncJobRepository.findByStatusAndMappingId(SyncStatus.IN_PROGRESS, 3L)).thenReturn(List.of(inProgress));

        int cancelled = service.cancelSubmission(7L, "Cancelled by operator (bulk submission)");

        assertEquals(3, cancelled);
        ArgumentCaptor<BulkSubmission> captor = ArgumentCaptor.forClass(BulkSubmission.class);
        verify(bulkSubmissionRepository).save(captor.capture());
        assertNotNull(captor.getValue().getCancelledAt());
        assertTrue(realCancellation.isCancelRequested(31L));
	}
}
