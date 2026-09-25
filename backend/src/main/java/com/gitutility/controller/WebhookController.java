package com.gitutility.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
            @PathVariable String mappingId,
            @RequestHeader(value = "X-GitHub-Event", defaultValue = "push") String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature256,
            @RequestBody String rawPayload) {

        if ("ping".equalsIgnoreCase(eventType)) {
            return ResponseEntity.ok(Map.of("status", "pong"));
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

        return webhookIngestionService.processGithubDelivery(mapping, eventType, rawPayload);
    }

    @PostMapping({"/github", "/ghes"})
    public ResponseEntity<?> handleGenericWebhook(
            @RequestHeader(value = "X-GitHub-Event", defaultValue = "push") String eventType,
            @RequestBody String rawPayload) {

        if ("ping".equalsIgnoreCase(eventType)) {
            return ResponseEntity.ok(Map.of("status", "pong"));
        }

        try {
            RepoMapping mapping = webhookIngestionService.findMappingForPayload(rawPayload);
            if (mapping == null) {
                log.info("No active mapping configured for webhook payload");
                return ResponseEntity.ok(Map.of("status", "ignored", "reason", "No active mapping found for repo"));
            }
            return webhookIngestionService.processGithubDelivery(mapping, eventType, rawPayload);
        } catch (Exception e) {
            log.error("Error parsing generic webhook: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid JSON payload: " + e.getMessage()));
        }
    }

    @PostMapping({"/github/credential/{credentialId}", "/ghes/credential/{credentialId}"})
    public ResponseEntity<?> handleCredentialWebhook(
            @PathVariable String credentialId,
            @RequestHeader(value = "X-GitHub-Event", defaultValue = "push") String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature256,
            @RequestBody String rawPayload) {
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
            JsonNode repo = objectMapper.readTree(rawPayload).path("repository");
            String repoUrl = repo.path("clone_url").asText(null);
            String repoFullName = repo.path("full_name").asText("unknown");
            if ((repoUrl == null || repoUrl.isBlank()) && "unknown".equals(repoFullName)) {
                return ResponseEntity.badRequest().body(Map.of("error", "No repository info in payload"));
            }
            List<RepoMapping> matches = mappingRepository.findActiveMatchingRepo(repoUrl, repoFullName).stream()
                    .filter(m -> credentialId.equals(m.getSourceCredentialId())
                            || credentialId.equals(m.getTargetCredentialId()))
                    .toList();
            if (matches.isEmpty()) {
                return ResponseEntity.ok(Map.of("status", "ignored",
                        "reason", "No active pair bound to credential " + credentialId + " for " + repoFullName));
            }
            return webhookIngestionService.processGithubDelivery(matches.get(0), eventType, rawPayload);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
