package com.gitutility.controller;

import com.gitutility.model.entity.UnmappedWebhookEvent;
import com.gitutility.repository.UnmappedWebhookEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/unmapped-webhooks")
@RequiredArgsConstructor
public class UnmappedWebhookController {

    private final UnmappedWebhookEventRepository unmappedWebhookEventRepository;

    @GetMapping
    public ResponseEntity<List<UnmappedWebhookEvent>> getUnmappedEvents() {
        return ResponseEntity.ok(unmappedWebhookEventRepository.findTop100ByOrderByReceivedAtDesc());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEvent(@PathVariable Long id) {
        unmappedWebhookEventRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Map<String, String>> clearAllEvents() {
        unmappedWebhookEventRepository.deleteAll();
        return ResponseEntity.ok(Map.of("message", "Cleared all unmapped webhook events"));
    }
}
