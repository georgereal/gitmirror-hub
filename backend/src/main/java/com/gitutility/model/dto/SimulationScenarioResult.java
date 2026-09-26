package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationScenarioResult {
    private String status;
    private String event;
    private String side;
    private String repoUrl;
    private String summary;
}
