package com.gitutility.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LiveGitProgressMonitorTest {

    @Test
    void endTaskWritesCompletionMessageToAuditConsumer() {
        StringBuilder audit = new StringBuilder();
        LiveGitProgressMonitor monitor = new LiveGitProgressMonitor(
                1L, 2L, "push",
                null,
                (phase, msg) -> audit.append(msg)
        );
        monitor.beginTask("Writing objects", 100);
        // beginTask notifies the audit consumer so long advertise/negotiate gaps are not silent
        assertTrue(audit.toString().contains("0/100 objects (0%)"));
        monitor.update(40); // ticks do not write audit rows
        assertFalse(audit.toString().contains("40/100"));
        monitor.endTask();
        assertTrue(audit.toString().contains("100/100 objects (100%)"));
    }
}
