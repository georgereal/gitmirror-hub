package com.gitutility.service;

import com.gitutility.service.CircuitBreakerManagerService.CircuitState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Low-cardinality Hub process metrics for the Internals observability screen.
 * Job-scoped detail stays in {@link ProviderRateMeter} / audit logs.
 */
@Component
@RequiredArgsConstructor
public class HubMetrics {

    private final MeterRegistry meterRegistry;
    private final CircuitBreakerManagerService circuitBreakerManager;
    private final SimulationService simulationService;
    private final ConsumerRuntimeRegistry consumerRuntimeRegistry;

    private final AtomicInteger fullUnacked = new AtomicInteger();
    private final AtomicInteger incrementalUnacked = new AtomicInteger();
    private final AtomicInteger inboundUnacked = new AtomicInteger();
    private final AtomicBoolean consumerPaused = new AtomicBoolean();
    private final AtomicReference<String> circuitStateName = new AtomicReference<>("CLOSED");

    private Counter actionsCancels;

    @PostConstruct
    void bindGauges() {
        actionsCancels = Counter.builder("gitmirror.actions.cancels")
                .description("Workflow runs cancelled by mirror Actions suppression")
                .register(meterRegistry);

        Gauge.builder("gitmirror.circuit_breaker.state", this, HubMetrics::circuitStateOrdinal)
                .description("0=CLOSED 1=HALF_OPEN 2=OPEN")
                .register(meterRegistry);
        Gauge.builder("gitmirror.circuit_breaker.failures", circuitBreakerManager,
                        cb -> cb.getCurrentConsecutiveFailures().get())
                .description("Consecutive permanent failures toward trip threshold")
                .register(meterRegistry);
        Gauge.builder("gitmirror.consumer.paused", consumerPaused, v -> v.get() ? 1.0 : 0.0)
                .description("1 when sync consumers are paused")
                .register(meterRegistry);
        Gauge.builder("gitmirror.lane.unacked", fullUnacked, AtomicInteger::get)
                .tag("lane", SyncLaneRouter.LANE_FULL)
                .register(meterRegistry);
        Gauge.builder("gitmirror.lane.unacked", incrementalUnacked, AtomicInteger::get)
                .tag("lane", SyncLaneRouter.LANE_INCREMENTAL)
                .register(meterRegistry);
        Gauge.builder("gitmirror.lane.unacked", inboundUnacked, AtomicInteger::get)
                .tag("lane", SyncLaneRouter.LANE_INBOUND)
                .register(meterRegistry);
    }

    public void refreshRuntimeGauges() {
        circuitStateName.set(circuitBreakerManager.getState().name());
        consumerPaused.set(simulationService.isConsumerPaused());
        fullUnacked.set(consumerRuntimeRegistry.slotsForLane(SyncLaneRouter.LANE_FULL).size());
        incrementalUnacked.set(consumerRuntimeRegistry.slotsForLane(SyncLaneRouter.LANE_INCREMENTAL).size());
        inboundUnacked.set(consumerRuntimeRegistry.slotsForLane(SyncLaneRouter.LANE_INBOUND).size());
    }

    public void recordJobOutcome(String lane, String status, long durationMs) {
        String safeLane = lane == null || lane.isBlank() ? "unknown" : lane;
        String safeStatus = status == null || status.isBlank() ? "unknown" : status;
        Timer.builder("gitmirror.sync.job")
                .description("Sync job wall duration by lane and terminal status")
                .tag("lane", safeLane)
                .tag("status", safeStatus)
                .register(meterRegistry)
                .record(Math.max(0L, durationMs), TimeUnit.MILLISECONDS);
        Counter.builder("gitmirror.sync.jobs")
                .description("Sync jobs completed by lane and status")
                .tag("lane", safeLane)
                .tag("status", safeStatus)
                .register(meterRegistry)
                .increment();
    }

    public void recordActionsCancelled(int count) {
        if (count > 0 && actionsCancels != null) {
            actionsCancels.increment(count);
        }
    }

    private double circuitStateOrdinal() {
        CircuitState state = circuitBreakerManager.getState();
        return switch (state) {
            case CLOSED -> 0;
            case HALF_OPEN -> 1;
            case OPEN -> 2;
        };
    }
}
