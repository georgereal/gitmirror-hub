package com.gitutility.service;

import com.gitutility.model.entity.ClusterRuntime;
import com.gitutility.repository.ClusterRuntimeRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Shared pause / circuit-breaker desired state across Hub pods.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClusterRuntimeService {

    private final ClusterRuntimeRepository clusterRuntimeRepository;
    private final InstanceIdentity instanceIdentity;
    private final SimulationService simulationService;

    @PostConstruct
    @Transactional
    public void ensureSingleton() {
        if (!clusterRuntimeRepository.existsById(ClusterRuntime.SINGLETON_ID)) {
            clusterRuntimeRepository.save(ClusterRuntime.builder()
                    .id(ClusterRuntime.SINGLETON_ID)
                    .consumersPaused(false)
                    .circuitState("CLOSED")
                    .consecutiveFailures(0)
                    .lastProbeMessage("Cluster runtime initialized")
                    .lastProbeSuccess(true)
                    .updatedByInstance(instanceIdentity.getInstanceId())
                    .updatedAt(Instant.now())
                    .build());
        }
    }

    @Transactional(readOnly = true)
    public ClusterRuntime getOrCreate() {
        return clusterRuntimeRepository.findById(ClusterRuntime.SINGLETON_ID).orElseGet(() -> {
            ClusterRuntime created = ClusterRuntime.builder()
                    .id(ClusterRuntime.SINGLETON_ID)
                    .consumersPaused(false)
                    .circuitState("CLOSED")
                    .build();
            return clusterRuntimeRepository.save(created);
        });
    }

    @Transactional
    public void setConsumersPaused(boolean paused) {
        ClusterRuntime runtime = getOrCreate();
        runtime.setConsumersPaused(paused);
        runtime.setUpdatedByInstance(instanceIdentity.getInstanceId());
        runtime.setUpdatedAt(Instant.now());
        clusterRuntimeRepository.save(runtime);
    }

    @Transactional
    public void publishCircuitState(String state,
                                    int consecutiveFailures,
                                    String probeMessage,
                                    boolean probeSuccess,
                                    boolean pauseConsumers) {
        ClusterRuntime runtime = getOrCreate();
        runtime.setCircuitState(state == null ? "CLOSED" : state);
        runtime.setConsecutiveFailures(Math.max(0, consecutiveFailures));
        runtime.setLastProbeMessage(probeMessage);
        runtime.setLastProbeSuccess(probeSuccess);
        runtime.setConsumersPaused(pauseConsumers);
        runtime.setUpdatedByInstance(instanceIdentity.getInstanceId());
        runtime.setUpdatedAt(Instant.now());
        clusterRuntimeRepository.save(runtime);
    }

    /**
     * Apply shared pause flag to local Rabbit listeners.
     */
    @Scheduled(fixedDelayString = "${git-utility.cluster.control-poll-ms:2000}")
    public void applySharedControls() {
        try {
            ClusterRuntime runtime = getOrCreate();
            boolean desiredPaused = runtime.isConsumersPaused();
            boolean localPaused = simulationService.isConsumerPaused();
            if (desiredPaused && !localPaused) {
                log.info("Cluster control: pausing local consumers (set by {})", runtime.getUpdatedByInstance());
                simulationService.pauseConsumerLocal();
            } else if (!desiredPaused && localPaused && !simulationService.isStartupHold()) {
                log.info("Cluster control: resuming local consumers (set by {})", runtime.getUpdatedByInstance());
                simulationService.resumeConsumerLocal();
            }
        } catch (Exception e) {
            log.debug("Cluster control poll notice: {}", e.getMessage());
        }
    }
}
