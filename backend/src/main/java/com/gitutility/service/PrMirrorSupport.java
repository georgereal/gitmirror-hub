package com.gitutility.service;

import tools.jackson.databind.JsonNode;

/**
 * Builds mirrored pull-request bodies with source attribution and parses author metadata from webhooks.
 */
public final class PrMirrorSupport {

    static final String FOOTER_MARKER = "Mirrored by GitMirror Hub";

    private PrMirrorSupport() {
    }

    public static String buildMirroredBody(String sourceRepoFullName,
                                           long sourcePrNumber,
                                           String authorLogin,
                                           String sourcePrUrl,
                                           String originalBody) {
        String trimmed = originalBody != null ? originalBody.strip() : "";
        if (!trimmed.isEmpty() && trimmed.contains(FOOTER_MARKER)) {
            return trimmed;
        }

        StringBuilder footer = new StringBuilder("\n\n---\n");
        footer.append("_").append(FOOTER_MARKER);
        if (sourcePrUrl != null && !sourcePrUrl.isBlank()) {
            footer.append(" from [")
                    .append(sourceRepoFullName)
                    .append("#")
                    .append(sourcePrNumber)
                    .append("](")
                    .append(sourcePrUrl.trim())
                    .append(")");
        } else if (sourceRepoFullName != null && !sourceRepoFullName.isBlank()) {
            footer.append(" from ").append(sourceRepoFullName).append("#").append(sourcePrNumber);
        }
        if (authorLogin != null && !authorLogin.isBlank()) {
            footer.append(" — originally opened by **@").append(authorLogin.trim()).append("**");
        }
        footer.append("._");

        if (trimmed.isEmpty()) {
            return footer.substring(2).stripLeading();
        }
        return trimmed + footer;
    }

    public static String authorLoginFromWebhook(JsonNode prNode) {
        if (prNode == null || prNode.isMissingNode()) {
            return null;
        }
        String login = prNode.path("user").path("login").asText(null);
        if (login != null && !login.isBlank()) {
            return login;
        }
        login = prNode.path("author").path("username").asText(null);
        if (login != null && !login.isBlank()) {
            return login;
        }
        return prNode.path("author").path("nickname").asText(null);
    }

    public static String sourcePrUrlFromWebhook(JsonNode prNode) {
        if (prNode == null || prNode.isMissingNode()) {
            return null;
        }
        String url = prNode.path("html_url").asText(null);
        if (url != null && !url.isBlank()) {
            return url;
        }
        return prNode.path("links").path("html").path("href").asText(null);
    }

    public static String discussionSummary(int commentsCount, int reviewCommentsCount) {
        int total = Math.max(0, commentsCount) + Math.max(0, reviewCommentsCount);
        if (total <= 0) {
            return "No discussion on source yet";
        }
        if (reviewCommentsCount > 0 && commentsCount > 0) {
            return commentsCount + " comment(s), " + reviewCommentsCount + " review comment(s) on source (not replicated)";
        }
        if (reviewCommentsCount > 0) {
            return reviewCommentsCount + " review comment(s) on source (not replicated)";
        }
        return commentsCount + " comment(s) on source (not replicated)";
    }
}
