package com.gitutility.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class RabbitMQConfig {

    @Value("${git-utility.queue.exchange:git.sync.exchange}")
    private String exchangeName;

    @Value("${git-utility.queue.main-queue:git.sync.queue}")
    private String mainQueueName;

    @Value("${git-utility.queue.routing-key:git.sync.key}")
    private String routingKey;

    @Value("${git-utility.queue.dlx-exchange:git.sync.dlx}")
    private String dlxExchangeName;

    @Value("${git-utility.queue.dlq-queue:git.sync.dlq}")
    private String dlqQueueName;

    @Value("${git-utility.queue.dlq-routing-key:git.sync.dlq.key}")
    private String dlqRoutingKey;

    @Value("${git-utility.queue.inbound-queue:git.sync.inbound.queue}")
    private String inboundQueueName;

    @Value("${git-utility.queue.inbound-routing-key:git.webhook.inbound}")
    private String inboundRoutingKey;

    @Value("${git-utility.queue.incremental-queue:git.sync.incremental.queue}")
    private String incrementalQueueName;

    @Value("${git-utility.queue.incremental-routing-key:git.sync.incremental.key}")
    private String incrementalRoutingKey;

    @Bean
    public DirectExchange mainExchange() {
        return new DirectExchange(exchangeName, true, false);
    }

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(dlxExchangeName, true, false);
    }

    /**
     * Main queue with Dead-Letter-Exchange (DLX) parameters.
     * When messages fail max retries or get rejected without requeue, RabbitMQ routes them automatically to DLX -> DLQ.
     */
    @Bean
    public Queue mainQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", dlxExchangeName);
        args.put("x-dead-letter-routing-key", dlqRoutingKey);
        return new Queue(mainQueueName, true, false, false, args);
    }

    @Bean
    public Queue dlqQueue() {
        return new Queue(dlqQueueName, true, false, false);
    }

    @Bean
    public Queue inboundQueue() {
        return new Queue(inboundQueueName, true, false, false);
    }

    /**
     * Webhook incremental execution lane. Same DLX as the full-mirror queue so exhausted
     * retries still land in {@code git.sync.dlq}.
     */
    @Bean
    public Queue incrementalQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", dlxExchangeName);
        args.put("x-dead-letter-routing-key", dlqRoutingKey);
        return new Queue(incrementalQueueName, true, false, false, args);
    }

    @Bean
    public Binding mainQueueBinding(Queue mainQueue, DirectExchange mainExchange) {
        return BindingBuilder.bind(mainQueue).to(mainExchange).with(routingKey);
    }

    @Bean
    public Binding incrementalQueueBinding(Queue incrementalQueue, DirectExchange mainExchange) {
        return BindingBuilder.bind(incrementalQueue).to(mainExchange).with(incrementalRoutingKey);
    }

    @Bean
    public Binding dlqBinding(Queue dlqQueue, DirectExchange dlxExchange) {
        return BindingBuilder.bind(dlqQueue).to(dlxExchange).with(dlqRoutingKey);
    }

    @Bean
    public Binding inboundQueueBinding(Queue inboundQueue, DirectExchange mainExchange) {
        return BindingBuilder.bind(inboundQueue).to(mainExchange).with(inboundRoutingKey);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }

    @Value("${git-utility.queue.max-retry-attempts:3}")
    private int maxRetryAttempts;

    @Value("${git-utility.queue.retry-initial-interval-ms:3000}")
    private long retryInitialIntervalMs;

    @Value("${git-utility.queue.retry-multiplier:2.0}")
    private double retryMultiplier;

    @Value("${git-utility.queue.retry-max-interval-ms:30000}")
    private long retryMaxIntervalMs;

    @Bean(name = "rabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        factory.setDefaultRequeueRejected(false);
        // Recoverer that rejects poisoned/max-retried messages without requeueing so DLX captures them
        // Employs exponential backoff: 3s -> 6s -> 12s (capped at 30s)
        factory.setAdviceChain(
            RetryInterceptorBuilder.stateless()
                .maxRetries(maxRetryAttempts)
                .backOffOptions(retryInitialIntervalMs, retryMultiplier, retryMaxIntervalMs)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build()
        );
        return factory;
    }
}
