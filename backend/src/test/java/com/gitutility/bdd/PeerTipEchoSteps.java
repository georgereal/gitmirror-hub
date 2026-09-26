package com.gitutility.bdd;

import com.gitutility.model.entity.RepoMapping;
import com.gitutility.provider.ScmProviderFacade;
import com.gitutility.service.PairTipEchoService;
import com.gitutility.service.ScmCredentialService;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

public class PeerTipEchoSteps {

    private static final String ZERO_SHA = "0000000000000000000000000000000000000000";

    private boolean known;
    private String peerSha;
    private boolean peerPresent;
    private boolean pushEcho;
    private boolean deleteEcho;
    private PairTipEchoService echoService;
    private RepoMapping peerMapping;
    private Path peerDir;
    private String localTip;
    private PairTipEchoService.PeerTip lookedUp;

    @Before("@peer-tip")
    public void reset() {
        known = false;
        peerSha = null;
        peerPresent = false;
        pushEcho = false;
        deleteEcho = false;
        localTip = null;
        lookedUp = null;
        peerDir = null;
        echoService = new PairTipEchoService(mock(ScmProviderFacade.class), mock(ScmCredentialService.class));
        peerMapping = RepoMapping.builder()
                .id("peer")
                .repoAUrl("https://github.com/acme/origin.git")
                .repoBUrl("https://github.com/acme/mirror.git")
                .build();
    }

    @After("@peer-tip")
    public void removePeerRepository() throws IOException {
        if (peerDir == null) {
            return;
        }
        try (var walk = Files.walk(peerDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The next scenario uses a new directory.
                }
            });
        }
        peerDir = null;
    }

    @Given("the peer lookup succeeded")
    public void lookupSucceeded() {
        known = true;
    }

    @Given("the peer lookup failed")
    public void lookupFailed() {
        known = false;
    }

    @Given("the peer advertises sha {string}")
    public void peerAdvertises(String sha) {
        peerSha = sha;
        peerPresent = true;
    }

    @Given("the peer does not advertise the ref")
    public void peerRefGone() {
        peerPresent = false;
    }

    @Given("the peer still advertises the ref")
    public void peerRefPresent() {
        peerPresent = true;
    }

    @When("a push arrives at sha {string}")
    public void pushArrives(String sha) {
        pushEcho = PairTipEchoService.pushIsEcho(known, peerSha, sha);
    }

    @When("a push arrives at the zero sha")
    public void pushArrivesAtZero() {
        pushEcho = PairTipEchoService.pushIsEcho(known, peerSha, ZERO_SHA);
    }

    @Then("the push is an echo")
    public void pushIsEcho() {
        assertTrue(pushEcho);
    }

    @Then("the push is not an echo")
    public void pushIsNotEcho() {
        assertFalse(pushEcho);
    }

    @Then("the delete is an echo")
    public void deleteIsEcho() {
        deleteEcho = PairTipEchoService.deleteIsEcho(known, peerPresent);
        assertTrue(deleteEcho);
    }

    @Then("the delete is not an echo")
    public void deleteIsNotEcho() {
        deleteEcho = PairTipEchoService.deleteIsEcho(known, peerPresent);
        assertFalse(deleteEcho);
    }

    @Given("a local peer repository with branch {string}")
    public void localPeerRepository(String branch) throws Exception {
        peerDir = Files.createTempDirectory("peer-tip-");
        PersonIdent ident = new PersonIdent("Dev Tester", "dev@example.com");
        try (Git git = Git.init().setDirectory(peerDir.toFile()).setInitialBranch(branch).call()) {
            git.commit().setMessage("tip").setAllowEmpty(true).setAuthor(ident).setCommitter(ident).call();
            localTip = git.getRepository().resolve("refs/heads/" + branch).getName();
        }
        peerMapping.setRepoAUrl(peerDir.toUri().toString());
    }

    @Given("the peer repository cannot be opened")
    public void peerRepositoryMissing() {
        peerMapping.setRepoAUrl(Path.of(System.getProperty("java.io.tmpdir"), "missing-peer-repo").toUri().toString());
    }

    @When("the peer repository is read for ref {string}")
    public void readPeerRepository(String ref) {
        lookedUp = echoService.lookupSide(peerMapping, false, ref);
    }

    @Then("the peer lookup is known")
    public void lookupIsKnown() {
        assertNotNull(lookedUp);
        assertTrue(lookedUp.known());
    }

    @Then("the peer lookup is unknown")
    public void lookupIsUnknown() {
        assertNotNull(lookedUp);
        assertFalse(lookedUp.known());
    }

    @Then("the peer ref is not advertised")
    public void refNotAdvertised() {
        assertFalse(lookedUp.present());
    }

    @Then("the advertised sha matches the local tip")
    public void advertisedShaMatches() {
        assertEquals(localTip, lookedUp.sha());
    }

    @Then("that lookup makes a delete an echo")
    public void lookupDeleteIsEcho() {
        assertTrue(PairTipEchoService.deleteIsEcho(lookedUp.known(), lookedUp.present()));
    }

    @Then("that lookup does not make a delete an echo")
    public void lookupDeleteIsNotEcho() {
        assertFalse(PairTipEchoService.deleteIsEcho(lookedUp.known(), lookedUp.present()));
    }

    @Then("that lookup makes a push of the local tip an echo")
    public void lookupPushIsEcho() {
        assertTrue(PairTipEchoService.pushIsEcho(lookedUp.known(), lookedUp.sha(), localTip));
    }
}
