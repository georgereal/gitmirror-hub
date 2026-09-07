package com.gitutility.controller;

import com.gitutility.model.dto.SystemEngineConfigRequest;
import com.gitutility.model.dto.SystemEngineConfigResponse;
import com.gitutility.service.CircuitBreakerManagerService;
import com.gitutility.service.SystemEngineConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/system-config")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class SystemEngineConfigController {

    private final SystemEngineConfigService systemEngineConfigService;
    private final CircuitBreakerManagerService circuitBreakerManagerService;
    private final com.gitutility.service.EnterpriseLoggingService enterpriseLoggingService;

    @GetMapping
    public ResponseEntity<SystemEngineConfigResponse> getConfig() {
        return ResponseEntity.ok(systemEngineConfigService.getConfigResponse());
    }

    @PostMapping
    public ResponseEntity<SystemEngineConfigResponse> updateConfig(@RequestBody SystemEngineConfigRequest request) {
        log.info("Received request to update System Engine Configuration: {}", request);
        SystemEngineConfigResponse response = systemEngineConfigService.updateConfig(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/test-nas-path")
    public ResponseEntity<Map<String, Object>> testNasPath(@RequestBody Map<String, String> payload) {
        String pathStr = payload.get("path");
        Map<String, Object> result = new HashMap<>();

        if (pathStr == null || pathStr.isBlank()) {
            result.put("valid", false);
            result.put("message", "Path cannot be empty");
            return ResponseEntity.badRequest().body(result);
        }

        try {
            Path path = Paths.get(pathStr.trim());
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }

            File dir = path.toFile();
            boolean readable = Files.isReadable(path);
            boolean writable = Files.isWritable(path);
            long freeSpaceMb = dir.getUsableSpace() / (1024 * 1024);
            long totalSpaceMb = dir.getTotalSpace() / (1024 * 1024);

            // Test creating a temp test file
            Path testFile = path.resolve(".gitutility-mount-test-" + System.currentTimeMillis());
            Files.writeString(testFile, "mount-check-ok");
            Files.deleteIfExists(testFile);

            result.put("valid", readable && writable);
            result.put("path", dir.getAbsolutePath());
            result.put("readable", readable);
            result.put("writable", writable);
            result.put("freeSpaceMb", freeSpaceMb);
            result.put("totalSpaceMb", totalSpaceMb);
            result.put("message", "NAS mount path is accessible and writable with " + (freeSpaceMb / 1024) + " GB usable space.");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.warn("NAS path test failed for '{}': {}", pathStr, e.getMessage());
            result.put("valid", false);
            result.put("path", pathStr);
            result.put("message", "Access error: " + e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    @PostMapping("/circuit-breaker/probe-and-reset")
    public ResponseEntity<Map<String, Object>> probeAndResetCircuitBreaker(
            @RequestParam(defaultValue = "false") boolean forceReset) {
        log.info("Admin triggered circuit breaker probe and reset (forceReset={})", forceReset);
        CircuitBreakerManagerService.ProbeResult probeResult = circuitBreakerManagerService.probeAndReset(forceReset);

        Map<String, Object> response = new HashMap<>();
        response.put("success", probeResult.success());
        response.put("message", probeResult.message());
        response.put("circuitState", circuitBreakerManagerService.getState().name());
        response.put("currentFailures", circuitBreakerManagerService.getCurrentConsecutiveFailures().get());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/test-logging-sink")
    public ResponseEntity<Map<String, Object>> testLoggingSink(@RequestBody Map<String, String> payload) {
        String sink = payload.get("sink");
        String url = payload.get("url");
        String token = payload.get("token");
        String host = payload.get("host");
        String filePath = payload.get("filePath");

        log.info("Testing logging sink connection for sink={}", sink);
        var result = enterpriseLoggingService.testLoggingSink(sink, url, token, host, filePath);

        Map<String, Object> response = new HashMap<>();
        response.put("success", result.success());
        response.put("statusCode", result.statusCode());
        response.put("message", result.message());
        response.put("latencyMs", result.latencyMs());

        return ResponseEntity.ok(response);
    }
}
