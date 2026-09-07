package com.gitutility.service;

import com.gitutility.model.dto.RuntimeMetricsResponse;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.search.Search;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RuntimeMetricsService {

    private static final List<String> EXECUTOR_NAMES = List.of(
            "gitmirror.sync",
            "gitmirror.lfs.discovery",
            "gitmirror.lfs.transfer",
            "gitmirror.pr.create"
    );

    private final MeterRegistry meterRegistry;
    private final HubMetrics hubMetrics;
    private final CircuitBreakerManagerService circuitBreakerManager;
    private final SimulationService simulationService;
    private final InstanceIdentity instanceIdentity;
    private final InstallApiUsageTracker installApiUsageTracker;

    public RuntimeMetricsResponse snapshot() {
        hubMetrics.refreshRuntimeGauges();

        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        long heapMax = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
        double heapPct = heapMax > 0 ? (100.0 * heap.getUsed() / heapMax) : 0.0;

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();

        return RuntimeMetricsResponse.builder()
                .instanceId(instanceIdentity.getInstanceId())
                .capturedAt(Instant.now())
                .jvm(RuntimeMetricsResponse.JvmMemory.builder()
                        .heapUsedBytes(heap.getUsed())
                        .heapMaxBytes(heapMax)
                        .heapUsedPercent(round1(heapPct))
                        .nonHeapUsedBytes(nonHeap.getUsed())
                        .build())
                .threads(RuntimeMetricsResponse.JvmThreads.builder()
                        .live(threads.getThreadCount())
                        .daemon(threads.getDaemonThreadCount())
                        .peak(threads.getPeakThreadCount())
                        .started(threads.getTotalStartedThreadCount())
                        .build())
                .circuitBreaker(RuntimeMetricsResponse.CircuitBreakerSnapshot.builder()
                        .state(circuitBreakerManager.getState().name())
                        .consecutiveFailures(circuitBreakerManager.getCurrentConsecutiveFailures().get())
                        .lastProbeMessage(circuitBreakerManager.getLastProbeMessage())
                        .lastProbeSuccess(circuitBreakerManager.isLastProbeSuccess())
                        .build())
                .consumerPaused(simulationService.isConsumerPaused())
                .executors(snapshotExecutors())
                .lanes(snapshotLanes())
                .jobOutcomes(snapshotJobOutcomes())
                .apiUsageByInstall(installApiUsageTracker.snapshot())
                .actionsCancelsTotal(counterValue("gitmirror.actions.cancels"))
                .build();
    }

    private List<RuntimeMetricsResponse.ExecutorPoolSnapshot> snapshotExecutors() {
        List<RuntimeMetricsResponse.ExecutorPoolSnapshot> list = new ArrayList<>();
        for (String name : EXECUTOR_NAMES) {
            double active = gaugeValue("executor.active", Tags.of("name", name));
            double queued = gaugeValue("executor.queued", Tags.of("name", name));
            double poolSize = gaugeValue("executor.pool.size", Tags.of("name", name));
            double completed = gaugeValue("executor.completed", Tags.of("name", name));
            Double max = optionalGauge("executor.pool.max", Tags.of("name", name));
            if (Double.isNaN(active) && Double.isNaN(poolSize) && Double.isNaN(completed)) {
                continue;
            }
            list.add(RuntimeMetricsResponse.ExecutorPoolSnapshot.builder()
                    .name(name)
                    .active(nanToZero(active))
                    .queued(nanToZero(queued))
                    .poolSize(nanToZero(poolSize))
                    .completed(nanToZero(completed))
                    .maxPoolSize(max != null && !max.isNaN() ? max : null)
                    .build());
        }
        return list;
    }

    private List<RuntimeMetricsResponse.LaneSnapshot> snapshotLanes() {
        List<RuntimeMetricsResponse.LaneSnapshot> lanes = new ArrayList<>();
        lanes.add(lane(SyncLaneRouter.LANE_FULL, SyncLaneRouter.FULL_CONSUMER_ID));
        lanes.add(lane(SyncLaneRouter.LANE_INCREMENTAL, SyncLaneRouter.INCREMENTAL_CONSUMER_ID));
        lanes.add(lane(SyncLaneRouter.LANE_INBOUND, SyncLaneRouter.INBOUND_CONSUMER_ID));
        return lanes;
    }

    private RuntimeMetricsResponse.LaneSnapshot lane(String lane, String listenerId) {
        SimulationService.ListenerHealth health = simulationService.getListenerHealth(listenerId);
        double unacked = gaugeValue("gitmirror.lane.unacked", Tags.of("lane", lane));
        return RuntimeMetricsResponse.LaneSnapshot.builder()
                .lane(lane)
                .unacked((int) nanToZero(unacked))
                .configuredConsumers(health.concurrentConsumers())
                .activeConsumers(health.activeConsumers())
                .running(health.running())
                .build();
    }

    private List<RuntimeMetricsResponse.JobOutcomeSnapshot> snapshotJobOutcomes() {
        Map<String, RuntimeMetricsResponse.JobOutcomeSnapshot> byKey = new LinkedHashMap<>();
        for (Meter meter : meterRegistry.find("gitmirror.sync.jobs").counters()) {
            String lane = tag(meter, "lane");
            String status = tag(meter, "status");
            String key = lane + "|" + status;
            double count = meterRegistry.counter("gitmirror.sync.jobs", "lane", lane, "status", status).count();
            Timer timer = meterRegistry.find("gitmirror.sync.job").tags("lane", lane, "status", status).timer();
            Double mean = timer != null && timer.count() > 0
                    ? timer.mean(TimeUnit.MILLISECONDS)
                    : null;
            byKey.put(key, RuntimeMetricsResponse.JobOutcomeSnapshot.builder()
                    .lane(lane)
                    .status(status)
                    .count(count)
                    .meanDurationMs(mean != null ? round1(mean) : null)
                    .build());
        }
        List<RuntimeMetricsResponse.JobOutcomeSnapshot> list = new ArrayList<>(byKey.values());
        list.sort(Comparator
                .comparing(RuntimeMetricsResponse.JobOutcomeSnapshot::getLane)
                .thenComparing(RuntimeMetricsResponse.JobOutcomeSnapshot::getStatus));
        return list;
    }

    private double counterValue(String name) {
        Search search = meterRegistry.find(name);
        if (search.counter() == null) {
            return 0;
        }
        return search.counter().count();
    }

    private double gaugeValue(String name, Tags tags) {
        Double v = meterRegistry.find(name).tags(tags).gauge() != null
                ? meterRegistry.find(name).tags(tags).gauge().value()
                : Double.NaN;
        return v != null ? v : Double.NaN;
    }

    private Double optionalGauge(String name, Tags tags) {
        var gauge = meterRegistry.find(name).tags(tags).gauge();
        return gauge != null ? gauge.value() : null;
    }

    private static String tag(Meter meter, String key) {
        return meter.getId().getTag(key) != null ? meter.getId().getTag(key) : "unknown";
    }

    private static double nanToZero(double v) {
        return Double.isNaN(v) ? 0 : v;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
