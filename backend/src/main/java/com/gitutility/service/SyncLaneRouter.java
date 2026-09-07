package com.gitutility.service;

import com.gitutility.model.dto.SyncEventMessage;

/**
 * Routes execution work onto the full-mirror lane vs the webhook incremental lane.
 * Matches {@code GitSyncEngine}: a null/blank ref or {@code *} branch is a full clone.
 */
public final class SyncLaneRouter {

    public static final String FULL_CONSUMER_ID = "gitSyncFullConsumer";
    public static final String INCREMENTAL_CONSUMER_ID = "gitSyncIncrementalConsumer";
    /** Legacy listener id kept in pause/resume lookups so older containers still stop. */
    public static final String LEGACY_CONSUMER_ID = "gitSyncConsumer";

    public static final String INBOUND_CONSUMER_ID = "inboundWebhookConsumer";

    public static final String[] EXECUTION_LISTENER_IDS = {
            FULL_CONSUMER_ID,
            INCREMENTAL_CONSUMER_ID,
            LEGACY_CONSUMER_ID
    };

    public static final String LANE_FULL = "FULL";
    public static final String LANE_INCREMENTAL = "INCREMENTAL";
    public static final String LANE_INBOUND = "INBOUND";

    private SyncLaneRouter() {
    }

    public static boolean isFullMirror(String ref, String branch) {
        return ref == null || ref.isBlank() || "*".equals(branch);
    }

    public static boolean isFullMirror(SyncEventMessage event) {
        if (event == null) {
            return true;
        }
        return isFullMirror(event.getRef(), event.getBranch());
    }

    /**
     * Pair-wide PR and release REST sync runs on full-mirror jobs only.
     * Incremental branch jobs (webhook / Sync main / overwrite) stay Git (+ LFS).
     * Operators still trigger PRs/releases from the dedicated UI actions.
     */
    public static boolean includePairMetadata(SyncEventMessage event) {
        return isFullMirror(event);
    }

    public static String lane(String ref, String branch) {
        return isFullMirror(ref, branch) ? LANE_FULL : LANE_INCREMENTAL;
    }

    public static String routingKey(String ref, String branch, String fullRoutingKey, String incrementalRoutingKey) {
        return isFullMirror(ref, branch) ? fullRoutingKey : incrementalRoutingKey;
    }
}
