package com.gitutility.model.enums;

/**
 * How the mirror treats a non-fast-forward update on a branch that already exists
 * on the destination. Trunk names are not special; a new branch (no destination tip)
 * is still created.
 */
public enum TrunkConflictPolicy {
    /** Keep destination tip; push source onto {@code sync-conflict/<branch>-<ts>} and optionally open a PR. */
    ISOLATE,
    /** Record the conflict and skip pushing the diverged trunk (other refs still proceed). */
    FAIL_JOB,
    /** Force-push source onto destination. For designated primary → replica / DR pairs. */
    ORIGIN_WINS;

    public static TrunkConflictPolicy fromString(String value) {
        if (value == null || value.isBlank()) {
            return ISOLATE;
        }
        try {
            return TrunkConflictPolicy.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ISOLATE;
        }
    }
}
