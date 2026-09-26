package com.gitutility.bdd;

import com.gitutility.controller.WebhookController;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.RepoVisibility;
import com.gitutility.model.enums.SyncDirection;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncJobRepository;
import com.gitutility.repository.UnmappedWebhookEventRepository;
import com.gitutility.service.DedupLedgerService;
import com.gitutility.service.PairTipEchoService;
import com.gitutility.service.PullRequestSyncService;
import com.gitutility.service.QueueProducerService;
import com.gitutility.service.RefInterestPolicy;
import com.gitutility.service.RefOriginService;
import com.gitutility.service.SystemEngineConfigService;
import com.gitutility.service.UnmappedWebhookRetention;
import com.gitutility.service.ReleaseAndStatusSyncService;
import com.gitutility.service.WebSocketNotificationService;
import com.gitutility.service.WebhookIngestionService;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class WebhookEchoSteps {

    private static final String ZERO_SHA = "0000000000000000000000000000000000000000";

    private RepoMappingRepository mappingRepository;
    private SyncJobRepository syncJobRepository;
    private QueueProducerService queueProducerService;
    private PairTipEchoService pairTipEchoService;
    private WebhookController webhookController;
    private RepoMapping mapping;
    private String originUrl;
    private String mirrorUrl;
    private boolean echoPush;
    private boolean echoDelete;
    private ResponseEntity<?> response;

    @Before("@webhook")
    public void reset() {
        mappingRepository = mock(RepoMappingRepository.class);
        syncJobRepository = mock(SyncJobRepository.class);
        queueProducerService = mock(QueueProducerService.class);
        pairTipEchoService = mock(PairTipEchoService.class);
        echoPush = false;
        echoDelete = false;
        response = null;
        mapping = null;

        WebhookIngestionService ingestion = new WebhookIngestionService(
                mappingRepository,
                syncJobRepository,
                queueProducerService,
                new DedupLedgerService(),
                mock(WebSocketNotificationService.class),
                JsonMapper.builder().build(),
                mock(PullRequestSyncService.class),
                mock(ReleaseAndStatusSyncService.class),
                mock(UnmappedWebhookEventRepository.class),
                mock(UnmappedWebhookRetention.class),
                mock(SystemEngineConfigService.class),
                mock(RefOriginService.class),
                new RefInterestPolicy("agents/,dependabot/", 45_000L, true),
                null);
        lenient().when(pairTipEchoService.pushEcho(any(), anyBoolean(), any(), any()))
                .thenAnswer(invocation -> echoPush);
        lenient().when(pairTipEchoService.deleteEcho(any(), anyBoolean(), any()))
                .thenAnswer(invocation -> echoDelete);
        ingestion.setPairTipEchoService(pairTipEchoService);
        lenient().when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(invocation -> {
            SyncJob job = invocation.getArgument(0);
            if (job.getId() == null) {
                job.setId("42");
            }
            return job;
        });
        webhookController = new WebhookController(
                mappingRepository, ingestion, JsonMapper.builder().build(), null);
    }

    @Given("a bidirectional pair {string} mirroring {string} to {string}")
    public void pair(String name, String origin, String mirror) {
        originUrl = origin;
        mirrorUrl = mirror;
        mapping = RepoMapping.builder()
                .id("1")
                .name(name)
                .repoAUrl(origin)
                .repoBUrl(mirror)
                .active(true)
                .syncDirection(SyncDirection.BIDIRECTIONAL)
                .build();
        when(mappingRepository.findById("1")).thenReturn(Optional.of(mapping));
    }

    @Given("the pair is a public origin with a private mirror")
    public void publicPrivateBackup() {
        mapping.setSourceVisibility(RepoVisibility.PUBLIC);
        mapping.setTargetVisibility(RepoVisibility.PRIVATE);
    }

    @Given("the mirror tip was written by the hub")
    public void mirrorTipEcho() {
        echoPush = true;
    }

    @Given("the mirror delete was written by the hub")
    public void mirrorDeleteEcho() {
        echoDelete = true;
    }

    @When("a push arrives from the mirror on branch {string} at sha {string} with message {string}")
    public void mirrorPush(String branch, String sha, String message) {
        response = webhookController.handleMappingSpecificWebhook(
                "1", "push", null, pushPayload(mirrorUrl + ".git", "acme/mirror-dest", branch, sha, message, false));
    }

    @When("a push arrives from the origin on branch {string} at sha {string} with message {string}")
    public void originPush(String branch, String sha, String message) {
        response = webhookController.handleMappingSpecificWebhook(
                "1", "push", null, pushPayload(originUrl + ".git", "origin/repo", branch, sha, message, false));
    }

    @When("a branch delete arrives from the origin for {string}")
    public void originDelete(String branch) {
        response = webhookController.handleMappingSpecificWebhook(
                "1", "push", null, pushPayload(originUrl + ".git", "origin/repo", branch, ZERO_SHA, "", true));
    }

    @When("a branch delete arrives from the mirror for {string}")
    public void mirrorDelete(String branch) {
        response = webhookController.handleMappingSpecificWebhook(
                "1", "push", null, pushPayload(mirrorUrl + ".git", "acme/mirror-dest", branch, ZERO_SHA, "", true));
    }

    @Then("the webhook is skipped as {string}")
    public void skippedAs(String reason) {
        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<SyncJob> saved = ArgumentCaptor.forClass(SyncJob.class);
        verify(syncJobRepository).save(saved.capture());
        assertEquals(SyncStatus.SKIPPED, saved.getValue().getStatus());
        assertEquals(reason, saved.getValue().getSkipReason());
    }

    @Then("the webhook is accepted and queued")
    public void acceptedAndQueued() {
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(queueProducerService).enqueueSyncJob(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Then("no sync job is queued")
    public void notQueued() {
        verify(queueProducerService, never()).enqueueSyncJob(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private static String pushPayload(String cloneUrl, String fullName, String branch, String sha,
                                      String message, boolean deleted) {
        String headCommit = deleted ? "" : """
                  "head_commit": { "id": "%s", "message": "%s" },
                """.formatted(sha, message);
        return """
                {
                  "ref": "refs/heads/%s",
                  "after": "%s",
                  "deleted": %s,
                  "repository": {
                    "clone_url": "%s",
                    "full_name": "%s"
                  },
                  %s
                  "pusher": { "name": "developer" }
                }
                """.formatted(branch, sha, deleted, cloneUrl, fullName, headCommit);
    }
}
