package com.gitutility.model.dto;

import tools.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScmInstallationOption {
    private String installationId;
    private String accountLogin;
    private String accountType;
    private String repositorySelection;
    private String htmlUrl;
    /**
     * Raw permission scopes reported by {@code GET /app/installations}
     * (e.g. {@code {"contents":"write","administration":"write"}}). {@code null} when the host
     * did not return a permissions object.
     */
    private Map<String, String> permissions;
    /**
     * Server-derived: creating repositories requires {@code administration: write}.
     * {@code false} when the host reported a permissions object without {@code administration: write}
     * (GitHub omits ungranted permissions, so a missing key is positive knowledge). {@code null} only
     * when the host reported no permissions object at all (legacy hosts) — consumers must never block
     * on unknown.
     */
    private Boolean canCreateRepo;

    /** Parses one element of the {@code GET /app/installations} JSON array. */
    public static ScmInstallationOption fromInstallation(JsonNode n) {
        Map<String, String> permissions = new LinkedHashMap<>();
        JsonNode permsNode = n.path("permissions");
        boolean hasPermissions = permsNode.isObject();
        if (hasPermissions) {
            for (Map.Entry<String, JsonNode> e : permsNode.properties()) {
                JsonNode v = e.getValue();
                if (v != null && !v.isNull()) {
                    permissions.put(e.getKey(), v.asText());
                }
            }
        }
        // administration: write is required to POST /user/repos (or /orgs/*/repos). When the host
        // reports a permissions object, an absent or non-write administration entry is positive
        // knowledge that the installation cannot create repositories — surface that at credential
        // selection / preflight time instead of failing the live create call with a 403
        // ("Resource not accessible by integration"). No permissions node at all stays unknown.
        String administration = permissions.get("administration");
        return ScmInstallationOption.builder()
                .installationId(n.path("id").asText())
                .accountLogin(n.path("account").path("login").asText(null))
                .accountType(n.path("account").path("type").asText(null))
                .repositorySelection(n.path("repository_selection").asText(null))
                .htmlUrl(n.path("html_url").asText(null))
                .permissions(hasPermissions ? permissions : null)
                .canCreateRepo(hasPermissions ? Boolean.valueOf("write".equalsIgnoreCase(administration)) : null)
                .build();
    }
}
