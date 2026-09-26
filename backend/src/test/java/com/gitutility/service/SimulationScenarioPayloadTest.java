package com.gitutility.service;

import com.gitutility.model.dto.SimulationScenarioRequest;
import com.gitutility.model.entity.RepoMapping;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationScenarioPayloadTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RepoMapping pair = RepoMapping.builder()
            .id("pair-1")
            .repoAUrl("https://github.com/acme/source.git")
            .repoBUrl("https://github.com/acme/mirror.git")
            .build();

    @Test
    void refForKeepsHeadsTagsAndNotes() {
        assertEquals("refs/heads/test/sim-probe", SimulationScenarioPayload.refFor("branch", "test/sim-probe"));
        assertEquals("refs/tags/sim-probe", SimulationScenarioPayload.refFor("tag", "sim-probe"));
        assertEquals("refs/notes/sim-note", SimulationScenarioPayload.refFor("note", "sim-note"));
    }

    @Test
    void destinationDeleteNamesTheMirrorRepository() throws Exception {
        SimulationScenarioPayload.Built built = SimulationScenarioPayload.build(pair, SimulationScenarioRequest.builder()
                .side("destination")
                .kind("branch")
                .operation("delete")
                .refName("test/sim-probe")
                .commitSha("abcdef1")
                .build());

        JsonNode root = JSON.readTree(built.json());
        assertEquals("delete", built.eventType());
        assertEquals("https://github.com/acme/mirror.git", root.path("repository").path("clone_url").asText());
        assertEquals("refs/heads/test/sim-probe", root.path("ref").asText());
        assertEquals(SimulationScenarioPayload.ZERO_SHA, root.path("after").asText());
        assertEquals("simulation-lab", root.path("sender").path("login").asText());
    }

    @Test
    void mergeAndUnpublishKeepTheChosenSide() throws Exception {
        SimulationScenarioPayload.Built merge = SimulationScenarioPayload.build(pair, SimulationScenarioRequest.builder()
                .side("source")
                .kind("pull_request")
                .operation("merge")
                .pullRequestNumber(5L)
                .refName("test/meta-sync")
                .commitSha("edc84f9")
                .build());
        JsonNode pr = JSON.readTree(merge.json());
        assertEquals("pull_request", merge.eventType());
        assertEquals("closed", pr.path("action").asText());
        assertTrue(pr.path("pull_request").path("merged").asBoolean());
        assertEquals("https://github.com/acme/source.git", pr.path("repository").path("clone_url").asText());
        assertEquals("acme/source", pr.path("pull_request").path("head").path("repo").path("full_name").asText());

        SimulationScenarioPayload.Built release = SimulationScenarioPayload.build(pair, SimulationScenarioRequest.builder()
                .side("DESTINATION")
                .kind("release")
                .operation("unpublish")
                .releaseTag("meta-sync-v1")
                .build());
        JsonNode body = JSON.readTree(release.json());
        assertEquals("release", release.eventType());
        assertEquals("unpublished", body.path("action").asText());
        assertEquals("https://github.com/acme/mirror.git", body.path("repository").path("clone_url").asText());
        assertFalse(body.path("release").path("tag_name").asText().isBlank());
    }

    @Test
    void statusAndCheckRunUseTheRequestedOutcome() throws Exception {
        SimulationScenarioPayload.Built status = SimulationScenarioPayload.build(pair, SimulationScenarioRequest.builder()
                .side("source")
                .kind("status")
                .operation("failure")
                .commitSha("abc123")
                .context("simulation/status")
                .build());
        JsonNode statusNode = JSON.readTree(status.json());
        assertEquals("status", status.eventType());
        assertEquals("failure", statusNode.path("state").asText());
        assertEquals("abc123", statusNode.path("sha").asText());

        SimulationScenarioPayload.Built check = SimulationScenarioPayload.build(pair, SimulationScenarioRequest.builder()
                .side("destination")
                .kind("check_run")
                .operation("success")
                .context("simulation/ping")
                .commitSha("abc123")
                .build());
        JsonNode run = JSON.readTree(check.json()).path("check_run");
        assertEquals("check_run", check.eventType());
        assertEquals("completed", run.path("status").asText());
        assertEquals("success", run.path("conclusion").asText());
        assertEquals("https://github.com/acme/mirror.git",
                JSON.readTree(check.json()).path("repository").path("clone_url").asText());
    }
}
