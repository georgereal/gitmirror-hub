package com.gitutility.messaging.webhook.rabbit;

import com.gitutility.messaging.webhook.WebhookBusConditions;
import com.gitutility.messaging.webhook.WebhookEventPublisher;
import com.gitutility.model.dto.IncrementalGitEvent;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@WebhookBusConditions.OnRabbit
public class RabbitWebhookPublisher implements WebhookEventPublisher {

    private final RabbitTemplate webhookRabbitTemplate;
    private final RabbitWebhookConfig.RabbitWebhookTopology topology;
    private final ObjectMapper objectMapper;

    public RabbitWebhookPublisher(RabbitTemplate webhookRabbitTemplate,
                                  RabbitWebhookConfig.RabbitWebhookTopology topology,
                                  ObjectMapper objectMapper) {
        this.webhookRabbitTemplate = webhookRabbitTemplate;
        this.topology = topology;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(IncrementalGitEvent event) {
        send(topology.exchange(), topology.routingKey(), event);
    }

    @Override
    public void deadLetter(IncrementalGitEvent event, String reason) {
        if (event != null && (event.getError() == null || event.getError().isBlank())) {
            event.setError(reason);
        }
        send("", topology.dlq(), event);
    }

    @Override
    public String destination() {
        return topology.queue();
    }

    private void send(String exchange, String routingKey, IncrementalGitEvent event) {
        try {
            webhookRabbitTemplate.convertAndSend(exchange, routingKey, objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            throw new IllegalStateException("Rabbit publish to " + routingKey + " failed: " + e.getMessage(), e);
        }
    }
}
