package com.gitutility.model.enums;

/**
 * Pair-level resume checkpoint for full-mirror runs. Ordinal order defines skip depth.
 */
public enum SyncCheckpointStage {
    NONE,
    PUSH_DONE,
    LFS_DISCOVERY_DONE,
    LFS_TRANSFER_PARTIAL,
    /** Git mirror phases finished; resume continues with PR/release metadata only. */
    GIT_SYNC_DONE
}
