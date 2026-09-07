package com.gitutility.service;

import com.gitutility.model.entity.RepoMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RefInterestPolicyTest {

    private RefInterestPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new RefInterestPolicy(
                "agents/,agent/,dependabot/,renovate/,fork-pr-,sync-conflict/",
                45_000L,
                true);
        policy.clearCoalesceState();
    }

    @Test
    void ephemeralPrefixesMatchAgentAndBotBranches() {
        assertTrue(policy.isEphemeralAutomationBranch("agents/fix-foo"));
        assertTrue(policy.isEphemeralAutomationBranch("refs/heads/agent/bar"));
        assertTrue(policy.isEphemeralAutomationBranch("dependabot/npm-bump"));
        assertTrue(policy.isEphemeralAutomationBranch("fork-pr-123"));
        assertFalse(policy.isEphemeralAutomationBranch("main"));
        assertFalse(policy.isEphemeralAutomationBranch("feature/login"));
    }

    @Test
    void highPriorityIncludesTrunkAndRelease() {
        assertTrue(policy.isHighPriorityWebhookBranch("main"));
        assertTrue(policy.isHighPriorityWebhookBranch("master"));
        assertTrue(policy.isHighPriorityWebhookBranch("release/1.2"));
        assertFalse(policy.isHighPriorityWebhookBranch("feature/x"));
    }

    @Test
    void enqueueSkipsEphemeralEvenWhenPatternIsStar() {
        RepoMapping mapping = RepoMapping.builder().id(1L).branchPattern("*").build();
        assertFalse(policy.shouldEnqueuePushWebhook(mapping, "agents/log-analysis"));
        assertTrue(policy.shouldEnqueuePushWebhook(mapping, "main"));
        assertTrue(policy.shouldEnqueuePushWebhook(mapping, "feature/x"));
    }

    @Test
    void enqueueHonorsBranchPatternAllowlist() {
        RepoMapping mapping = RepoMapping.builder().id(1L).branchPattern("main,release/*").build();
        assertTrue(policy.shouldEnqueuePushWebhook(mapping, "main"));
        assertTrue(policy.shouldEnqueuePushWebhook(mapping, "release/2.0"));
        assertFalse(policy.shouldEnqueuePushWebhook(mapping, "feature/x"));
        assertFalse(policy.shouldEnqueuePushWebhook(mapping, "agents/x"));
    }

    @Test
    void matchesBranchPatternSupportsWildcards() {
        assertTrue(RefInterestPolicy.matchesBranchPattern("*", "anything"));
        assertTrue(RefInterestPolicy.matchesBranchPattern("main", "main"));
        assertTrue(RefInterestPolicy.matchesBranchPattern("release/*", "release/1"));
        assertFalse(RefInterestPolicy.matchesBranchPattern("release/*", "releases/1"));
        assertTrue(RefInterestPolicy.matchesBranchPattern("feat*", "feature"));
    }

    @Test
    void coalesceDropsRapidNonTrunkButNotMain() {
        assertTrue(policy.tryAcceptIncrementalEnqueue(5L, "feature/a"));
        assertFalse(policy.tryAcceptIncrementalEnqueue(5L, "feature/b"));
        assertTrue(policy.tryAcceptIncrementalEnqueue(5L, "main"));
    }

    @Test
    void prWebhookSkipsEphemeralHeads() {
        RepoMapping mapping = RepoMapping.builder().id(1L).branchPattern("*").build();
        assertFalse(policy.shouldHandlePrWebhook(mapping, "agents/pr-head"));
        assertTrue(policy.shouldHandlePrWebhook(mapping, "feature/pr-head"));
    }
}
