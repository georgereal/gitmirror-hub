package com.gitutility.service;

import com.gitutility.model.entity.SyncJob;
import com.gitutility.model.enums.SyncStatus;
import com.gitutility.repository.SyncJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StartupJobRecoveryServiceTest {

    @Mock
    private SimulationService simulationService;

    @Mock
    private SyncJobRepository syncJobRepository;

    @Mock
    private WebSocketNotificationService webSocketNotificationService;

    @Mock
    private JobExecutionStateService jobExecutionStateService;

    @InjectMocks
    private StartupJobRecoveryService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "pauseConsumersOnStartup", true);
        ReflectionTestUtils.setField(service, "orphanJobGraceSeconds", 120);
    }

    @Test
    void marksStaleInProgressJobsAsInterrupted() {
        SyncJob stale = SyncJob.builder()
                .id(1L)
                .status(SyncStatus.IN_PROGRESS)
                .startedAt(Instant.now().minusSeconds(600))
                .build();
        SyncJob fresh = SyncJob.builder()
                .id(2L)
                .status(SyncStatus.IN_PROGRESS)
                .startedAt(Instant.now().minusSeconds(10))
                .build();
        when(syncJobRepository.findByStatus(SyncStatus.IN_PROGRESS)).thenReturn(List.of(stale, fresh));

        int count = service.markOrphanInProgressJobs();

        assertEquals(1, count);
        ArgumentCaptor<SyncJob> captor = ArgumentCaptor.forClass(SyncJob.class);
        verify(syncJobRepository).save(captor.capture());
        assertEquals(SyncStatus.INTERRUPTED, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getErrorMessage());
        verify(webSocketNotificationService).notifyJobUpdated(any(SyncJob.class));
        verify(syncJobRepository, never()).save(fresh);
    }

    @Test
    void onStartupPausesConsumersWhenConfigured() {
        when(syncJobRepository.findByStatus(SyncStatus.IN_PROGRESS)).thenReturn(List.of());

        service.onStartup();

        verify(simulationService).pauseConsumer();
    }
}
