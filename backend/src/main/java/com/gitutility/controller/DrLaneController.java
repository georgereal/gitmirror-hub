package com.gitutility.controller;

import com.gitutility.model.dto.DrLaneResponse;
import com.gitutility.service.DrLaneService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dr-lanes")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DrLaneController {

    private final DrLaneService drLaneService;

    @GetMapping
    public ResponseEntity<List<DrLaneResponse>> list() {
        return ResponseEntity.ok(drLaneService.list());
    }

    @PostMapping("/activate")
    public ResponseEntity<DrLaneResponse> activate(@RequestBody LaneRequest request) {
        return ResponseEntity.ok(drLaneService.activate(requireKey(request)));
    }

    @PostMapping("/fail-back")
    public ResponseEntity<DrLaneResponse> failBack(@RequestBody LaneRequest request) {
        return ResponseEntity.ok(drLaneService.failBack(requireKey(request)));
    }

    @PostMapping("/probe")
    public ResponseEntity<DrLaneResponse> probe(@RequestBody LaneRequest request) {
        return ResponseEntity.ok(drLaneService.probe(requireKey(request)));
    }

    private static String requireKey(LaneRequest request) {
        if (request == null || request.getLaneKey() == null || request.getLaneKey().isBlank()) {
            throw new IllegalArgumentException("laneKey is required.");
        }
        return request.getLaneKey();
    }

    @Data
    public static class LaneRequest {
        private String laneKey;
    }
}
