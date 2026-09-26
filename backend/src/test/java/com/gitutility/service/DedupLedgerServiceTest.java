package com.gitutility.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DedupLedgerServiceTest {

    private DedupLedgerService dedupLedgerService;

    @BeforeEach
    void setUp() {
        dedupLedgerService = new DedupLedgerService();
    }

    @Test
    void testRecordAndDetectEchoLoop() {
        String repoUrl = "https://github.com/my-org/core-service.git";
        String commitSha = "a1b2c3d4e5f6";

        // Initially not recorded
        assertFalse(dedupLedgerService.isSystemGeneratedEcho(repoUrl, commitSha));

        // Record automated push
        dedupLedgerService.recordSystemPush(repoUrl, commitSha);

        // Subsequent check within TTL must identify it as loop/echo
        assertTrue(dedupLedgerService.isSystemGeneratedEcho(repoUrl, commitSha));
        assertTrue(dedupLedgerService.isSystemGeneratedEcho("https://github.com/my-org/core-service", commitSha),
                "Should match regardless of .git suffix");

        // Different commit or different repo should not match
        assertFalse(dedupLedgerService.isSystemGeneratedEcho(repoUrl, "differentSha"));
        assertFalse(dedupLedgerService.isSystemGeneratedEcho("https://github.com/other-org/other-service.git", commitSha));
    }

    @Test
    void testMultipleBranchTipsAreTrackedIndependently() {
        String dest = "https://github.com/acme/mirror-dest";
        dedupLedgerService.recordSystemPush(dest, "aaa111");
        dedupLedgerService.recordSystemPush(dest, "bbb222");

        assertTrue(dedupLedgerService.isSystemGeneratedEcho(dest + ".git", "aaa111"));
        assertTrue(dedupLedgerService.isSystemGeneratedEcho("https://github.com/acme/mirror-dest.git", "bbb222"));
        assertFalse(dedupLedgerService.isSystemGeneratedEcho(dest, "ccc333"));
    }

    @Test
    void refDeleteEchoIsTrackedIndependentlyOfSha() {
        String dest = "https://github.com/acme/mirror-dest.git";
        assertFalse(dedupLedgerService.isSystemGeneratedRefDelete(dest, "refs/heads/add-repocloud-deploy-button"));
        dedupLedgerService.recordSystemRefDelete(dest, "refs/heads/add-repocloud-deploy-button");
        assertTrue(dedupLedgerService.isSystemGeneratedRefDelete(
                "https://github.com/acme/mirror-dest", "add-repocloud-deploy-button"));
        assertFalse(dedupLedgerService.isSystemGeneratedRefDelete(dest, "refs/heads/other"));
    }

    @Test
    void refTipAndPullRequestActionAreDistinctFromABlanketPrNumber() {
        String dest = "https://github.com/acme/mirror-dest.git";
        dedupLedgerService.recordRefTip(dest, "refs/heads/main", "7bceb28");
        assertTrue(dedupLedgerService.isEchoPush(dest, "refs/heads/main", "7bceb28"));
        assertFalse(dedupLedgerService.isEchoPush(dest, "refs/heads/main", "05b3b4d"));
        assertEquals("7bceb28", dedupLedgerService.refTip("https://github.com/acme/mirror-dest", "main"));

        dedupLedgerService.recordPullRequestOpened(dest, 1, "1379233");
        assertTrue(dedupLedgerService.isEchoPullRequest(dest, 1, "opened", "1379233", null, false));
        assertFalse(dedupLedgerService.isEchoPullRequest(
                dest, 1, "closed", "1379233", "05b3b4d", true));

        dedupLedgerService.recordPullRequestClosed(dest, 1);
        assertTrue(dedupLedgerService.isEchoPullRequest(dest, 1, "closed", null, null, false));
    }
}
