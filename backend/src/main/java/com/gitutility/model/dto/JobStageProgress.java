package com.gitutility.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
}
