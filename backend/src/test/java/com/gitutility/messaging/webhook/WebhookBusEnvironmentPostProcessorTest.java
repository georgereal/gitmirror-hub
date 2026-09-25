package com.gitutility.messaging.webhook;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookBusEnvironmentPostProcessorTest {

    @Test
    void offExcludesKafkaAutoConfig() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("GIT_WEBHOOK_BUS_PROVIDER", "off");

        new WebhookBusEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

        assertEquals("off", env.getProperty("git-utility.webhook-bus.provider"));
        String excluded = env.getProperty("spring.autoconfigure.exclude");
        assertTrue(excluded.contains(WebhookBusEnvironmentPostProcessor.KAFKA_AUTO_CONFIGURATION));
    }

    @Test
    void kafkaRequiresBootstrapServers() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("GIT_WEBHOOK_BUS_PROVIDER", "kafka");

        assertThrows(IllegalStateException.class,
                () -> new WebhookBusEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication()));
    }

    @Test
    void kafkaCopiesTopicAndGroupOntoSpringKafka() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("GIT_WEBHOOK_BUS_PROVIDER", "kafka");
        env.setProperty("GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        env.setProperty("GIT_WEBHOOK_KAFKA_GROUP_ID", "acme-hub");
        env.setProperty("git-utility.webhook-bus.kafka.incremental-topic", "acme.git.incremental");

        new WebhookBusEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

        assertEquals("kafka", env.getProperty("git-utility.webhook-bus.provider"));
        assertEquals("localhost:9092", env.getProperty("spring.kafka.bootstrap-servers"));
        assertEquals("acme-hub", env.getProperty("spring.kafka.consumer.group-id"));
        assertEquals("false", env.getProperty("spring.kafka.consumer.enable-auto-commit"));
    }

    @Test
    void rabbitRejectsQueueThatMatchesFullMirrorLane() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("GIT_MESSAGING_PROVIDER", "rabbitmq");
        env.setProperty("GIT_WEBHOOK_BUS_PROVIDER", "rabbitmq");
        env.setProperty("GIT_WEBHOOK_RABBITMQ_ADDRESSES", "amqp://localhost");
        env.setProperty("git-utility.queue.main-queue", "git.sync.queue");
        env.setProperty("GIT_WEBHOOK_RABBITMQ_QUEUE", "git.sync.queue");

        assertThrows(IllegalStateException.class,
                () -> new WebhookBusEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication()));
    }
}
