package com.gitutility.controller;

import tools.jackson.databind.ObjectMapper;
import com.gitutility.model.dto.GitHubPushPayload;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.service.WebhookIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final RepoMappingRepository mappingRepository;
    private final WebhookIngestionService webhookIngestionService;
    private final ObjectMapper objectMapper;
    private final com.gitutility.service.ScmCredentialService credentialService;

    @PostMapping("/github/{mappingId}")
    public ResponseEntity<?> handleMappingSpecificWebhook(
            @PathVariable Long mappingId,
            @RequestHeader(value = "X-GitHub-Event", defaultValue = "push") String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature256,
            @RequestBody String rawPayload) {

        if (!"push".equalsIgnoreCase(eventType)) {
            log.info("Ignoring non-push GitHub event: {}", eventType);
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "Only push events are processed"));
        }

        RepoMapping mapping = mappingRepository.findById(mappingId)
                .orElse(null);

        if (mapping == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Repository mapping not found for id: " + mappingId));
        }

        if (!mapping.isActive()) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "Mapping is inactive"));
        }

        // Validate HMAC signature if secret is configured
        if (mapping.getWebhookSecret() != null && !mapping.getWebhookSecret().isBlank()) {
            if (!webhookIngestionService.isValidSignature(rawPayload, mapping.getWebhookSecret(), signature256)) {
                log.warn("Invalid webhook signature for mapping: {}", mapping.getName());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid webhook signature"));
            }
        }

        return webhookIngestionService.processPushEvent(mapping, rawPayload);
    }

    @PostMapping({"/github", "/ghes"})
    public ResponseEntity<?> handleGenericWebhook(
            @RequestHeader(value = "X-GitHub-Event", defaultValue = "push") String eventType,
            @RequestBody String rawPayload) {

        if (!"push".equalsIgnoreCase(eventType)) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "Only push events are processed"));
        }

        try {
            GitHubPushPayload payload = objectMapper.readValue(rawPayload, GitHubPushPayload.class);
            if (payload.getRepository() == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "No repository info in payload"));
            }

            String repoUrl = payload.getRepository().getCloneUrl();
            String repoFullName = payload.getRepository().getFullName();

            List<RepoMapping> matches = mappingRepository.findActiveMatchingRepo(repoUrl, repoFullName);
            if (matches.isEmpty()) {
                log.info("No active mapping configured for repo: {}", repoFullName);
                return ResponseEntity.ok(Map.of("status", "ignored", "reason", "No active mapping found for repo: " + repoFullName));
            }

            // Process with the first matched mapping
            RepoMapping mapping = matches.get(0);
            return webhookIngestionService.processPushEvent(mapping, rawPayload);

        } catch (Exception e) {
            log.error("Error parsing generic push webhook: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid JSON payload: " + e.getMessage()));
        }
    }

    @PostMapping({"/github/credential/{credentialId}", "/ghes/credential/{credentialId}"})
    public ResponseEntity<?> handleCredentialWebhook(
            @PathVariable Long credentialId,
            @RequestHeader(value = "X-GitHub-Event", defaultValue = "push") String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature256,
            @RequestBody String rawPayload) {
        if (!"push".equalsIgnoreCase(eventType) && !"ping".equalsIgnoreCase(eventType)) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "Only push events are processed"));
        }
        if ("ping".equalsIgnoreCase(eventType)) {
            return ResponseEntity.ok(Map.of("status", "pong"));
        }
        var cred = credentialService.requireEnabled(credentialId);
        if (cred.getWebhookSecret() != null && !cred.getWebhookSecret().isBlank()) {
            if (!credentialService.hmacMatches(cred, rawPayload, signature256)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid webhook signature for credential " + credentialId));
            }
        }
        try {
            GitHubPushPayload payload = objectMapper.readValue(rawPayload, GitHubPushPayload.class);
            if (payload.getRepository() == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "No repository info in payload"));
            }
            String repoUrl = payload.getRepository().getCloneUrl();
            String repoFullName = payload.getRepository().getFullName();
            List<RepoMapping> matches = mappingRepository.findActiveMatchingRepo(repoUrl, repoFullName).stream()
                    .filter(m -> credentialId.equals(m.getSourceCredentialId())
                            || credentialId.equals(m.getTargetCredentialId()))
                    .toList();
            if (matches.isEmpty()) {
                return ResponseEntity.ok(Map.of("status", "ignored",
                        "reason", "No active pair bound to credential " + credentialId + " for " + repoFullName));
            }
            return webhookIngestionService.processPushEvent(matches.get(0), rawPayload);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
