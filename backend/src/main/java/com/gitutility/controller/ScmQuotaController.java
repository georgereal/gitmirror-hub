package com.gitutility.controller;

import com.gitutility.model.dto.ScmQuotaResponse;
import com.gitutility.service.ScmQuotaProbeService;
import com.gitutility.service.ScmQuotaTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/scm-quotas")
@RequiredArgsConstructor
public class ScmQuotaController {

    private final ScmQuotaTracker scmQuotaTracker;
    private final ScmQuotaProbeService scmQuotaProbeService;

    @GetMapping
    public ResponseEntity<ScmQuotaResponse> getScmQuotas(
            @RequestParam(name = "topRepos", defaultValue = "25") int topRepos) {
        if (scmQuotaProbeService != null) {
            scmQuotaProbeService.probeGithubCoreIfNeeded();
        }
        ScmQuotaTracker.Snapshot snap = scmQuotaTracker.snapshot(Math.min(Math.max(topRepos, 1), 100));
        ScmQuotaResponse body = ScmQuotaResponse.builder()
                .capturedAt(snap.getCapturedAt())
                .installations(snap.getInstallations().stream()
                        .map(v -> ScmQuotaResponse.InstallQuota.builder()
                                .provider(v.getProvider())
                                .installationKey(v.getInstallationKey())
                                .restRemaining(v.getRestRemaining())
                                .restLimit(v.getRestLimit())
                                .restResetAt(v.getRestResetAt())
                                .restCalls(v.getRestCalls())
                                .rest429Count(v.getRest429Count())
                                .graphqlRemaining(v.getGraphqlRemaining())
                                .graphqlLimit(v.getGraphqlLimit())
                                .graphqlCalls(v.getGraphqlCalls())
                                .graphqlPointsUsed(v.getGraphqlPointsUsed())
                                .graphql429Count(v.getGraphql429Count())
                                .updatedAt(v.getUpdatedAt())
                                .series(v.getSeries() == null ? List.of() : v.getSeries().stream()
                                        .map(s -> ScmQuotaResponse.QuotaSample.builder()
                                                .at(s.getAt())
                                                .restRemaining(s.getRestRemaining())
                                                .restLimit(s.getRestLimit())
                                                .appRestCalls(s.getAppRestCalls())
                                                .graphqlRemaining(s.getGraphqlRemaining())
                                                .appGraphqlCalls(s.getAppGraphqlCalls())
                                                .build())
                                        .toList())
                                .githubRestConsumed(v.getGithubRestConsumed())
                                .appRestConsumed(v.getAppRestConsumed())
                                .externalRestSuspect(v.getExternalRestSuspect())
                                .build())
                        .toList())
                .hottestRepos(snap.getHottestRepos().stream()
                        .map(v -> ScmQuotaResponse.RepoHotspot.builder()
                                .provider(v.getProvider())
                                .repoFullName(v.getRepoFullName())
                                .gitFetches(v.getGitFetches())
                                .gitPushes(v.getGitPushes())
                                .gitThrottles(v.getGitThrottles())
                                .graphqlCalls(v.getGraphqlCalls())
                                .graphqlPointsUsed(v.getGraphqlPointsUsed())
                                .graphql429Count(v.getGraphql429Count())
                                .heatScore(v.getHeatScore())
                                .updatedAt(v.getUpdatedAt())
                                .build())
                        .toList())
                .build();
        return ResponseEntity.ok(body);
    }
}
