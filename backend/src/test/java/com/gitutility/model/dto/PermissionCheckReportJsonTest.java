package com.gitutility.model.dto;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionCheckReportJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void serializesIsPrivateAsIsPrivateNotPrivate() {
        PermissionCheckReport report = PermissionCheckReport.builder()
                .valid(true)
                .isPrivate(true)
                .message("ok")
                .accessMode("AUTHENTICATED")
                .build();

        JsonNode node = mapper.readTree(mapper.writeValueAsString(report));
        assertTrue(node.has("isPrivate"), "expected isPrivate key, got: " + node);
        assertEquals(true, node.get("isPrivate").asBoolean());
    }

    @Test
    void githubRepoOptionSerializesIsPrivate() {
        GitHubRepoOption opt = GitHubRepoOption.builder()
                .fullName("owner/repo")
                .isPrivate(true)
                .build();
        JsonNode node = mapper.readTree(mapper.writeValueAsString(opt));
        assertTrue(node.has("isPrivate"), "expected isPrivate key, got: " + node);
        assertEquals(true, node.get("isPrivate").asBoolean());
    }
}
