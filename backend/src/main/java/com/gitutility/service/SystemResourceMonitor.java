package com.gitutility.service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;

/**
 * Live CPU / heap / disk headroom probe used by {@link PushBatchConcurrencyService} to
 * collapse push-batch fan-out when the pod is under pressure. Every probe returns {@code -1}
 * when the value is unknown so callers can fail open instead of stalling syncs.
 */
public class SystemResourceMonitor {

    private final File workspaceDir;

    public SystemResourceMonitor(String workspaceDir) {
        this.workspaceDir = workspaceDir == null || workspaceDir.isBlank() ? null : new File(workspaceDir);
    }

    /** Normalized system load average (load / cores), {@code 0..1}, or {@code -1} when unknown. */
    public double cpuLoad() {
        try {
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            double load = os.getSystemLoadAverage();
            int cores = os.getAvailableProcessors();
            if (load < 0 || cores <= 0) {
                return -1;
            }
            return load / cores;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Heap used percent {@code 0..100}, or {@code -1} when the max heap is unbounded/unknown. */
    public double heapUsedPercent() {
        try {
            MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
            MemoryUsage heap = memory.getHeapMemoryUsage();
            long max = heap.getMax();
            if (max <= 0) {
                return -1;
            }
            return 100.0 * heap.getUsed() / max;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Free disk percent {@code 0..100} on the workspace volume, or {@code -1} when unknown. */
    public double diskFreePercent() {
        try {
            File probe = workspaceDir;
            if (probe == null) {
                return -1;
            }
            if (!probe.exists()) {
                File parent = probe.getParentFile();
                probe = parent != null && parent.exists() ? parent : new File(".").getAbsoluteFile();
            }
            long total = probe.getTotalSpace();
            if (total <= 0) {
                return -1;
            }
            return 100.0 * probe.getUsableSpace() / total;
        } catch (Exception e) {
            return -1;
        }
    }
}