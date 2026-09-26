package com.gitutility.bdd;

import com.gitutility.service.MirrorBehavior;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PullRequestMirrorSteps {

    private String replicaHead;

    @When("a replica head is chosen for pull request {long} with head {string} from a fork")
    public void forkHead(long number, String head) {
        replicaHead = MirrorBehavior.replicaHeadBranch(number, head, true);
    }

    @When("a replica head is chosen for pull request {long} with head {string} from the same repository")
    public void sameRepoHead(long number, String head) {
        replicaHead = MirrorBehavior.replicaHeadBranch(number, head, false);
    }

    @Then("the replica head branch is {string}")
    public void replicaHeadIs(String branch) {
        assertEquals(branch, replicaHead);
    }

    @Then("{string} is a pull request close action")
    public void isClose(String action) {
        assertTrue(MirrorBehavior.isCloseAction(action));
    }

    @Then("{string} is not a pull request close action")
    public void isNotClose(String action) {
        assertFalse(MirrorBehavior.isCloseAction(action));
    }

    @Then("{string} is a pull request open action")
    public void isOpenAction(String action) {
        assertTrue(MirrorBehavior.isOpenAction(action));
    }

    @Then("pull request state {string} is open")
    public void stateOpen(String state) {
        assertTrue(MirrorBehavior.isOpenPullRequestState(state));
    }

    @Then("pull request state {string} is closed")
    public void stateClosed(String state) {
        assertFalse(MirrorBehavior.isOpenPullRequestState(state));
    }
}
