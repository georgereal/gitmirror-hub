package com.gitutility.messaging.none;

import com.gitutility.messaging.MessagingConditions;
import com.gitutility.messaging.SyncEventBus;
import com.gitutility.model.dto.SyncEventMessage;
import com.gitutility.service.QueueConsumerService;
import com.gitutility.service.SimulationService;
import com.gitutility.service.SyncLaneRouter;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
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

    private final QueueConsumerService queueConsumerService;
    private final SimulationService simulationService;
    private final LinkedBlockingQueue<SyncEventMessage> deferred = new LinkedBlockingQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "none-messaging-worker");
        t.setDaemon(true);
        return t;
    });

    public NoneSyncEventBus(@Lazy QueueConsumerService queueConsumerService,
                            @Lazy SimulationService simulationService) {
        this.queueConsumerService = queueConsumerService;
        this.simulationService = simulationService;
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
