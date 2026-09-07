package com.gitutility.service;

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
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueProducerServiceTest {

    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private SyncJobRepository syncJobRepository;
    @Mock
    private WebSocketNotificationService webSocketNotificationService;

    private QueueProducerService producer;

    @BeforeEach
    void setUp() {
        producer = new QueueProducerService(rabbitTemplate, syncJobRepository, webSocketNotificationService);
        ReflectionTestUtils.setField(producer, "exchangeName", "git.sync.exchange");
        ReflectionTestUtils.setField(producer, "fullRoutingKey", "git.sync.key");
        ReflectionTestUtils.setField(producer, "incrementalRoutingKey", "git.sync.incremental.key");
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void webhookWithSpecificRefGoesToIncrementalLane() {
        enqueue("refs/heads/main", "main", TriggerType.WEBHOOK);
        verifyPublished("git.sync.incremental.key");
    }

    @Test
    void fullMirrorGoesToFullLane() {
        enqueue(null, "*", TriggerType.MANUAL);
        verifyPublished("git.sync.key");
    }

    @Test
    void overwriteFlagIsPublishedOnMessage() {
        RepoMapping mapping = RepoMapping.builder().id(1L).name("pair").build();
        SyncJob job = SyncJob.builder().id(9L).mappingId(1L).build();
        producer.enqueueSyncJob(mapping, job, "https://src.git", "https://dst.git",
                "refs/heads/main", "main", null, "abc", "msg", "author", TriggerType.MANUAL, true);

        ArgumentCaptor<SyncEventMessage> msgCaptor = ArgumentCaptor.forClass(SyncEventMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq("git.sync.exchange"),
                eq("git.sync.incremental.key"),
                msgCaptor.capture(),
                any(MessagePostProcessor.class)
        );
        assertTrue(msgCaptor.getValue().isOverwriteFromSource());
    }

    private void enqueue(String ref, String branch, TriggerType triggerType) {
        RepoMapping mapping = RepoMapping.builder().id(1L).name("pair").build();
        SyncJob job = SyncJob.builder().id(9L).mappingId(1L).build();
        producer.enqueueSyncJob(mapping, job, "https://src.git", "https://dst.git",
                ref, branch, null, "abc", "msg", "author", triggerType);
    }

    private void verifyPublished(String routingKey) {
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate).convertAndSend(
                eq("git.sync.exchange"),
                keyCaptor.capture(),
                any(SyncEventMessage.class),
                any(MessagePostProcessor.class)
        );
        assertEquals(routingKey, keyCaptor.getValue());
    }
}
