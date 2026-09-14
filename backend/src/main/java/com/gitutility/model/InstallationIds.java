package com.gitutility.model;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Encode/decode selected GitHub App installation IDs stored as JSON on {@code scm_credentials}.
 */
public final class InstallationIds {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private InstallationIds() {
    }

    public static List<String> normalize(List<String> ids) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.isBlank()) {
                    out.add(id.trim());
                }
            }
        }
        return new ArrayList<>(out);
    }

    public static List<String> decode(String json, String primaryFallback) {
        List<String> fromJson = decodeJson(json);
        if (!fromJson.isEmpty()) {
            return fromJson;
        }
        if (primaryFallback != null && !primaryFallback.isBlank()) {
            return List.of(primaryFallback.trim());
        }
        return List.of();
    }

    public static String encode(List<String> ids) {
        List<String> normalized = normalize(ids);
        try {
            return MAPPER.writeValueAsString(normalized);
        } catch (Exception e) {
            return "[]";
        }
    }

    public static String primary(List<String> ids) {
        List<String> normalized = normalize(ids);
        return normalized.isEmpty() ? null : normalized.get(0);
    }

    public static boolean contains(List<String> ids, String installationId) {
        if (installationId == null || installationId.isBlank() || ids == null) {
            return false;
        }
        String needle = installationId.trim();
        for (String id : ids) {
            if (needle.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> decodeJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(json.trim());
            List<String> out = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode n : root) {
                    String v = n.isValueNode() ? n.asText(null) : null;
                    if (v != null && !v.isBlank()) {
                        out.add(v.trim());
                    }
                }
            }
            return normalize(out);
        } catch (Exception e) {
            return List.of();
        }
    }
}
