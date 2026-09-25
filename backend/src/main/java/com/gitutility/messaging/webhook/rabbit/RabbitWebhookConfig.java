package com.gitutility.messaging.webhook.rabbit;

import com.gitutility.messaging.webhook.WebhookBusConditions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@WebhookBusConditions.OnRabbit
@Slf4j
public class RabbitWebhookConfig {

    @Bean
    public CachingConnectionFactory webhookRabbitConnectionFactory(
            @Value("${git-utility.webhook-bus.rabbitmq.addresses}") String addresses) throws Exception {
        CachingConnectionFactory factory = new CachingConnectionFactory();
        factory.setUri(addresses);
        return factory;
    }

    @Bean
    public RabbitTemplate webhookRabbitTemplate(CachingConnectionFactory webhookRabbitConnectionFactory) {
        return new RabbitTemplate(webhookRabbitConnectionFactory);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory webhookRabbitListenerContainerFactory(
            CachingConnectionFactory webhookRabbitConnectionFactory,
            @Value("${git-utility.webhook-bus.rabbitmq.prefetch:1}") int prefetch,
            @Value("${git-utility.webhook-bus.rabbitmq.concurrency:1}") int concurrency) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(webhookRabbitConnectionFactory);
        factory.setPrefetchCount(Math.max(1, prefetch));
        factory.setConcurrentConsumers(Math.max(1, concurrency));
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        return factory;
    }

    @Bean
    public RabbitAdmin webhookRabbitAdmin(CachingConnectionFactory webhookRabbitConnectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(webhookRabbitConnectionFactory);
        admin.setExplicitDeclarationsOnly(true);
        return admin;
    }

    @Bean
    public RabbitWebhookTopology webhookRabbitTopology(
            RabbitAdmin webhookRabbitAdmin,
            @Value("${git-utility.webhook-bus.rabbitmq.exchange}") String exchange,
            @Value("${git-utility.webhook-bus.rabbitmq.queue}") String queue,
            @Value("${git-utility.webhook-bus.rabbitmq.routing-key}") String routingKey,
            @Value("${git-utility.webhook-bus.rabbitmq.dlx}") String dlx,
            @Value("${git-utility.webhook-bus.rabbitmq.dlq}") String dlq) {
        return new RabbitWebhookTopology(webhookRabbitAdmin, exchange, queue, routingKey, dlx, dlq);
    }

    public static final class RabbitWebhookTopology {
        private final String exchange;
        private final String queue;
        private final String routingKey;
        private final String dlq;

        public RabbitWebhookTopology(RabbitAdmin admin, String exchange, String queue,
                                      String routingKey, String dlx, String dlq) {
            this.exchange = exchange;
            this.queue = queue;
            this.routingKey = routingKey;
            this.dlq = dlq;
            DirectExchange ex = new DirectExchange(exchange, true, false);
            DirectExchange dead = new DirectExchange(dlx, true, false);
            var main = QueueBuilder.durable(queue).deadLetterExchange(dlx).deadLetterRoutingKey(dlq).build();
            var poison = QueueBuilder.durable(dlq).build();
            admin.declareExchange(ex);
            admin.declareExchange(dead);
            admin.declareQueue(main);
            admin.declareQueue(poison);
            admin.declareBinding(BindingBuilder.bind(main).to(ex).with(routingKey));
            admin.declareBinding(BindingBuilder.bind(poison).to(dead).with(dlq));
            log.info("Webhook Rabbit lane declared: exchange={} queue={}", exchange, queue);
        }

        public String exchange() {
            return exchange;
        }

        public String queue() {
            return queue;
        }

        public String routingKey() {
            return routingKey;
        }

        public String dlq() {
            return dlq;
        }
    }
}
