package com.gitutility.controller;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncDirection;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncJobRepository;
import com.gitutility.service.DedupLedgerService;
import com.gitutility.service.PairTipEchoService;
import com.gitutility.service.PullRequestSyncService;
import com.gitutility.service.QueueProducerService;
import com.gitutility.service.ReleaseAndStatusSyncService;
import com.gitutility.service.WebhookIngestionService;
import com.gitutility.service.WebSocketNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookControllerTest {

    @Mock
    private RepoMappingRepository mappingRepository;
    @Mock
    private SyncJobRepository syncJobRepository;
    @Mock
    private QueueProducerService queueProducerService;
    @Mock
    private DedupLedgerService dedupLedgerService;
    @Mock
    private WebSocketNotificationService webSocketNotificationService;
    @Mock
    private PullRequestSyncService pullRequestSyncService;
    @Mock
    private ReleaseAndStatusSyncService releaseAndStatusSyncService;
    @Mock
    private com.gitutility.repository.UnmappedWebhookEventRepository unmappedWebhookEventRepository;
    @Mock
    private com.gitutility.service.RefOriginService refOriginService;

    private WebhookController webhookController;
    private WebhookIngestionService webhookIngestionService;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        webhookIngestionService = new WebhookIngestionService(
                mappingRepository,
                syncJobRepository,
                queueProducerService,
                dedupLedgerService,
                webSocketNotificationService,
                objectMapper,
                pullRequestSyncService,
                releaseAndStatusSyncService,
                unmappedWebhookEventRepository,
                mock(com.gitutility.service.UnmappedWebhookRetention.class),
                mock(com.gitutility.service.SystemEngineConfigService.class),
                refOriginService,
                new com.gitutility.service.RefInterestPolicy("agents/,dependabot/", 45_000L, true),
                null
        );
        webhookController = new WebhookController(
                mappingRepository,
                webhookIngestionService,
                objectMapper,
                null
        );
    }

    @Test
    void testEnqueueValidPushWebhook() {
        RepoMapping mapping = RepoMapping.builder()
                .id("1")
                .name("test-pair")
                .repoAUrl("https://github.com/org/repo-a.git")
                .repoBUrl("https://github.com/org/repo-b.git")
                .active(true)
                .syncDirection(SyncDirection.BIDIRECTIONAL)
                .build();

        when(mappingRepository.findById("1")).thenReturn(Optional.of(mapping));
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(i -> {
            SyncJob j = i.getArgument(0);
            j.setId("101");
            return j;
        });

        String payload = """
                {
                  "ref": "refs/heads/main",
                  "before": "0000000000000000",
                  "after": "sha123456",
                  "repository": {
                    "clone_url": "https://github.com/org/repo-a.git",
                    "full_name": "org/repo-a"
                  },
                  "head_commit": {
                    "id": "sha123456",
                    "message": "feat: new feature"
                  },
                  "pusher": {
                    "name": "developer"
                  }
                }
                """;

        ResponseEntity<?> response = webhookController.handleMappingSpecificWebhook(
                "1", "push", null, payload
        );

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(queueProducerService, times(1)).enqueueSyncJob(
                eq(mapping), any(SyncJob.class), any(), any(), eq("refs/heads/main"), eq("main"), any(), eq("sha123456"), any(), any(), any()
        );
    }

    @Test
    void testSkipWebhookWhenLoopEchoDetected() {
        RepoMapping mapping = RepoMapping.builder()
                .id("1")
                .name("test-pair")
                .repoAUrl("https://github.com/org/repo-a.git")
                .repoBUrl("https://github.com/org/repo-b.git")
                .active(true)
                .syncDirection(SyncDirection.BIDIRECTIONAL)
                .build();

        when(mappingRepository.findById("1")).thenReturn(Optional.of(mapping));
        PairTipEchoService peerTips = mock(PairTipEchoService.class);
        when(peerTips.pushEcho(any(), anyBoolean(), any(), eq("sha123456"))).thenReturn(true);
        webhookIngestionService.setPairTipEchoService(peerTips);

        String payload = """
                {
                  "ref": "refs/heads/main",
                  "after": "sha123456",
                  "repository": {
                    "clone_url": "https://github.com/org/repo-b.git"
                  }
                }
                """;

        ResponseEntity<?> response = webhookController.handleMappingSpecificWebhook(
                "1", "push", null, payload
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals("skipped", body.get("status"));
        assertEquals("Other repository already has refs/heads/main at this tip", body.get("reason"));

        // Must NOT enqueue to queue
        verify(queueProducerService, never()).enqueueSyncJob(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }
}
