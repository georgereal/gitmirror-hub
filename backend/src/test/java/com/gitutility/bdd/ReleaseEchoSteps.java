package com.gitutility.bdd;

import com.gitutility.model.dto.SyncDiffReport;
import com.gitutility.service.MirrorBehavior;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReleaseEchoSteps {

    private boolean peerKnown;
    private boolean peerPresent;
    private boolean peerDraft;
    private boolean statusKnown;
    private boolean statusEcho;
    private SyncDiffReport.ReleaseDetail draft;
    private SyncDiffReport.ReleaseDetail published;
    private SyncDiffReport.ReleaseDetail kept;

    @Before("@release")
    public void reset() {
        peerKnown = false;
        peerPresent = false;
        peerDraft = false;
        statusKnown = false;
        statusEcho = false;
        draft = null;
        published = null;
        kept = null;
    }

    @Given("the other repository was looked up")
    public void peerLookedUp() {
        peerKnown = true;
    }

    @Given("the other repository lookup failed")
    public void peerLookupFailed() {
        peerKnown = false;
        peerPresent = false;
    }

    @Given("the tag is already gone on the peer")
    public void tagGone() {
        peerPresent = false;
    }

    @Given("the tag is still present on the peer")
    public void tagPresent() {
        peerPresent = true;
    }

    @Given("the peer release is present and a draft")
    public void peerDraft() {
        peerPresent = true;
        peerDraft = true;
    }

    @Given("the peer release is present and published")
    public void peerPublished() {
        peerPresent = true;
        peerDraft = false;
    }

    @Given("the hub knows the peer status")
    public void statusKnown() {
        statusKnown = true;
    }

    @Given("the hub does not know the peer status")
    public void statusUnknown() {
        statusKnown = false;
    }

    @Given("a draft release and a published release share tag {string}")
    public void sharedTag(String tag) {
        draft = SyncDiffReport.ReleaseDetail.builder().id(1L).tagName(tag).isDraft(true).build();
        published = SyncDiffReport.ReleaseDetail.builder().id(2L).tagName(tag).isDraft(false).build();
        kept = MirrorBehavior.preferRelease(draft, published);
    }

    @When("status {string} is written and the peer status is {string}")
    public void statusWritten(String incoming, String peer) {
        statusEcho = MirrorBehavior.statusWriteIsEcho(statusKnown, peer, incoming);
    }

    @Then("the release delete is an echo")
    public void deleteIsEcho() {
        assertTrue(MirrorBehavior.releaseDeleteIsEcho(peerKnown, peerPresent));
    }

    @Then("the release delete is not an echo")
    public void deleteIsNotEcho() {
        assertFalse(MirrorBehavior.releaseDeleteIsEcho(peerKnown, peerPresent));
    }

    @Then("the release unpublish is an echo")
    public void unpublishIsEcho() {
        assertTrue(MirrorBehavior.releaseUnpublishIsEcho(peerKnown, peerPresent, peerDraft));
    }

    @Then("the release unpublish is not an echo")
    public void unpublishIsNotEcho() {
        assertFalse(MirrorBehavior.releaseUnpublishIsEcho(peerKnown, peerPresent, peerDraft));
    }

    @Then("the status write is an echo")
    public void statusIsEcho() {
        assertTrue(statusEcho);
    }

    @Then("the status write is not an echo")
    public void statusIsNotEcho() {
        assertFalse(statusEcho);
    }

    @Then("the kept release is the published one")
    public void keptPublished() {
        assertEquals(published.getId(), kept.getId());
    }

    @Then("the distinct release tag count is {int}")
    public void distinctTags(int count) {
        assertEquals(count, MirrorBehavior.distinctReleaseTags(List.of(draft, published)));
    }
}
