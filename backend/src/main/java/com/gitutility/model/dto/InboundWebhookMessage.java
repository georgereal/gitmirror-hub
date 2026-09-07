package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboundWebhookMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String provider; // "github", "gitlab", "bitbucket"
    private Long mappingId;
    private String eventType;
    private String deliveryId;
    private Map<String, String> headers;
    private String rawPayload;
    private String signature;
    @Builder.Default
    private Instant receivedAt = Instant.now();
}
