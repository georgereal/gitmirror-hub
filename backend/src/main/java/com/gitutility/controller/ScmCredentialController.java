package com.gitutility.controller;

import com.gitutility.model.dto.*;
import com.gitutility.model.entity.ScmCredential;
import com.gitutility.service.ScmCredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/scm-credentials")
@RequiredArgsConstructor
public class ScmCredentialController {

    private final ScmCredentialService credentialService;

    @GetMapping
    public ResponseEntity<List<ScmCredentialResponse>> list(
            @RequestParam(required = false) String provider) {
        List<ScmCredentialResponse> list = credentialService.list(provider).stream()
                .map(ScmCredentialResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScmCredentialResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(ScmCredentialResponse.fromEntity(credentialService.require(id)));
    }

    @PostMapping
    public ResponseEntity<ScmCredentialResponse> create(@RequestBody ScmCredentialRequest request) {
        ScmCredential saved = credentialService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ScmCredentialResponse.fromEntity(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ScmCredentialResponse> update(@PathVariable Long id, @RequestBody ScmCredentialRequest request) {
        return ResponseEntity.ok(ScmCredentialResponse.fromEntity(credentialService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        credentialService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/installations")
    public ResponseEntity<List<ScmInstallationOption>> installations(@PathVariable Long id) {
        return ResponseEntity.ok(credentialService.listInstallations(id));
    }

    @GetMapping("/{id}/repositories")
    public ResponseEntity<RepoSearchResult> repositories(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "") String query,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "15") int limit,
            @RequestParam(required = false, defaultValue = "PULL") String access) {
        return ResponseEntity.ok(credentialService.searchRepositories(id, query, page, limit, access));
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<PermissionCheckReport> test(
            @PathVariable Long id,
            @RequestBody(required = false) TestConnectionRequest request) {
        return ResponseEntity.ok(credentialService.testConnection(id, request));
    }

    @PostMapping("/{id}/create-repo")
    public ResponseEntity<Map<String, Object>> createRepo(
            @PathVariable Long id,
            @RequestBody CreateRepoRequest request) {
        boolean ok = credentialService.createRemoteRepository(id, request);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("ok", false, "message", "Failed to create repository with this credential"));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/suggest")
    public ResponseEntity<List<ScmCredentialResponse>> suggest(@RequestParam String repoUrl) {
        List<ScmCredentialResponse> list = credentialService.suggestForRepoUrl(repoUrl).stream()
                .map(ScmCredentialResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(list);
    }
}
