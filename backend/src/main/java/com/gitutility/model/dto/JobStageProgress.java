package com.gitutility.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Job-scoped partial progress within a pipeline stage (push ledger, LFS transfer cursor).
 * Persisted on {@link com.gitutility.model.entity.SyncJob#stageProgressJson} — not inferred from git.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobStageProgress {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Builder.Default
    private Map<String, String> completedPushRefs = new LinkedHashMap<>();

    @Builder.Default
    private Set<String> completedLfsOids = new LinkedHashSet<>();

    /** tab-separated oid\\tsize lines, same format as pair-level LFS cache. */
    private String discoveredLfsBlob;

    /** GraphQL endCursor or REST Link URL — resume open-PR listing mid-job. */
    private String prListCursor;

    /** True when the last job run finished listing all open PR pages. */
    private Boolean prListComplete;

    /**
     * Source PR numbers observed while listing open PRs this job (across resumes).
     * Used so close-reconcile does not treat unread pages as closed.
     */
    @Builder.Default
    private Set<Long> prSeenOpenSourceNumbers = new LinkedHashSet<>();

    public static JobStageProgress empty() {
        return JobStageProgress.builder().build();
    }

    public static JobStageProgress fromJson(String json) {
        if (json == null || json.isBlank()) {
            return empty();
        }
        try {
            JobStageProgress parsed = MAPPER.readValue(json, JobStageProgress.class);
            if (parsed.completedPushRefs == null) {
                parsed.completedPushRefs = new LinkedHashMap<>();
            }
            if (parsed.completedLfsOids == null) {
                parsed.completedLfsOids = new LinkedHashSet<>();
            }
            if (parsed.prSeenOpenSourceNumbers == null) {
                parsed.prSeenOpenSourceNumbers = new LinkedHashSet<>();
            }
            return parsed;
        } catch (Exception e) {
            return empty();
        }
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Union of push refs and LFS cursors so a ref push and an overlapping LFS pass
     * can persist without dropping the other side's progress.
     */
    public static JobStageProgress merge(JobStageProgress base, JobStageProgress incoming) {
        if (incoming == null) {
            return base == null ? empty() : base;
        }
        if (base == null) {
            return incoming;
        }
        if (incoming.completedPushRefs != null) {
            base.completedPushRefs.putAll(incoming.completedPushRefs);
        }
        if (incoming.completedLfsOids != null) {
            base.completedLfsOids.addAll(incoming.completedLfsOids);
        }
        if (incoming.discoveredLfsBlob != null) {
            base.discoveredLfsBlob = incoming.discoveredLfsBlob;
        }
        if (incoming.prListCursor != null) {
            base.prListCursor = incoming.prListCursor;
        }
        if (incoming.prListComplete != null) {
            base.prListComplete = incoming.prListComplete;
        }
        if (incoming.prSeenOpenSourceNumbers != null) {
            base.prSeenOpenSourceNumbers.addAll(incoming.prSeenOpenSourceNumbers);
        }
        return base;
    }
}
