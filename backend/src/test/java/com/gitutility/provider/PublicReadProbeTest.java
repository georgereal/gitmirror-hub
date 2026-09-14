package com.gitutility.provider;

import com.gitutility.model.dto.TestConnectionRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicReadProbeTest {

    @Test
    void skipAnonymousProbe_whenCredentialIdPresent() {
        TestConnectionRequest req = TestConnectionRequest.builder()
                .repoUrl("https://github.com/owner/public-repo.git")
                .requiredAccess("READ")
                .knownPrivate(false)
                .credentialId(42L)
                .build();
        assertTrue(PublicReadProbe.skipAnonymousProbe(req));
    }

    @Test
    void skipAnonymousProbe_whenTokenPresent() {
        TestConnectionRequest req = TestConnectionRequest.builder()
                .repoUrl("https://github.com/owner/public-repo.git")
                .requiredAccess("READ")
                .token("ghs_test")
                .build();
        assertTrue(PublicReadProbe.skipAnonymousProbe(req));
    }

    @Test
    void allowAnonymousProbe_whenNoCredentialAndPublicRead() {
        TestConnectionRequest req = TestConnectionRequest.builder()
                .repoUrl("https://github.com/owner/public-repo.git")
                .requiredAccess("READ")
                .knownPrivate(false)
                .build();
        assertFalse(PublicReadProbe.skipAnonymousProbe(req));
    }

    @Test
    void skipAnonymousProbe_whenKnownPrivateOrWrite() {
        assertTrue(PublicReadProbe.skipAnonymousProbe(TestConnectionRequest.builder()
                .knownPrivate(true)
                .requiredAccess("READ")
                .build()));
        assertTrue(PublicReadProbe.skipAnonymousProbe(TestConnectionRequest.builder()
                .requiredAccess("WRITE")
                .build()));
    }
}
