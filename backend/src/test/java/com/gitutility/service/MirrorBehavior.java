package com.gitutility.service;

import com.gitutility.model.dto.SyncDiffReport;
import com.gitutility.model.dto.SyncEventMessage;
import com.gitutility.model.enums.TrunkConflictPolicy;
import com.gitutility.service.GitSyncEngine.TrunkPushAction;

import java.util.List;

/**
 * Package bridge for the Cucumber scenarios. The decisions stay package-private
 * on the services; this test type is the only caller outside {@code com.gitutility.service}.
 */
public final class MirrorBehavior {

    private MirrorBehavior() {
    }

    public static TrunkPushAction decideTrunkPush(boolean fastForward, boolean destContainsSource,
                                                   boolean overwrite, TrunkConflictPolicy policy,
                                                   boolean bidirectional) {
        return GitSyncEngine.decideTrunkPush(fastForward, destContainsSource, overwrite, policy, bidirectional);
    }

    public static TrunkPushAction decideAncestryUpdate(boolean sameTip, boolean incomingContainsCurrent,
                                                        boolean currentContainsIncoming, boolean beforeMatchesCurrentTip,
                                                        boolean overwrite, TrunkConflictPolicy policy,
                                                        boolean bidirectional) {
        return GitSyncEngine.decideAncestryUpdate(
                sameTip, incomingContainsCurrent, currentContainsIncoming, beforeMatchesCurrentTip,
                overwrite, policy, bidirectional);
    }

    public static boolean incrementalSourceBranchDeleted(SyncEventMessage event) {
        return GitSyncEngine.incrementalSourceBranchDeleted(event);
    }

    public static String repoForConflictPr(String pushTargetRepo, String sourceRepo, boolean keptOnSource) {
        return GitSyncEngine.repoForConflictPr(pushTargetRepo, sourceRepo, keptOnSource);
    }

    public static boolean releaseDeleteIsEcho(boolean known, boolean peerPresent) {
        return ReleaseAndStatusSyncService.releaseDeleteIsEcho(known, peerPresent);
    }

    public static boolean releaseUnpublishIsEcho(boolean known, boolean peerPresent, boolean peerDraft) {
        return ReleaseAndStatusSyncService.releaseUnpublishIsEcho(known, peerPresent, peerDraft);
    }

    public static boolean statusWriteIsEcho(boolean known, String peerState, String incomingState) {
        return ReleaseAndStatusSyncService.statusWriteIsEcho(known, peerState, incomingState);
    }

    public static SyncDiffReport.ReleaseDetail preferRelease(SyncDiffReport.ReleaseDetail current,
                                                              SyncDiffReport.ReleaseDetail candidate) {
        return ReleaseAndStatusSyncService.preferRelease(current, candidate);
    }

    public static int distinctReleaseTags(List<SyncDiffReport.ReleaseDetail> releases) {
        return ReleaseAndStatusSyncService.distinctReleaseTags(releases);
    }

    public static String replicaHeadBranch(long sourcePrNumber, String headRef, boolean isFork) {
        return PullRequestSyncService.replicaHeadBranch(sourcePrNumber, headRef, isFork);
    }

    public static boolean isCloseAction(String action) {
        return PullRequestSyncService.isCloseAction(action);
    }

    public static boolean isOpenAction(String action) {
        return PullRequestSyncService.isOpenAction(action);
    }

    public static boolean isOpenPullRequestState(String state) {
        return PullRequestSyncService.isOpenPullRequestState(state);
    }
}
