package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationConfigRequest {
    private Boolean consumerPaused;
    private Boolean simulateTargetDown;
    private Boolean simulateSourceDown;
    private Boolean simulateRateLimit;
    private Long artificialDelayMs;
}
