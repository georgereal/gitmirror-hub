package com.gitutility.controller;

import com.gitutility.model.dto.FeatureFlagsRequest;
import com.gitutility.model.dto.FeatureFlagsResponse;
import com.gitutility.service.FeatureFlagsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/feature-flags")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class FeatureFlagsController {

    private final FeatureFlagsService featureFlagsService;

    @GetMapping
    public ResponseEntity<FeatureFlagsResponse> get() {
        return ResponseEntity.ok(featureFlagsService.getResponse());
    }

    @PutMapping
    public ResponseEntity<FeatureFlagsResponse> update(@RequestBody FeatureFlagsRequest request) {
        log.info("Updating feature flags: {}", request);
        return ResponseEntity.ok(featureFlagsService.update(request));
    }

    /** Alias for clients that POST like system-config. */
    @PostMapping
    public ResponseEntity<FeatureFlagsResponse> updatePost(@RequestBody FeatureFlagsRequest request) {
        return update(request);
    }
}
