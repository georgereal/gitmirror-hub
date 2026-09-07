package com.gitutility.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "instance_heartbeats")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstanceHeartbeat {

    @Id
    @Column(name = "instance_id", length = 255)
    private String instanceId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Lob
    @Column(name = "payload_json", nullable = false, columnDefinition = "CLOB")
    private String payloadJson;
}
