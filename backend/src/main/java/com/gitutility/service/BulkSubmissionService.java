package com.gitutility.service;

import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.repository.BulkSubmissionRepository;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Lifecycle operations on a bulk migration submission: operator bulk-cancel of every
 * job the submission created, and the engine-side fail-fast that stops the remaining
 * queued jobs of a submission when destination creation fails for access reasons
 * (siblings share one destination credential + owner, so they would fail identically).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BulkSubmissionService {

    private final BulkSubmissionRepository bulkSubmissionRepository;
    private final RepoMappingRepository mappingRepository;
    private final SyncJobRepository syncJobRepository;
    private final SyncJobService syncJobService;
    private final JobCancellationService jobCancellationService;

    /**
     * Cancels every queued job of a submission (per mapping) and cooperatively requests
     * cancellation of in-progress ones. Skipped rows have no jobs and are untouched.
     */
    public int cancelSubmission(String submissionId, String reason) {
        bulkSubmissionRepository.findById(submissionId).ifPresent(sub -> {
            if (sub.getCancelledAt() == null) {
                sub.setCancelledAt(Instant.now());
                bulkSubmissionRepository.save(sub);
            }
        });
        List<RepoMapping> mappings = mappingRepository.findByBulkSubmissionId(submissionId);
        int cancelled = 0;
        for (RepoMapping mapping : mappings) {
            cancelled += syncJobService.cancelQueuedJobs(mapping.getId());
            List<SyncJob> inProgress =
                    syncJobRepository.findByStatusAndMappingId(SyncStatus.IN_PROGRESS, mapping.getId());
            for (SyncJob job : inProgress) {
                jobCancellationService.requestCancel(job.getId());
                log.info("Bulk cancel: cooperative cancel requested for in-progress Job #{} [{}]",
                        job.getId(), job.getPairName());
                cancelled++;
            }
        }
        log.info("Bulk submission {} cancel: {} job(s) cancelled/flagged{}", submissionId, cancelled,
                mappings.isEmpty() ? " (no mappings found)" : "");
        return cancelled;
    }

    /**
     * Engine fail-fast (destination auto-create): cancels remaining QUEUED jobs of the same
     * submission that target the same destination credential, so an access failure does not
     * burn the queue on identical failures. In-progress jobs are left alone.
     */
    public int stopRemainingQueuedOnAccessFailure(RepoMapping failedMapping, String reason) {
        String submissionId = failedMapping.getBulkSubmissionId();
        if (submissionId == null) {
            return 0;
        }
        int cancelled = 0;
        for (RepoMapping sibling : mappingRepository.findByBulkSubmissionId(submissionId)) {
            if (Objects.equals(sibling.getId(), failedMapping.getId())) {
                continue;
            }
            if (!Objects.equals(sibling.getTargetCredentialId(), failedMapping.getTargetCredentialId())) {
                continue;
            }
            for (SyncJob job : syncJobRepository.findByStatusAndMappingId(SyncStatus.QUEUED, sibling.getId())) {
                jobCancellationService.requestCancel(job.getId());
                job.setStatus(SyncStatus.CANCELLED);
                job.setCompletedAt(Instant.now());
                job.setDurationMs(0L);
                job.setErrorMessage(reason);
                syncJobRepository.save(job);
                cancelled++;
            }
        }
        if (cancelled > 0) {
            log.warn("Bulk submission {} fail-fast: cancelled {} queued sibling job(s) after destination "
                    + "creation access failure on mapping {}", submissionId, cancelled, failedMapping.getId());
        }
        return cancelled;
    }
}
