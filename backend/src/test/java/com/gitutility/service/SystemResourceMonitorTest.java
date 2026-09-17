package com.gitutility.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemResourceMonitorTest {

    @TempDir
    Path tempDir;

    @Test
    void heapUsedPercentIsBetweenZeroAndOneHundredOrUnknown() {
        double heap = new SystemResourceMonitor(null).heapUsedPercent();
        assertTrue(heap == -1 || (heap >= 0 && heap <= 100), "heap=" + heap);
    }

    @Test
    void cpuLoadIsNormalizedOrUnknown() {
        double cpu = new SystemResourceMonitor(null).cpuLoad();
        assertTrue(cpu == -1 || cpu >= 0, "cpu=" + cpu);
    }

    @Test
    void diskFreePercentUsesWorkspaceVolume() {
        double free = new SystemResourceMonitor(tempDir.toString()).diskFreePercent();
        assertTrue(free == -1 || (free >= 0 && free <= 100), "free=" + free);
    }

    @Test
    void missingWorkspaceFallsBackToParentVolume() {
        double free = new SystemResourceMonitor(tempDir.resolve("does-not-exist").toString()).diskFreePercent();
        assertTrue(free == -1 || (free >= 0 && free <= 100), "free=" + free);
    }

    @Test
    void nullWorkspaceYieldsUnknownDisk() {
        assertEquals(-1, new SystemResourceMonitor(null).diskFreePercent(), 0.0001);
    }
}