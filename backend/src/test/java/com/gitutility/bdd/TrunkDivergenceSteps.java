package com.gitutility.bdd;

import com.gitutility.model.dto.SyncEventMessage;
import com.gitutility.model.enums.TrunkConflictPolicy;
import com.gitutility.service.GitSyncEngine.TrunkPushAction;
import com.gitutility.service.MirrorBehavior;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TrunkDivergenceSteps {

    private static final String MIRROR = "https://mirror.example/repo";
    private static final String ORIGIN = "https://source.example/repo";

    private boolean fastForward;
    private boolean destContainsSource;
    private boolean overwrite;
    private boolean bidirectional;
    private boolean sameTip;
    private boolean incomingContainsCurrent;
    private boolean currentContainsIncoming;
    private boolean beforeMatchesCurrentTip;
    private TrunkConflictPolicy policy;
    private TrunkPushAction action;
    private SyncEventMessage event;
    private String conflictRepo;

    @Before("@trunk")
    public void reset() {
        fastForward = false;
        destContainsSource = false;
        overwrite = false;
        bidirectional = false;
        sameTip = false;
        incomingContainsCurrent = false;
        currentContainsIncoming = false;
        beforeMatchesCurrentTip = false;
        policy = TrunkConflictPolicy.ISOLATE;
        action = null;
        event = null;
        conflictRepo = null;
    }

    @Given("a bidirectional pair")
    public void aBidirectionalPair() {
        bidirectional = true;
    }

    @Given("a unidirectional pair")
    public void aUnidirectionalPair() {
        bidirectional = false;
        overwrite = true;
    }

    @Given("the destination tip is a fast-forward of the origin")
    public void destinationIsFastForward() {
        fastForward = true;
    }

    @Given("the destination tip is not a fast-forward of the origin")
    public void destinationIsNotFastForward() {
        fastForward = false;
    }

    @Given("the destination contains the origin tip")
    public void destinationContainsOrigin() {
        destContainsSource = true;
        fastForward = false;
    }

    @Given("the operator has not requested an overwrite")
    public void noOverwrite() {
        overwrite = false;
    }

    @Given("the operator has requested an overwrite")
    public void requestedOverwrite() {
        overwrite = true;
    }

    @Given("the trunk conflict policy is {string}")
    public void trunkPolicy(String name) {
        policy = switch (name) {
            case "isolate" -> TrunkConflictPolicy.ISOLATE;
            case "origin-wins" -> TrunkConflictPolicy.ORIGIN_WINS;
            case "fail-job" -> TrunkConflictPolicy.FAIL_JOB;
            default -> throw new IllegalArgumentException("Unknown trunk policy: " + name);
        };
    }

    @Given("both sides are on the same tip")
    public void sameTip() {
        sameTip = true;
    }

    @Given("the webhook before-sha matches the current tip")
    public void beforeShaMatches() {
        beforeMatchesCurrentTip = true;
    }

    @Given("an incremental event for branch {string} with after sha {string}")
    public void incrementalEvent(String branch, String afterSha) {
        event = SyncEventMessage.builder()
                .ref("refs/heads/" + branch)
                .branch(branch)
                .afterSha(afterSha)
                .build();
    }

    @When("the trunk push is decided")
    public void decideTrunkPush() {
        action = MirrorBehavior.decideTrunkPush(
                fastForward, destContainsSource, overwrite, policy, bidirectional);
    }

    @When("the ancestry update is decided")
    public void decideAncestry() {
        action = MirrorBehavior.decideAncestryUpdate(
                sameTip,
                incomingContainsCurrent,
                currentContainsIncoming,
                beforeMatchesCurrentTip,
                overwrite,
                policy,
                bidirectional);
    }

    @When("the kept tip is on the {string}")
    public void keptTip(String side) {
        boolean keptOnSource = "origin".equals(side);
        conflictRepo = MirrorBehavior.repoForConflictPr(MIRROR, ORIGIN, keptOnSource);
    }

    @Then("the trunk action is {string}")
    public void trunkAction(String expected) {
        TrunkPushAction want = switch (expected) {
            case "push" -> TrunkPushAction.PUSH;
            case "force" -> TrunkPushAction.FORCE;
            case "isolate" -> TrunkPushAction.ISOLATE;
            case "skip" -> TrunkPushAction.SKIP;
            case "no-push" -> TrunkPushAction.NO_PUSH;
            case "adopt-dest" -> TrunkPushAction.ADOPT_DEST;
            default -> throw new IllegalArgumentException("Unknown trunk action: " + expected);
        };
        assertEquals(want, action);
    }

    @Then("the event is a source branch delete")
    public void isDelete() {
        assertTrue(MirrorBehavior.incrementalSourceBranchDeleted(event));
    }

    @Then("the event is not a source branch delete")
    public void isNotDelete() {
        assertFalse(MirrorBehavior.incrementalSourceBranchDeleted(event));
    }

    @Then("the conflict pull request opens on {string}")
    public void conflictOpensOn(String repo) {
        assertEquals(repo, conflictRepo);
    }
}
