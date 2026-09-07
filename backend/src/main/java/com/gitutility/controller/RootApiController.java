package com.gitutility.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class RootApiController {

    @GetMapping({"/", "/api", "/api/v1"})
    public ResponseEntity<Map<String, Object>> getApiRootInfo() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("service", "GitMirror Hub API");
        response.put("version", "1.0.0");
        response.put("status", "UP");
        response.put("timestamp", Instant.now().toString());

        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("github_app_config", "/api/v1/github-app/config");
        endpoints.put("github_app_test_connection", "/api/v1/github-app/test-connection");
        endpoints.put("github_app_repositories", "/api/v1/github-app/repositories");
        endpoints.put("mappings", "/api/v1/mappings");
        endpoints.put("recent_jobs", "/api/v1/jobs/recent");
        endpoints.put("job_stats", "/api/v1/jobs/stats");
        endpoints.put("queue_status", "/api/v1/queue/status");
        endpoints.put("runtime_metrics", "/api/v1/runtime-metrics");
        endpoints.put("runtime_metrics_cluster", "/api/v1/runtime-metrics/cluster");
        endpoints.put("scm_quotas", "/api/v1/scm-quotas");

        endpoints.put("simulation_state", "/api/v1/simulation/state");
        endpoints.put("github_webhook", "/api/v1/webhooks/github/{mappingId}");
        endpoints.put("websocket", "/ws");
        endpoints.put("h2_console", "/h2-console");

        response.put("endpoints", endpoints);
        response.put("documentation", "See ARCHITECTURE.md and REPO_MAP.md for complete API schemas");

        return ResponseEntity.ok(response);
    }
}
