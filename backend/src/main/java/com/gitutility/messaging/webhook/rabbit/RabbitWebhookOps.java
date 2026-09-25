package com.gitutility.messaging.webhook.rabbit;

import com.gitutility.messaging.webhook.WebhookBusConditions;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@WebhookBusConditions.OnRabbit
public class RabbitWebhookOps {

    private final RabbitTemplate webhookRabbitTemplate;
    private final RabbitWebhookConfig.RabbitWebhookTopology topology;

    public RabbitWebhookOps(RabbitTemplate webhookRabbitTemplate,
                            RabbitWebhookConfig.RabbitWebhookTopology topology) {
        this.webhookRabbitTemplate = webhookRabbitTemplate;
        this.topology = topology;
    }

    public Map<String, Object> status() {
        Map<String, Object> body = new HashMap<>();
        body.put("queue", topology.queue());
        body.put("exchange", topology.exchange());
        body.put("routingKey", topology.routingKey());
        body.put("deadLetterQueue", topology.dlq());
        return body;
    }

    public int redrive(int limit) {
        int cap = Math.max(1, Math.min(limit, 100));
        int moved = 0;
        while (moved < cap) {
            Message message = webhookRabbitTemplate.receive(topology.dlq(), 200);
            if (message == null) {
                break;
            }
            webhookRabbitTemplate.send(topology.exchange(), topology.routingKey(), message);
            moved++;
        }
        return moved;
    }
}
