package com.gitutility.messaging.none;

import com.gitutility.messaging.MessagingConditions;
import com.gitutility.messaging.SyncEventBus;
import com.gitutility.model.dto.SyncEventMessage;
import com.gitutility.service.QueueConsumerService;
import com.gitutility.service.SimulationService;
import com.gitutility.service.SyncLaneRouter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process sync bus when {@code GIT_MESSAGING_PROVIDER=none}. No external broker.
 */
@Component
@MessagingConditions.OnNone
@Slf4j
public class NoneSyncEventBus implements SyncEventBus {

    public static final String METRIC_NAME = "gitmirror.messaging.none";

    private final QueueConsumerService queueConsumerService;
    private final SimulationService simulationService;
    private final LinkedBlockingQueue<SyncEventMessage> deferred = new LinkedBlockingQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final int workerThreads;
    private final ExecutorService executor;

    public NoneSyncEventBus(@Lazy QueueConsumerService queueConsumerService,
                            @Lazy SimulationService simulationService,
                            MeterRegistry meterRegistry,
                            @Value("${git-utility.messaging.none.worker-threads:8}") int workerThreads) {
        this.queueConsumerService = queueConsumerService;
        this.simulationService = simulationService;
        this.workerThreads = Math.max(1, workerThreads);
        ExecutorService raw = Executors.newFixedThreadPool(this.workerThreads, r -> {
            Thread t = new Thread(r, "none-messaging-worker");
            t.setDaemon(true);
            return t;
        });
        this.executor = ExecutorServiceMetrics.monitor(meterRegistry, raw, METRIC_NAME);
        Gauge.builder("gitmirror.messaging.none.pending", deferred, LinkedBlockingQueue::size)
                .description("Deferred sync jobs while consumers are paused (none messaging)")
                .register(meterRegistry);
        Gauge.builder("gitmirror.messaging.none.inflight", inFlight, AtomicInteger::get)
                .description("In-flight sync jobs on none-messaging workers")
                .register(meterRegistry);
        log.info("Messaging none: {} in-process worker thread(s)", this.workerThreads);
    }

    public int workerThreads() {
        return workerThreads;
    }

    @Override
    public void publish(SyncEventMessage message) {
        dispatch(message, false);
    }

    @Override
    public void republish(SyncEventMessage message) {
        dispatch(message, true);
    }

    private void dispatch(SyncEventMessage message, boolean republish) {
        if (message == null || message.getJobId() == null) {
            return;
        }
        String lane = SyncLaneRouter.lane(message.getRef(), message.getBranch());
        if (simulationService.isConsumerPaused()) {
            deferred.offer(message);
            log.info("Messaging none: deferred Job #{} on lane {} (consumers paused); pending={}",
                    message.getJobId(), lane, deferred.size());
            return;
        }
        submit(message, republish, lane);
    }

    private void submit(SyncEventMessage message, boolean republish, String lane) {
        log.info("Messaging none: {} Job #{} on lane {} [{}]",
                republish ? "re-dispatching" : "dispatching",
                message.getJobId(), lane, message.getPairName());
        executor.execute(() -> {
            inFlight.incrementAndGet();
            try {
                if (simulationService.isConsumerPaused()) {
                    deferred.offer(message);
                    return;
                }
                queueConsumerService.consumeSyncEvent(message);
            } catch (Exception e) {
                log.error("In-process sync failed for Job #{}: {}", message.getJobId(), e.getMessage(), e);
            } finally {
                inFlight.decrementAndGet();
            }
        });
    }

    @Override
    public void onConsumersResumed() {
        List<SyncEventMessage> batch = new ArrayList<>();
        deferred.drainTo(batch);
        if (batch.isEmpty()) {
            return;
        }
        log.info("Messaging none: draining {} deferred job(s) after consumer resume", batch.size());
        for (SyncEventMessage message : batch) {
            submit(message, true, SyncLaneRouter.lane(message.getRef(), message.getBranch()));
        }
    }

    public int pendingCount() {
        return deferred.size();
    }

    public int inFlightCount() {
        return inFlight.get();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
