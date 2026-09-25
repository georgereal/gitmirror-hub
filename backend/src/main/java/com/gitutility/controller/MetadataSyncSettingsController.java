package com.gitutility.controller;

import com.gitutility.model.dto.MetadataSyncSettingsRequest;
import com.gitutility.model.dto.MetadataSyncSettingsResponse;
import com.gitutility.service.MetadataSyncSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/metadata-sync-settings")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class MetadataSyncSettingsController {

    private final MetadataSyncSettingsService metadataSyncSettingsService;

    @GetMapping
    public ResponseEntity<MetadataSyncSettingsResponse> get() {
        return ResponseEntity.ok(metadataSyncSettingsService.getResponse());
    }

    @PutMapping
    public ResponseEntity<MetadataSyncSettingsResponse> update(@RequestBody MetadataSyncSettingsRequest request) {
        log.info("Updating metadata sync settings: {}", request);
        return ResponseEntity.ok(metadataSyncSettingsService.update(request));
    }

    @PostMapping
    public ResponseEntity<MetadataSyncSettingsResponse> updatePost(@RequestBody MetadataSyncSettingsRequest request) {
        return update(request);
    }
}
