package com.gitutility.bdd;

import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.ScmCredential;
import com.gitutility.model.enums.PairSide;
import com.gitutility.provider.GitHubRulesetClient;
import com.gitutility.provider.ScmProviderAdapter;
import com.gitutility.provider.ScmProviderFacade;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.service.ReplicaRulesetService;
import com.gitutility.service.ScmCredentialService;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ReplicaRulesetSteps {

    private static final String ORIGIN = "https://github.com/acme/origin";
    private static final String MIRROR = "https://github.com/acme/mirror";
    private static final long APP_ID = 4242L;

    private RepoMappingRepository mappings;
    private ScmProviderFacade providers;
    private ScmProviderAdapter adapter;
    private ReplicaRulesetService rules;
    private RepoMapping mapping;
    private final List<String> calls = new ArrayList<>();
    private RuntimeException failure;

    @Before("@replica-ruleset")
    public void reset() {
        mappings = mock(RepoMappingRepository.class);
        providers = mock(ScmProviderFacade.class);
        adapter = mock(ScmProviderAdapter.class);
        ScmCredentialService credentials = mock(ScmCredentialService.class);
        calls.clear();
        failure = null;
        mapping = RepoMapping.builder()
                .id("pair")
                .repoAUrl(ORIGIN)
                .repoBUrl(MIRROR)
                .primarySide(PairSide.A)
                .sourceCredentialId("cred-a")
                .targetCredentialId("cred-b")
                .build();
        when(mappings.findById("pair")).thenReturn(Optional.of(mapping));
        when(mappings.save(any(RepoMapping.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(credentials.require(any())).thenReturn(ScmCredential.builder()
                .appId(Long.toString(APP_ID))
                .botLogin("gitmirror[bot]")
                .build());
        when(providers.parseRepoFullName(any())).thenAnswer(invocation -> sideOf(invocation.getArgument(0)));
        when(providers.getAdapterForUrl(any())).thenReturn(adapter);
        when(adapter.ensureReplicaReadonlyRuleset(any(), anyLong(), any())).thenAnswer(invocation -> {
            calls.add(invocation.getArgument(0) + "|" + invocation.getArgument(2));
            return 15L;
        });
        rules = new ReplicaRulesetService(mappings, providers, credentials);
    }

    @Given("a GitHub pair whose replica has no ruleset")
    public void githubPair() {
        mapping.setReplicaRulesetId(null);
        mapping.setReplicaRulesetEnforcement(null);
    }

    @Given("the replica ruleset enforcement is {string}")
    public void enforcement(String value) {
        mapping.setReplicaRulesetId(15L);
        mapping.setReplicaRulesetEnforcement(value);
    }

    @Given("repository A is the writable primary and repository B is locked")
    public void aWritableBLocked() {
        mapping.setPrimarySide(PairSide.A);
        mapping.setReplicaRulesetId(15L);
        mapping.setReplicaRulesetEnforcement("active");
    }

    @Given("repository A is locked")
    public void aLocked() {
        mapping.setPrimarySide(PairSide.B);
        mapping.setReplicaRulesetId(15L);
        mapping.setReplicaRulesetEnforcement("active");
    }

    @Given("a GitLab pair")
    public void gitlabPair() {
        mapping.setRepoAUrl("https://gitlab.com/acme/origin");
        mapping.setRepoBUrl("https://gitlab.com/acme/mirror");
        when(adapter.ensureReplicaReadonlyRuleset(any(), anyLong(), any()))
                .thenThrow(new UnsupportedOperationException(
                        "Repository rulesets are available on GitHub and GitHub Enterprise Server only"));
    }

    @When("the operator locks the replica")
    public void lockReplica() {
        apply("lock");
    }

    @When("the operator unlocks the replica")
    public void unlockReplica() {
        apply("unlock");
    }

    @When("the operator swaps the primary")
    public void swapPrimary() {
        apply("swap");
    }

    @When("the operator locks repository B")
    public void lockRepositoryB() {
        apply("swap");
    }

    @Then("a ruleset named {string} exists on the replica")
    public void rulesetName(String name) {
        assertNull(failure);
        assertEquals(name, GitHubRulesetClient.RULESET_NAME);
        assertTrue(calls.stream().anyMatch(call -> call.startsWith("B|")));
        assertNotNull(mapping.getReplicaRulesetId());
    }

    @Then("that ruleset enforcement is {string}")
    public void enforcementIs(String value) {
        assertEquals(value, mapping.getReplicaRulesetEnforcement());
        assertTrue(calls.stream().anyMatch(call -> call.endsWith("|" + value)));
    }

    @Then("the mirror App is the bypass actor")
    public void appIsBypass() {
        verify(adapter).ensureReplicaReadonlyRuleset(any(), eq(APP_ID), any());
    }

    @Then("the ruleset still exists")
    public void rulesetStillExists() {
        assertNotNull(mapping.getReplicaRulesetId());
        assertTrue(mapping.getReplicaRulesetId() > 0);
    }

    @Then("repository A is locked before repository B is unlocked")
    public void aLockedBeforeBUnlocked() {
        int lockA = calls.indexOf("A|active");
        int unlockB = calls.indexOf("B|disabled");
        assertTrue(lockA >= 0 && unlockB >= 0 && lockA < unlockB);
    }

    @Then("repository B is locked")
    public void bLocked() {
        assertTrue(calls.contains("B|active"));
        assertEquals(PairSide.A, mapping.getPrimarySide());
    }

    @Then("repository A is unlocked")
    public void aUnlocked() {
        assertTrue(calls.contains("A|disabled"));
    }

    @Then("no replica ruleset is written")
    public void nothingWritten() {
        assertNotNull(failure);
        assertTrue(failure instanceof UnsupportedOperationException);
        verify(mappings, never()).save(any());
        assertNull(mapping.getReplicaRulesetId());
    }

    private void apply(String action) {
        failure = null;
        try {
            rules.apply(mapping.getId(), action, null);
        } catch (RuntimeException ex) {
            failure = ex;
        }
    }

    private String sideOf(String url) {
        if (url != null && url.equals(mapping.getRepoBUrl())) {
            return "B";
        }
        return "A";
    }
}
