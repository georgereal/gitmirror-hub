package com.gitutility.bdd;

import com.gitutility.model.dto.SimulationScenarioRequest;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.service.SimulationScenarioPayload;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SimulationProbeSteps {

    private static final String ZERO_SHA = "0000000000000000000000000000000000000000";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RepoMapping mapping;
    private SimulationScenarioPayload.Built built;
    private JsonNode payload;
    private Exception rejected;

    @Before("@sim-probe")
    public void reset() {
        mapping = null;
        built = null;
        payload = null;
        rejected = null;
    }

    @Given("a pair from {string} to {string}")
    public void pair(String origin, String mirror) {
        mapping = RepoMapping.builder()
                .id("1")
                .repoAUrl(origin)
                .repoBUrl(mirror)
                .build();
    }

    @When("a {string} {string} probe is sent from the source for ref {string}")
    public void probeFromSource(String kind, String operation, String ref) {
        send(kind, operation, "source", ref);
    }

    @When("a {string} {string} probe is sent from the destination for ref {string}")
    public void probeFromDestination(String kind, String operation, String ref) {
        send(kind, operation, "destination", ref);
    }

    @When("a {string} {string} probe is sent from {string} for ref {string}")
    public void probeFromSide(String kind, String operation, String side, String ref) {
        send(kind, operation, side, ref);
    }

    @Then("the probe event is {string}")
    public void eventType(String event) {
        assertNotNull(built);
        assertEquals(event, built.eventType());
    }

    @Then("the payload repository is {string}")
    public void repository(String cloneUrl) {
        assertEquals(cloneUrl, payload.path("repository").path("clone_url").asText());
    }

    @Then("the payload ref is {string}")
    public void ref(String ref) {
        assertEquals(ref, payload.path("ref").asText());
    }

    @Then("the payload is not a delete")
    public void notADelete() {
        assertFalse(payload.path("deleted").asBoolean(false));
    }

    @Then("the payload after sha is the zero sha")
    public void afterIsZero() {
        assertEquals(ZERO_SHA, payload.path("after").asText());
    }

    @Then("the pull request action is {string}")
    public void pullRequestAction(String action) {
        assertEquals(action, payload.path("action").asText());
    }

    @Then("the pull request merged flag is {string}")
    public void pullRequestMerged(String merged) {
        assertEquals(Boolean.parseBoolean(merged), payload.path("pull_request").path("merged").asBoolean());
    }

    @Then("the release action is {string}")
    public void releaseAction(String action) {
        assertEquals(action, payload.path("action").asText());
    }

    @Then("the status state is {string}")
    public void statusState(String state) {
        assertEquals(state, payload.path("state").asText());
    }

    @Then("the check run conclusion is {string}")
    public void checkConclusion(String conclusion) {
        assertEquals(conclusion, payload.path("check_run").path("conclusion").asText());
    }

    @Then("the probe is rejected")
    public void rejected() {
        assertNotNull(rejected);
        assertTrue(rejected instanceof IllegalArgumentException);
    }

    private void send(String kind, String operation, String side, String ref) {
        rejected = null;
        built = null;
        payload = null;
        try {
            built = SimulationScenarioPayload.build(mapping, SimulationScenarioRequest.builder()
                    .kind(kind)
                    .operation(operation)
                    .side(side)
                    .refName(ref)
                    .build());
            payload = JSON.readTree(built.json());
        } catch (RuntimeException ex) {
            rejected = ex;
        }
    }
}
