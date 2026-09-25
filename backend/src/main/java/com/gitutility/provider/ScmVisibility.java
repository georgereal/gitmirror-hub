package com.gitutility.provider;

import tools.jackson.databind.JsonNode;

/**
 * SCM visibility as reported by the provider (public, private, internal).
 * Callers persist this value; the UI must not invent a different one.
 */
public final class ScmVisibility {

    private ScmVisibility() {}

    /** {@code PUBLIC}, {@code PRIVATE}, or {@code INTERNAL} from a GitHub/GitLab-style repo node. */
    public static String fromRepoNode(JsonNode repo) {
        if (repo == null || repo.isMissingNode() || repo.isNull()) {
            return "PRIVATE";
        }
        String visibility = repo.path("visibility").asText("");
        if ("public".equalsIgnoreCase(visibility)) {
            return "PUBLIC";
        }
        if ("internal".equalsIgnoreCase(visibility)) {
            return "INTERNAL";
        }
        if ("private".equalsIgnoreCase(visibility)) {
            return "PRIVATE";
        }
        return repo.path("private").asBoolean(true) ? "PRIVATE" : "PUBLIC";
    }

    public static boolean isPrivateFlag(String visibility) {
        return !"PUBLIC".equals(visibility);
    }

    public static String label(String visibility) {
        if ("PUBLIC".equals(visibility)) {
            return "Public";
        }
        if ("INTERNAL".equals(visibility)) {
            return "Internal";
        }
        return "Private";
    }
}
