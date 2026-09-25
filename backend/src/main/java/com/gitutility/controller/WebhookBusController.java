package com.gitutility.controller;

import com.gitutility.messaging.webhook.WebhookBusProvider;
import com.gitutility.messaging.webhook.kafka.KafkaWebhookOps;
import com.gitutility.messaging.webhook.rabbit.RabbitWebhookOps;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhook-bus")
@RequiredArgsConstructor
public class WebhookBusController {

    private final ObjectProvider<KafkaWebhookOps> kafkaOps;
    private final ObjectProvider<RabbitWebhookOps> rabbitOps;

    @Value("${git-utility.webhook-bus.provider:off}")
    private String providerProperty;

    @GetMapping
    public ResponseEntity<Map<String, Object>> status(
            @RequestParam(defaultValue = "false") boolean fresh) {
        WebhookBusProvider provider = WebhookBusProvider.from(providerProperty);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", provider.wireId());
        if (provider == WebhookBusProvider.KAFKA && kafkaOps.getIfAvailable() != null) {
            body.putAll(kafkaOps.getObject().status(fresh));
        } else if (provider == WebhookBusProvider.RABBITMQ && rabbitOps.getIfAvailable() != null) {
            body.putAll(rabbitOps.getObject().status());
        }
        return ResponseEntity.ok(body);
    }

    @PostMapping("/redrive")
    public ResponseEntity<Map<String, Object>> redrive(@RequestParam(defaultValue = "10") int limit) {
        WebhookBusProvider provider = WebhookBusProvider.from(providerProperty);
        int moved;
        if (provider == WebhookBusProvider.KAFKA && kafkaOps.getIfAvailable() != null) {
            moved = kafkaOps.getObject().redrive(limit);
        } else if (provider == WebhookBusProvider.RABBITMQ && rabbitOps.getIfAvailable() != null) {
            moved = rabbitOps.getObject().redrive(limit);
        } else {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Webhook bus is off. Set GIT_WEBHOOK_BUS_PROVIDER=kafka or rabbitmq."));
        }
        return ResponseEntity.ok(Map.of("redriven", moved));
    }
}
