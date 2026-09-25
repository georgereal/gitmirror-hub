package com.gitutility.controller;

import com.gitutility.model.dto.RepositoryRulesetItem;
import com.gitutility.model.dto.WriteAuthorityRequest;
import com.gitutility.model.dto.WriteAuthorityView;
import com.gitutility.service.WriteAuthorityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/write-authority")
@RequiredArgsConstructor
public class WriteAuthorityController {

    private final WriteAuthorityService writeAuthorityService;

    @GetMapping
    public ResponseEntity<WriteAuthorityView> view(
            @RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(writeAuthorityService.view(refresh));
    }

    @GetMapping("/repository")
    public ResponseEntity<?> repository(
            @RequestParam String credentialId,
            @RequestParam String fullName,
            @RequestParam(required = false) String installationId) {
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(writeAuthorityService.repositoryRuleset(credentialId, fullName, installationId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/repository/rulesets")
    public ResponseEntity<?> repositoryRulesets(
            @RequestParam String credentialId,
            @RequestParam String fullName,
            @RequestParam(required = false) String installationId) {
        try {
            List<RepositoryRulesetItem> items = writeAuthorityService.listRepositoryRulesets(
                    credentialId, fullName, installationId);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(items);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/repository/rulesets/enforcement")
    public ResponseEntity<?> setRepositoryRulesetEnforcement(@RequestBody WriteAuthorityRequest.RulesetEnforcement body) {
        try {
            writeAuthorityService.setRepositoryRulesetEnforcement(body);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> apply(@RequestBody WriteAuthorityRequest request) {
        try {
            return ResponseEntity.ok(writeAuthorityService.apply(request));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
