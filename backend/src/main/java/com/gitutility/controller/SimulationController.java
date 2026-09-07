package com.gitutility.controller;

import com.gitutility.model.dto.SimulationConfigRequest;
import com.gitutility.model.dto.SyntheticWebhookRequest;
import com.gitutility.model.entity.SyncJob;
import com.gitutility.service.ClusterRuntimeService;
import com.gitutility.service.SimulationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/simulation")
@RequiredArgsConstructor
public class SimulationController {

    private final SimulationService simulationService;
    private final ClusterRuntimeService clusterRuntimeService;

    @GetMapping("/state")
    public ResponseEntity<Map<String, Object>> getSimulationState() {
        return ResponseEntity.ok(simulationService.getSimulationState());
    }

    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody SimulationConfigRequest config) {
        if (config.getConsumerPaused() != null) {
            clusterRuntimeService.setConsumersPaused(config.getConsumerPaused());
        }
        simulationService.updateSimulationConfig(config);
        return ResponseEntity.ok(simulationService.getSimulationState());
    }

    @PostMapping("/consumer/pause")
    public ResponseEntity<Map<String, Object>> pauseConsumer() {
        clusterRuntimeService.setConsumersPaused(true);
        simulationService.pauseConsumer();
        return ResponseEntity.ok(Map.of("consumerPaused", true, "message", "Sync consumer listener paused cluster-wide. New events will queue without processing."));
    }

    @PostMapping("/consumer/resume")
    public ResponseEntity<Map<String, Object>> resumeConsumer() {
        clusterRuntimeService.setConsumersPaused(false);
        simulationService.resumeConsumer();
        return ResponseEntity.ok(Map.of("consumerPaused", false, "message", "Sync consumer listener resumed cluster-wide. Processing queued backlog."));
    }

    @PostMapping("/emit-webhook")
    public ResponseEntity<SyncJob> emitSyntheticWebhook(@RequestBody SyntheticWebhookRequest request) {
        SyncJob job = simulationService.emitSyntheticWebhook(request);
        return ResponseEntity.accepted().body(job);
    }
}
