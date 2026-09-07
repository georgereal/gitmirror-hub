package com.gitutility.controller;

import com.gitutility.model.dto.ClusterRuntimeMetricsResponse;
import com.gitutility.model.dto.RuntimeMetricsResponse;
import com.gitutility.service.InstanceHeartbeatService;
import com.gitutility.service.RuntimeMetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/runtime-metrics")
@RequiredArgsConstructor
public class RuntimeMetricsController {

    private final RuntimeMetricsService runtimeMetricsService;
    private final InstanceHeartbeatService instanceHeartbeatService;

    @GetMapping
    public ResponseEntity<RuntimeMetricsResponse> getRuntimeMetrics() {
        return ResponseEntity.ok(runtimeMetricsService.snapshot());
    }

    @GetMapping("/cluster")
    public ResponseEntity<ClusterRuntimeMetricsResponse> getClusterRuntimeMetrics() {
        return ResponseEntity.ok(instanceHeartbeatService.clusterSnapshot());
    }
}
