package com.gitutility.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Singleton cluster control-plane row (id = 1) shared by all Hub pods.
 */
@Entity
@Table(name = "cluster_runtime")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterRuntime {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(nullable = false)
    @Builder.Default
    private boolean consumersPaused = false;

    @Column(nullable = false, length = 32)
    @Builder.Default
    private String circuitState = "CLOSED";

    @Column(nullable = false)
    @Builder.Default
    private int consecutiveFailures = 0;

    @Column(length = 2000)
    private String lastProbeMessage;

    @Column(nullable = false)
    @Builder.Default
    private boolean lastProbeSuccess = true;

    @Column(length = 255)
    private String updatedByInstance;

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
