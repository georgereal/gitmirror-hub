package com.gitutility.messaging.webhook.rabbit;

import com.gitutility.messaging.webhook.WebhookBusConditions;
import com.gitutility.messaging.webhook.WebhookBusControls;
import com.gitutility.messaging.webhook.WebhookEventPublisher;
import com.gitutility.messaging.webhook.WebhookIncrementalService;
import com.gitutility.model.dto.IncrementalGitEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@WebhookBusConditions.OnRabbit
@Slf4j
public class RabbitWebhookListener implements WebhookBusControls {

    static final String LISTENER_ID = "webhookIncrementalRabbit";

    private final WebhookIncrementalService webhookIncrementalService;
    private final WebhookEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final RabbitListenerEndpointRegistry registry;

    public RabbitWebhookListener(WebhookIncrementalService webhookIncrementalService,
                                 WebhookEventPublisher publisher,
                                 ObjectMapper objectMapper,
                                 RabbitListenerEndpointRegistry registry) {
        this.webhookIncrementalService = webhookIncrementalService;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    @RabbitListener(
            id = LISTENER_ID,
            queues = "${git-utility.webhook-bus.rabbitmq.queue}",
            containerFactory = "webhookRabbitListenerContainerFactory"
    )
    public void onMessage(String body) {
        IncrementalGitEvent event;
        try {
            event = objectMapper.readValue(body, IncrementalGitEvent.class);
        } catch (Exception e) {
            log.warn("Discarding unreadable incremental Rabbit message: {}", e.getMessage());
            IncrementalGitEvent poison = new IncrementalGitEvent();
            poison.setError(e.getMessage());
            publisher.deadLetter(poison, e.getMessage());
            return;
        }
        webhookIncrementalService.handle(event);
    }

    @Override
    public void pause() {
        var container = registry.getListenerContainer(LISTENER_ID);
        if (container != null && container.isRunning()) {
            container.stop();
            log.info("Paused Rabbit incremental webhook listener");
        }
    }

    @Override
    public void resume() {
        var container = registry.getListenerContainer(LISTENER_ID);
        if (container != null && !container.isRunning()) {
            container.start();
            log.info("Resumed Rabbit incremental webhook listener");
        }
    }
}
