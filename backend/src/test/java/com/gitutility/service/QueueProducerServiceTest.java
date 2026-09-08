package com.gitutility.service;

import com.gitutility.messaging.SyncEventBus;
import com.gitutility.model.dto.SyncEventMessage;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.TriggerType;
import com.gitutility.repository.SyncJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueProducerServiceTest {

    @Mock
    private SyncEventBus syncEventBus;
    @Mock
    private SyncJobRepository syncJobRepository;
    @Mock
    private WebSocketNotificationService webSocketNotificationService;

    private QueueProducerService producer;

    @BeforeEach
    void setUp() {
        producer = new QueueProducerService(syncEventBus, syncJobRepository, webSocketNotificationService);
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void webhookWithSpecificRefGoesToIncrementalLane() {
        enqueue("refs/heads/main", "main", TriggerType.WEBHOOK);
        SyncEventMessage msg = capturePublished();
        assertEquals("refs/heads/main", msg.getRef());
        assertEquals("main", msg.getBranch());
    }

    @Test
    void fullMirrorGoesToFullLane() {
        enqueue(null, "*", TriggerType.MANUAL);
        SyncEventMessage msg = capturePublished();
        assertEquals("*", msg.getBranch());
        assertTrue(SyncLaneRouter.isFullMirror(msg));
    }

    @Test
    void overwriteFlagIsPublishedOnMessage() {
        RepoMapping mapping = RepoMapping.builder().id(1L).name("pair").build();
        SyncJob job = SyncJob.builder().id(9L).mappingId(1L).build();
        producer.enqueueSyncJob(mapping, job, "https://src.git", "https://dst.git",
                "refs/heads/main", "main", null, "abc", "msg", "author", TriggerType.MANUAL, true);

        assertTrue(capturePublished().isOverwriteFromSource());
    }

    private void enqueue(String ref, String branch, TriggerType triggerType) {
        RepoMapping mapping = RepoMapping.builder().id(1L).name("pair").build();
        SyncJob job = SyncJob.builder().id(9L).mappingId(1L).build();
        producer.enqueueSyncJob(mapping, job, "https://src.git", "https://dst.git",
                ref, branch, null, "abc", "msg", "author", triggerType);
    }

    private SyncEventMessage capturePublished() {
        ArgumentCaptor<SyncEventMessage> msgCaptor = ArgumentCaptor.forClass(SyncEventMessage.class);
        verify(syncEventBus).publish(msgCaptor.capture());
        return msgCaptor.getValue();
    }
}
