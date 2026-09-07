package com.gitutility.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderRateMeterScmApiTest {

    @Test
    void apiGithubIsRestApiButLfsOnGithubComIsNot() {
        assertTrue(ProviderRateMeter.looksLikeScmRestApi("api.github.com", "/repos/acme/app"));
        assertTrue(ProviderRateMeter.looksLikeScmRestApi("api.github.com", "/rate_limit"));
        assertFalse(ProviderRateMeter.looksLikeScmRestApi("github.com", "/acme/app.git/info/lfs/objects/batch"));
        assertFalse(ProviderRateMeter.looksLikeScmRestApi("github.com", "/acme/app"));
    }
}
