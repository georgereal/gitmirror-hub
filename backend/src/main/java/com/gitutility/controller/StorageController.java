package com.gitutility.controller;

import com.gitutility.model.dto.StorageStatusResponse;
import com.gitutility.service.StorageTieringService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageTieringService storageTieringService;

    @GetMapping("/status")
    public ResponseEntity<StorageStatusResponse> getStorageStatus() {
        return ResponseEntity.ok(storageTieringService.getStorageStatus());
    }

    @PostMapping("/evict-now")
    public ResponseEntity<Map<String, String>> triggerEviction() {
        storageTieringService.checkAndEvictLruCache();
        return ResponseEntity.ok(Map.of("message", "LRU cache evaluation completed"));
    }
}
