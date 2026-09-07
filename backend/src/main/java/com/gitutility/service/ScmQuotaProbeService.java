package com.gitutility.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Fills SCM quota "REST remaining" when the process has not yet observed {@code X-RateLimit-*}
 * on a real API call (e.g. only LFS/git traffic so far).
 * {@code GET /rate_limit} does not consume the primary REST quota.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScmQuotaProbeService {

    private static final Duration MIN_PROBE_INTERVAL = Duration.ofSeconds(60);

    private final ScmQuotaTracker scmQuotaTracker;
    private final GitHubAuthService gitHubAuthService;
    private final RestTemplate restTemplate;

    private volatile Instant lastProbeAt = Instant.EPOCH;

    public void probeGithubCoreIfNeeded() {
        ScmQuotaTracker.Snapshot snap = scmQuotaTracker.snapshot(1);
        boolean missingRemaining = snap.getInstallations().isEmpty()
                || snap.getInstallations().stream()
                .anyMatch(i -> "github".equalsIgnoreCase(i.getProvider()) && i.getRestRemaining() == null);
        if (!missingRemaining) {
            return;
        }
        Instant now = Instant.now();
        if (Duration.between(lastProbeAt, now).compareTo(MIN_PROBE_INTERVAL) < 0) {
            return;
        }
        lastProbeAt = now;
        try {
            String token = gitHubAuthService.getEffectiveGitHubToken();
            if (token == null || token.isBlank()) {
                return;
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token.trim());
            headers.setAccept(List.of(MediaType.valueOf("application/vnd.github+json")));
            headers.set("X-GitHub-Api-Version", "2022-11-28");
            // Interceptor on RestTemplate records X-RateLimit-* into ScmQuotaTracker.
            restTemplate.exchange(
                    URI.create("https://api.github.com/rate_limit"),
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class);
        } catch (Exception e) {
            log.debug("SCM quota rate_limit probe skipped: {}", e.getMessage());
        }
    }
}
