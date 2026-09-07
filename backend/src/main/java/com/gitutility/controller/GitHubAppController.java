package com.gitutility.controller;

import com.gitutility.model.dto.CreateRepoRequest;
import com.gitutility.model.dto.GitHubAppConfigRequest;
import com.gitutility.model.dto.GitHubRepoOption;
import com.gitutility.model.dto.PermissionCheckReport;
import com.gitutility.model.dto.ProviderConfigResponse;
import com.gitutility.model.dto.RepoSearchResult;
import com.gitutility.model.dto.TestConnectionRequest;
import com.gitutility.model.entity.GitHubAppConfig;
import com.gitutility.provider.ScmProviderFacade;
import com.gitutility.service.GitHubAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/github-app")
@RequiredArgsConstructor
public class GitHubAppController {

    private final GitHubAuthService gitHubAuthService;
    private final ScmProviderFacade scmProviderFacade;
    private final com.gitutility.service.ScmCredentialService credentialService;

    @GetMapping("/config")
    public ResponseEntity<ProviderConfigResponse> getAppConfig() {
        GitHubAppConfig config = gitHubAuthService.getAppConfig();
        return ResponseEntity.ok(ProviderConfigResponse.fromEntity(config));
    }

    @PostMapping("/config")
    public ResponseEntity<ProviderConfigResponse> saveAppConfig(@RequestBody GitHubAppConfigRequest request) {
        GitHubAppConfig saved = gitHubAuthService.saveAppConfig(request);
        return ResponseEntity.ok(ProviderConfigResponse.fromEntity(saved));
    }

    @PostMapping("/test-connection")
    public ResponseEntity<PermissionCheckReport> testConnection(@RequestBody TestConnectionRequest request) {
        return ResponseEntity.ok(scmProviderFacade.testConnection(null, request));
    }

    @PostMapping("/test-provider")
    public ResponseEntity<PermissionCheckReport> testProvider(
            @RequestParam(required = false, defaultValue = "GITHUB") String provider,
            @RequestBody(required = false) TestConnectionRequest request) {
        TestConnectionRequest safeReq = request != null ? request : TestConnectionRequest.builder().build();
        return ResponseEntity.ok(scmProviderFacade.testConnection(provider, safeReq));
    }

    @GetMapping("/repositories")
    public ResponseEntity<List<GitHubRepoOption>> listRepositories(
            @RequestParam(required = false) String token,
            @RequestParam(required = false) String provider) {
        if (provider == null || provider.isBlank() || "ALL".equalsIgnoreCase(provider)) {
            throw new IllegalArgumentException("provider is required");
        }
        return ResponseEntity.ok(scmProviderFacade.listAccessibleRepositories(token, provider));
    }

    @GetMapping("/search-repositories")
    public ResponseEntity<RepoSearchResult> searchRepositories(
            @RequestParam(required = false, defaultValue = "") String query,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "15") int limit,
            @RequestParam(required = false) Long credentialId,
            @RequestParam(required = false) String access) {
        if (credentialId != null) {
            return ResponseEntity.ok(credentialService.searchRepositories(credentialId, query, page, limit, access));
        }
        if (provider == null || provider.isBlank() || "ALL".equalsIgnoreCase(provider)) {
            throw new IllegalArgumentException("provider is required (do not search all providers)");
        }
        return ResponseEntity.ok(scmProviderFacade.searchRepositories(query, provider, page, limit));
    }

    @PostMapping("/create-repo")
    public ResponseEntity<GitHubRepoOption> createRepository(@RequestBody CreateRepoRequest request) {
        return ResponseEntity.ok(scmProviderFacade.createRemoteRepository(request));
    }
}
