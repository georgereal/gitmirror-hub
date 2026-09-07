package com.gitutility.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process view of AMQP listener threads currently holding an unacked message.
 * Ready counts still come from RabbitAdmin; unacked for this JVM is {@link #unackedCount(String)}.
 */
@Service
public class ConsumerRuntimeRegistry {

    public enum SlotState {
        PROCESSING,
        SKIPPING
    }

    public static final class Slot {
        private final String lane;
        private final String listenerId;
        private final Thread thread;
        private final long threadId;
        private final String threadName;
        private final Long jobId;
        private final String pairName;
        private final String ref;
        private final Instant startedAt;
        private volatile SlotState state;

        private Slot(String lane, String listenerId, Thread thread, Long jobId, String pairName, String ref, SlotState state) {
            this.lane = lane;
            this.listenerId = listenerId;
            this.thread = thread;
            this.threadId = thread.threadId();
            this.threadName = thread.getName();
            this.jobId = jobId;
            this.pairName = pairName;
            this.ref = ref;
            this.startedAt = Instant.now();
            this.state = state;
        }

        public String getLane() {
            return lane;
        }

        public String getListenerId() {
            return listenerId;
        }

        public String getThreadName() {
            return threadName;
        }

        public long getThreadId() {
            return threadId;
        }

        public boolean isThreadAlive() {
            return thread != null && thread.isAlive();
        }

        public Long getJobId() {
            return jobId;
        }

        public String getPairName() {
            return pairName;
        }

        public String getRef() {
            return ref;
        }

        public Instant getStartedAt() {
            return startedAt;
        }

        public SlotState getState() {
            return state;
        }

        public long elapsedMs() {
            return Math.max(0, Duration.between(startedAt, Instant.now()).toMillis());
        }
    }

    private final ConcurrentHashMap<Long, Slot> slotsByThread = new ConcurrentHashMap<>();

    public Slot bind(String lane, String listenerId, Long jobId, String pairName, String ref) {
        return bind(lane, listenerId, jobId, pairName, ref, SlotState.PROCESSING);
    }

    public Slot bind(String lane, String listenerId, Long jobId, String pairName, String ref, SlotState state) {
        Thread thread = Thread.currentThread();
        Slot slot = new Slot(lane, listenerId, thread, jobId, pairName, ref, state);
        slotsByThread.put(thread.threadId(), slot);
        return slot;
    }

    public void markSkipping() {
        Slot slot = slotsByThread.get(Thread.currentThread().threadId());
        if (slot != null) {
            slot.state = SlotState.SKIPPING;
        }
    }

    public void unbind() {
        slotsByThread.remove(Thread.currentThread().threadId());
    }

    public int unackedCount(String lane) {
        if (lane == null) {
            return slotsByThread.size();
        }
        return (int) slotsByThread.values().stream().filter(s -> lane.equals(s.lane)).count();
    }

    public List<Slot> slotsForLane(String lane) {
        List<Slot> out = new ArrayList<>();
        for (Slot slot : slotsByThread.values()) {
            if (lane == null || lane.equals(slot.lane)) {
                out.add(slot);
            }
        }
        return out;
    }

    public List<Slot> allSlots() {
        return new ArrayList<>(slotsByThread.values());
    }
}
