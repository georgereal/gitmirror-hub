package com.gitutility.bdd;

import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.ScmCredential;
import com.gitutility.model.entity.UnmappedWebhookEvent;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncJobRepository;
import com.gitutility.repository.UnmappedWebhookEventRepository;
import com.gitutility.service.DedupLedgerService;
import com.gitutility.service.PullRequestSyncService;
import com.gitutility.service.QueueProducerService;
import com.gitutility.service.RefOriginService;
import com.gitutility.service.ReleaseAndStatusSyncService;
import com.gitutility.service.ScmCredentialService;
import com.gitutility.service.SystemEngineConfigService;
import com.gitutility.service.UnmappedWebhookRetention;
import com.gitutility.service.WebSocketNotificationService;
import com.gitutility.service.WebhookIngestionService;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MirrorAppActorSteps {

    private static final String ORIGIN = "https://github.com/acme/origin.git";

    private WebhookIngestionService ingestion;
    private ScmCredentialService credentials;
    private UnmappedWebhookEventRepository unmapped;
    private PullRequestSyncService pullRequestSync;
    private RepoMapping mapping;

    @Before("@mirror-app")
    public void reset() {
        credentials = mock(ScmCredentialService.class);
        unmapped = mock(UnmappedWebhookEventRepository.class);
        pullRequestSync = mock(PullRequestSyncService.class);
        when(unmapped.save(any(UnmappedWebhookEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ingestion = new WebhookIngestionService(
                mock(RepoMappingRepository.class),
                mock(SyncJobRepository.class),
                mock(QueueProducerService.class),
                mock(DedupLedgerService.class),
                mock(WebSocketNotificationService.class),
                JsonMapper.builder().build(),
                pullRequestSync,
                mock(ReleaseAndStatusSyncService.class),
                unmapped,
                mock(UnmappedWebhookRetention.class),
                mock(SystemEngineConfigService.class),
                mock(RefOriginService.class),
                null,
                credentials);
        mapping = RepoMapping.builder()
                .id("1")
                .name("origin-mirror")
                .repoAUrl(ORIGIN)
                .repoBUrl("https://github.com/acme/mirror.git")
                .sourceCredentialId("cred-origin")
                .build();
    }

    @Given("the inbound credential bot login is {string}")
    public void botLogin(String login) {
        when(credentials.require("cred-origin")).thenReturn(ScmCredential.builder()
                .id("cred-origin")
                .botLogin(login)
                .build());
    }

    @When("a pull request opened event arrives from sender {string}")
    public void pullRequestOpened(String sender) {
        String payload = """
                {
                  "action": "opened",
                  "sender": {"login": "%s"},
                  "repository": {"clone_url": "%s"},
                  "pull_request": {
                    "number": 7,
                    "merged": false,
                    "head": {"ref": "feature/app", "sha": "abc123"}
                  }
                }
                """.formatted(sender, ORIGIN);
        ingestion.processGithubDelivery(mapping, "pull_request", payload);
    }

    @Then("the event is discarded as {string}")
    public void discardedAs(String reason) {
        ArgumentCaptor<UnmappedWebhookEvent> saved = ArgumentCaptor.forClass(UnmappedWebhookEvent.class);
        verify(unmapped).save(saved.capture());
        assertTrue(saved.getAllValues().stream().anyMatch(event -> reason.equals(event.getDiscardReason())));
    }

    @Then("the event is not discarded as {string}")
    public void notDiscardedAs(String reason) {
        verify(unmapped, never()).save(argThat(event -> reason.equals(event.getDiscardReason())));
    }

    @Then("the pull request is not synced")
    public void pullRequestNotSynced() {
        verify(pullRequestSync, never()).handlePrWebhookEvent(
                any(RepoMapping.class), any(), any(JsonNode.class), any());
    }
}
