package com.gitutility.messaging.webhook;

import com.gitutility.model.dto.IncrementalGitEvent;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.UnmappedWebhookEvent;
import com.gitutility.model.enums.SyncDirection;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncJobRepository;
import com.gitutility.repository.UnmappedWebhookEventRepository;
import com.gitutility.service.DedupLedgerService;
import com.gitutility.service.PairLeaseService;
import com.gitutility.service.QueueConsumerService;
import com.gitutility.service.RefInterestPolicy;
import com.gitutility.service.RefOriginService;
import com.gitutility.service.WebSocketNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookIncrementalServiceTest {

    @Mock RepoMappingRepository mappingRepository;
    @Mock SyncJobRepository syncJobRepository;
    @Mock UnmappedWebhookEventRepository unmappedWebhookEventRepository;
    @Mock DedupLedgerService dedupLedgerService;
    @Mock RefOriginService refOriginService;
    @Mock RefInterestPolicy refInterestPolicy;
    @Mock QueueConsumerService queueConsumerService;
    @Mock PairLeaseService pairLeaseService;
    @Mock WebhookEventPublisher publisher;
    @Mock WebSocketNotificationService webSocketNotificationService;

    private WebhookIncrementalService service;

    @BeforeEach
    void setUp() {
        service = new WebhookIncrementalService(
                mappingRepository,
                syncJobRepository,
                unmappedWebhookEventRepository,
                dedupLedgerService,
                refOriginService,
                refInterestPolicy,
                queueConsumerService,
                pairLeaseService,
                publisher,
                webSocketNotificationService);
    }

    @Test
    void echoCommitIsDroppedBeforeAJobIsCreated() throws Exception {
        RepoMapping mapping = RepoMapping.builder()
                .id("pair-1")
                .name("demo")
                .repoAUrl("https://github.com/acme/app.git")
                .repoBUrl("https://github.com/acme/app-mirror.git")
                .syncDirection(SyncDirection.BIDIRECTIONAL)
                .active(true)
                .build();
        when(mappingRepository.findActiveMatchingRepo(anyString(), anyString())).thenReturn(List.of(mapping));
        when(refInterestPolicy.shouldEnqueuePushWebhook(any(), anyString())).thenReturn(true);
        when(dedupLedgerService.isSystemGeneratedEcho(anyString(), anyString())).thenReturn(true);

        IncrementalGitEvent event = IncrementalGitEvent.builder()
                .provider("github")
                .repoUrl("https://github.com/acme/app-mirror.git")
                .ref("refs/heads/main")
                .beforeSha("aaa")
                .afterSha("bbb")
                .deliveryId("d1")
                .eventType("push")
                .build();

        service.handle(event);

        verify(queueConsumerService, never()).consumeSyncEvent(any(), anyString(), anyString());
        ArgumentCaptor<UnmappedWebhookEvent> saved = ArgumentCaptor.forClass(UnmappedWebhookEvent.class);
        verify(unmappedWebhookEventRepository).save(saved.capture());
        assertEquals("LOOP_DETECTED_SYSTEM_ECHO", saved.getValue().getDiscardReason());
    }
}
