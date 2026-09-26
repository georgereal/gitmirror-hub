package com.gitutility.messaging.webhook;

import com.gitutility.model.dto.IncrementalGitEvent;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.UnmappedWebhookEvent;
import com.gitutility.model.enums.SyncDirection;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.SyncJobRepository;
import com.gitutility.repository.UnmappedWebhookEventRepository;
import com.gitutility.service.PairLeaseService;
import com.gitutility.service.PairTipEchoService;
import com.gitutility.service.QueueConsumerService;
import com.gitutility.service.RefInterestPolicy;
import com.gitutility.service.RefOriginService;
import com.gitutility.service.UnmappedWebhookRetention;
import com.gitutility.service.WebSocketNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    @Mock UnmappedWebhookRetention unmappedWebhookRetention;
    @Mock RefOriginService refOriginService;
    @Mock RefInterestPolicy refInterestPolicy;
    @Mock QueueConsumerService queueConsumerService;
    @Mock PairLeaseService pairLeaseService;
    @Mock WebhookEventPublisher publisher;
    @Mock WebSocketNotificationService webSocketNotificationService;
    @Mock PairTipEchoService pairTipEchoService;

    private WebhookIncrementalService service;

    @BeforeEach
    void setUp() {
        service = new WebhookIncrementalService(
                mappingRepository,
                syncJobRepository,
                unmappedWebhookEventRepository,
                unmappedWebhookRetention,
                refOriginService,
                refInterestPolicy,
                queueConsumerService,
                pairLeaseService,
                publisher,
                webSocketNotificationService);
        service.setPairTipEchoService(pairTipEchoService);
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
        when(pairTipEchoService.pushEcho(any(), anyBoolean(), anyString(), anyString())).thenReturn(true);

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

    @Test
    void newShaOnMirrorMainIsSyncedEvenWhenTheBranchOriginatedOnTheOtherSide() throws Exception {
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
        when(refInterestPolicy.tryAcceptIncrementalEnqueue(anyString(), anyString())).thenReturn(true);
        when(pairTipEchoService.pushEcho(any(), anyBoolean(), anyString(), anyString())).thenReturn(false);
        when(syncJobRepository.save(any())).thenAnswer(invocation -> {
            com.gitutility.model.entity.SyncJob job = invocation.getArgument(0);
            job.setId("job-1");
            return job;
        });

        IncrementalGitEvent event = IncrementalGitEvent.builder()
                .provider("github")
                .repoUrl("https://github.com/acme/app-mirror.git")
                .ref("refs/heads/main")
                .beforeSha("7bceb28")
                .afterSha("05b3b4d30c2a8164e70f4379d2be541f3a657e8c")
                .deliveryId("d2")
                .eventType("push")
                .build();

        service.handle(event);

        ArgumentCaptor<com.gitutility.model.dto.SyncEventMessage> message =
                ArgumentCaptor.forClass(com.gitutility.model.dto.SyncEventMessage.class);
        verify(queueConsumerService).consumeSyncEvent(message.capture(), anyString(), anyString());
        assertEquals("https://github.com/acme/app-mirror.git", message.getValue().getSourceRepoUrl());
        assertEquals("https://github.com/acme/app.git", message.getValue().getTargetRepoUrl());
        assertEquals("05b3b4d30c2a8164e70f4379d2be541f3a657e8c", message.getValue().getAfterSha());
        verify(unmappedWebhookEventRepository, never()).save(any());
    }

    @Test
    void deleteIsNotAnEchoWhileTheOtherSideStillHasTheBranch() throws Exception {
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
        when(refInterestPolicy.tryAcceptIncrementalEnqueue(anyString(), anyString())).thenReturn(true);
        when(pairTipEchoService.deleteEcho(any(), anyBoolean(), anyString())).thenReturn(false);
        when(syncJobRepository.save(any())).thenAnswer(invocation -> {
            com.gitutility.model.entity.SyncJob job = invocation.getArgument(0);
            job.setId("job-del");
            return job;
        });

        IncrementalGitEvent event = IncrementalGitEvent.builder()
                .provider("github")
                .repoUrl("https://github.com/acme/app-mirror.git")
                .ref("refs/heads/test/sync-probe-1")
                .beforeSha("c66a7d0")
                .afterSha("0000000000000000000000000000000000000000")
                .deliveryId("d3")
                .eventType("delete")
                .build();

        service.handle(event);

        verify(queueConsumerService).consumeSyncEvent(any(), anyString(), anyString());
        verify(unmappedWebhookEventRepository, never()).save(any());
    }

    @Test
    void echoLooksAtThePeerTipNotAStoredMarker() {
        assertTrue(PairTipEchoService.pushIsEcho(true, "abc", "ABC"));
        assertFalse(PairTipEchoService.pushIsEcho(false, "abc", "abc"));
        assertFalse(PairTipEchoService.pushIsEcho(true, "old", "new"));
        assertTrue(PairTipEchoService.deleteIsEcho(true, false));
        assertFalse(PairTipEchoService.deleteIsEcho(true, true));
        assertFalse(PairTipEchoService.deleteIsEcho(false, false));
    }
}
