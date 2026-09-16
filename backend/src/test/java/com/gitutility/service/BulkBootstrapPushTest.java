package com.gitutility.service;

import com.gitutility.model.dto.PermissionCheckReport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decision matrix for the single-connection bulk mirror bootstrap push.
 * Bulk is used ONLY for full-mirror jobs against an EMPTY, reachable destination with refs to push;
 * everything else keeps the batched precision push.
 */
class BulkBootstrapPushTest {

    @Test
    void bulkUsedOnlyForFullMirrorOnBlankReachableDestination() {
        assertTrue(GitSyncEngine.shouldUseBulkBootstrapPush(true, true, true, true, 2));
        assertTrue(GitSyncEngine.shouldUseBulkBootstrapPush(true, true, true, true, 903 * 8));
    }

    @Test
    void batchedPrecisionPushUsedWhenAnyGuardFails() {
        assertFalse(GitSyncEngine.shouldUseBulkBootstrapPush(false, true, true, true, 500), "feature disabled");
        assertFalse(GitSyncEngine.shouldUseBulkBootstrapPush(true, false, true, true, 500), "not a full mirror");
        assertFalse(GitSyncEngine.shouldUseBulkBootstrapPush(true, true, false, true, 500), "target unreachable");
        assertFalse(GitSyncEngine.shouldUseBulkBootstrapPush(true, true, true, false, 500), "destination has refs");
        assertFalse(GitSyncEngine.shouldUseBulkBootstrapPush(true, true, true, true, 0), "nothing to push");
        assertFalse(GitSyncEngine.shouldUseBulkBootstrapPush(true, true, true, true, 1), "single ref");
    }

    @Test
    void permissionCheckReportCarriesEmptyDestinationSignal() {
        PermissionCheckReport report = PermissionCheckReport.builder()
                .valid(true)
                .emptyDestination(Boolean.TRUE)
                .build();
        assertEquals(Boolean.TRUE, report.getEmptyDestination());
        assertNull(PermissionCheckReport.builder().valid(true).build().getEmptyDestination(),
                "unknown (non-destination or probe failure) must stay null, never false");
    }
}