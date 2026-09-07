package com.gitutility.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class DedupLedgerServiceTest {

    private DedupLedgerService dedupLedgerService;

    @BeforeEach
    void setUp() {
        dedupLedgerService = new DedupLedgerService();
        ReflectionTestUtils.setField(dedupLedgerService, "ledgerTtlSeconds", 600L);
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
}
