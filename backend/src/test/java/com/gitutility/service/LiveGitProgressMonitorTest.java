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
        assertTrue(audit.isEmpty());
        monitor.update(40);
        monitor.endTask();
        assertTrue(audit.toString().contains("100/100 objects (100%)"));
    }
}
