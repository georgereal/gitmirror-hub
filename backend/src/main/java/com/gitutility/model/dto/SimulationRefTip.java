package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationRefTip {
    private boolean known;
    private boolean present;
    private String sha;
    private String ref;
}
