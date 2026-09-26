package com.gitutility.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseMetadataEchoTest {

    @Test
    void releaseDeleteAndStatusFollowTheOtherRepository() {
        assertTrue(ReleaseAndStatusSyncService.releaseDeleteIsEcho(true, false));
        assertFalse(ReleaseAndStatusSyncService.releaseDeleteIsEcho(true, true));
        assertFalse(ReleaseAndStatusSyncService.releaseDeleteIsEcho(false, false));

        assertTrue(ReleaseAndStatusSyncService.releaseUnpublishIsEcho(true, false, false));
        assertTrue(ReleaseAndStatusSyncService.releaseUnpublishIsEcho(true, true, true));
        assertFalse(ReleaseAndStatusSyncService.releaseUnpublishIsEcho(true, true, false));
        assertFalse(ReleaseAndStatusSyncService.releaseUnpublishIsEcho(false, false, false));

        assertTrue(ReleaseAndStatusSyncService.statusWriteIsEcho(true, "success", "SUCCESS"));
        assertFalse(ReleaseAndStatusSyncService.statusWriteIsEcho(true, "pending", "success"));
        assertFalse(ReleaseAndStatusSyncService.statusWriteIsEcho(false, "success", "success"));
    }

    @Test
    void onePublishedReleaseIsKeptWhenDraftsShareTheTag() {
        var draft = com.gitutility.model.dto.SyncDiffReport.ReleaseDetail.builder()
                .id(1L).tagName("meta-sync-v1").isDraft(true).build();
        var published = com.gitutility.model.dto.SyncDiffReport.ReleaseDetail.builder()
                .id(2L).tagName("meta-sync-v1").isDraft(false).build();
        var kept = ReleaseAndStatusSyncService.preferRelease(draft, published);
        assertEquals(2L, kept.getId());
        assertEquals(1, ReleaseAndStatusSyncService.distinctReleaseTags(java.util.List.of(draft, published)));
    }

    @Test
    void closedPullRequestsAreNotTheOpenMirror() {
        assertFalse(PullRequestSyncService.isOpenPullRequestState("closed"));
        assertFalse(PullRequestSyncService.isOpenPullRequestState("merged"));
        assertTrue(PullRequestSyncService.isOpenPullRequestState("open"));
        assertTrue(PullRequestSyncService.isOpenPullRequestState(null));
    }
}
