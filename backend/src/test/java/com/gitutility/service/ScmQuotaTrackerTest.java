package com.gitutility.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScmQuotaTrackerTest {

    private ScmQuotaTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ScmQuotaTracker(new SimpleMeterRegistry());
        tracker.bindGauges();
    }

    @Test
    void installQuotaAndRepoHotspotsAppearInSnapshot() {
        tracker.recordRestQuota("github", "install:42", 4800, 5000, Instant.now().plusSeconds(3600), false);
        tracker.recordGraphqlQuota("github", "install:42", "acme/app", 4900, 5000, 2, false);
        tracker.recordGitTraffic("github", "acme/app", true, true, false);
        tracker.recordGitTraffic("github", "acme/other", true, false, true);

        ScmQuotaTracker.Snapshot snap = tracker.snapshot(10);
        assertEquals(1, snap.getInstallations().size());
        assertEquals("install:42", snap.getInstallations().get(0).getInstallationKey());
        assertEquals(4800, snap.getInstallations().get(0).getRestRemaining());
        assertEquals(2L, snap.getInstallations().get(0).getGraphqlPointsUsed());

        assertFalse(snap.getHottestRepos().isEmpty());
        assertEquals("acme/other", snap.getHottestRepos().get(0).getRepoFullName());
        assertTrue(snap.getHottestRepos().get(0).getHeatScore()
                > snap.getHottestRepos().stream()
                .filter(r -> "acme/app".equals(r.getRepoFullName()))
                .findFirst()
                .orElseThrow()
                .getHeatScore());
    }

    @Test
    void externalRestSuspectWhenRemainingDropsFasterThanAppCalls() {
        Instant reset = Instant.now().plusSeconds(3600);
        tracker.recordRestQuota("github", "install:42", 5000, 5000, reset, false);
        tracker.recordRestQuota("github", "install:42", 4990, 5000, reset, false);

        ScmQuotaTracker.Snapshot snap = tracker.snapshot(10);
        ScmQuotaTracker.InstallQuotaView view = snap.getInstallations().get(0);
        assertTrue(view.getSeries().size() >= 2);
        assertEquals(10L, view.getGithubRestConsumed());
        assertEquals(1L, view.getAppRestConsumed());
        assertEquals(9L, view.getExternalRestSuspect());
    }
}
