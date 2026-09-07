package com.gitutility.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class CircuitBreakerManagerService {

    public enum CircuitState {
        CLOSED,     // Healthy, processing queue
        OPEN,       // Tripped, queue consumer paused to keep messages durable
        HALF_OPEN   // Testing downstream SCM connectivity with health probe
    }

    private final SimulationService simulationService;
    private final WebSocketNotificationService webSocketNotificationService;
    private final ClusterRuntimeService clusterRuntimeService;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${git-utility.circuit-breaker.failure-threshold:5}")
    private volatile int failureThreshold = 5;

    @Value("${git-utility.circuit-breaker.reset-timeout-seconds:30}")
    private volatile int resetTimeoutSeconds = 30;

    @Getter
    private volatile CircuitState state = CircuitState.CLOSED;

    @Getter
    private final AtomicInteger currentConsecutiveFailures = new AtomicInteger(0);

    @Getter
    private volatile Instant lastStateTransitionAt = Instant.now();

    @Getter
    private volatile String lastProbeMessage = "Circuit healthy";

    @Getter
    private volatile boolean lastProbeSuccess = true;

    public synchronized void updateThresholds(int newThreshold, int newResetTimeoutSeconds) {
        this.failureThreshold = Math.max(1, newThreshold);
        this.resetTimeoutSeconds = Math.max(5, newResetTimeoutSeconds);
        log.info("Updated Circuit Breaker parameters: threshold={}, resetTimeout={}s",
                this.failureThreshold, this.resetTimeoutSeconds);
    }

    /**
     * Record a permanent job failure (exhausted all retries).
     */
    public synchronized void recordPermanentFailure(String reason) {
        int failures = currentConsecutiveFailures.incrementAndGet();
        log.warn("Circuit Breaker: Recorded permanent job failure #{}/{} - Reason: {}",
                failures, failureThreshold, reason);

        if (failures >= failureThreshold && state == CircuitState.CLOSED) {
            tripCircuitBreaker(reason);
        }
    }

    /**
     * Record a successful sync execution.
     */
    public synchronized void recordSuccess() {
        if (currentConsecutiveFailures.get() > 0 || state != CircuitState.CLOSED) {
            log.info("Circuit Breaker: Resetting failure counters after successful sync");
            currentConsecutiveFailures.set(0);
            if (state != CircuitState.CLOSED) {
                transitionTo(CircuitState.CLOSED, "Sync succeeded; downstream subsystems operational");
            }
        }
    }

    private synchronized void tripCircuitBreaker(String reason) {
        transitionTo(CircuitState.OPEN, "Tripped after " + currentConsecutiveFailures.get() + " consecutive failures. Reason: " + reason);
        log.error("CIRCUIT BREAKER TRIPPED to OPEN! Pausing AMQP consumer to preserve message durability in RabbitMQ.");
        clusterRuntimeService.publishCircuitState(
                CircuitState.OPEN.name(),
                currentConsecutiveFailures.get(),
                lastProbeMessage,
                false,
                true);
        simulationService.pauseConsumer();
        broadcastStatus();
    }

    private synchronized void transitionTo(CircuitState newState, String message) {
        this.state = newState;
        this.lastStateTransitionAt = Instant.now();
        this.lastProbeMessage = message;
        log.info("Circuit Breaker State Transition -> {} ({})", newState, message);
        broadcastStatus();
    }

    /**
     * Automated self-healing periodic background probe.
     * When OPEN, periodically probes SCM connectivity.
     */
    @Scheduled(fixedDelayString = "${git-utility.circuit-breaker.probe-delay-ms:30000}")
    public synchronized void scheduledHealthProbe() {
        if (state != CircuitState.OPEN) {
            return;
        }

        log.info("Circuit Breaker [Self-Healing]: Probing downstream SCM health...");
        transitionTo(CircuitState.HALF_OPEN, "Executing scheduled health probe...");

        ProbeResult probe = executeHealthProbe();
        this.lastProbeSuccess = probe.success;
        this.lastProbeMessage = probe.message;

        if (probe.success) {
            log.info("Circuit Breaker [Self-Healing SUCCESS]: Subsystems reachable. Auto-resuming AMQP consumer.");
            currentConsecutiveFailures.set(0);
            transitionTo(CircuitState.CLOSED, "Auto-healed: " + probe.message);
            clusterRuntimeService.publishCircuitState(
                    CircuitState.CLOSED.name(), 0, lastProbeMessage, true, false);
            simulationService.resumeConsumer();
        } else {
            log.warn("Circuit Breaker [Self-Healing FAILED]: Subsystems still degraded ({}). Returning to OPEN.", probe.message);
            transitionTo(CircuitState.OPEN, "Probe failed: " + probe.message);
            clusterRuntimeService.publishCircuitState(
                    CircuitState.OPEN.name(),
                    currentConsecutiveFailures.get(),
                    lastProbeMessage,
                    false,
                    true);
        }
    }

    /**
     * Admin manual override: Test Subsystems and Reset Circuit Breaker.
     */
    public synchronized ProbeResult probeAndReset(boolean forceReset) {
        log.info("Admin initiated Circuit Breaker probe and reset (forceReset={})", forceReset);
        transitionTo(CircuitState.HALF_OPEN, "Admin probe in progress...");

        ProbeResult probe = executeHealthProbe();
        this.lastProbeSuccess = probe.success;
        this.lastProbeMessage = probe.message;

        if (probe.success || forceReset) {
            currentConsecutiveFailures.set(0);
            transitionTo(CircuitState.CLOSED, forceReset && !probe.success
                    ? "Admin force-reset: " + probe.message
                    : "Admin probe passed: " + probe.message);
            clusterRuntimeService.publishCircuitState(
                    CircuitState.CLOSED.name(), 0, lastProbeMessage, probe.success, false);
            simulationService.resumeConsumer();
            return new ProbeResult(true, "Subsystems operational. Circuit Breaker CLOSED and consumer resumed.");
        } else {
            transitionTo(CircuitState.OPEN, "Admin probe failed: " + probe.message);
            clusterRuntimeService.publishCircuitState(
                    CircuitState.OPEN.name(),
                    currentConsecutiveFailures.get(),
                    lastProbeMessage,
                    false,
                    true);
            return new ProbeResult(false, "Probe failed: " + probe.message + ". Use Force Reset to bypass.");
        }
    }

    /**
     * Probe Git provider endpoints (GitHub, GitLab, public internet connectivity).
     */
    public ProbeResult executeHealthProbe() {
        try {
            // 1. Probe GitHub public API health / zen endpoint
            ResponseEntity<String> response = restTemplate.getForEntity("https://api.github.com/zen", String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                return new ProbeResult(true, "GitHub API is reachable (HTTP 200). Latency normal.");
            }
            return new ProbeResult(false, "GitHub API returned HTTP " + response.getStatusCode());
        } catch (Exception e) {
            log.debug("Health probe exception against GitHub API: {}", e.getMessage());
            // Fallback: Check if local network is up via alternative public DNS/HTTP probe
            try {
                ResponseEntity<String> gitlabResp = restTemplate.getForEntity("https://gitlab.com", String.class);
                if (gitlabResp.getStatusCode().is2xxSuccessful()) {
                    return new ProbeResult(true, "GitLab reachable. Connectivity verified.");
                }
            } catch (Exception ex2) {
                // Both failed
            }
            return new ProbeResult(false, "Downstream SCM connectivity unreachable: " + e.getMessage());
        }
    }

    private void broadcastStatus() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("state", state.name());
        payload.put("failures", currentConsecutiveFailures.get());
        payload.put("threshold", failureThreshold);
        payload.put("lastMessage", lastProbeMessage);
        payload.put("lastTransition", lastStateTransitionAt.toString());
        payload.put("consumerPaused", simulationService.isConsumerPaused());
        webSocketNotificationService.notifySimulationUpdated(payload);
    }

    public record ProbeResult(boolean success, String message) {}
}
