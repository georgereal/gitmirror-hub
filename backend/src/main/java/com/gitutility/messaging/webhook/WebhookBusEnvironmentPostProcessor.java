package com.gitutility.messaging.webhook;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes {@code GIT_WEBHOOK_BUS_PROVIDER} and fails fast when Kafka or Rabbit
 * is selected without a broker address. Topic, queue, and group names stay in configuration.
 */
public class WebhookBusEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String KAFKA_AUTO_CONFIGURATION =
            "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration";
    public static final String KAFKA_METRICS_AUTO_CONFIGURATION =
            "org.springframework.boot.kafka.autoconfigure.metrics.KafkaMetricsAutoConfiguration";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = firstNonBlank(
                environment.getProperty("git-utility.webhook-bus.provider"),
                environment.getProperty("GIT_WEBHOOK_BUS_PROVIDER"),
                "off");
        WebhookBusProvider provider = WebhookBusProvider.from(raw);

        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("git-utility.webhook-bus.provider", provider.wireId());

        if (provider == WebhookBusProvider.OFF) {
            appendExclude(environment, overrides, KAFKA_AUTO_CONFIGURATION);
            appendExclude(environment, overrides, KAFKA_METRICS_AUTO_CONFIGURATION);
        } else if (provider == WebhookBusProvider.KAFKA) {
            applyKafka(environment, overrides);
        } else {
            applyRabbit(environment, overrides);
        }

        environment.getPropertySources().addFirst(new MapPropertySource("gitUtilityWebhookBus", overrides));
    }

    private static void applyKafka(ConfigurableEnvironment environment, Map<String, Object> overrides) {
        String bootstrap = firstNonBlank(
                environment.getProperty("git-utility.webhook-bus.kafka.bootstrap-servers"),
                environment.getProperty("GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS"));
        if (bootstrap == null) {
            throw new IllegalStateException(
                    "GIT_WEBHOOK_BUS_PROVIDER=kafka requires GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS");
        }
        String protocol = firstNonBlank(
                environment.getProperty("git-utility.webhook-bus.kafka.security-protocol"),
                environment.getProperty("GIT_WEBHOOK_KAFKA_SECURITY_PROTOCOL"),
                "PLAINTEXT");
        String mechanism = firstNonBlank(
                environment.getProperty("git-utility.webhook-bus.kafka.sasl-mechanism"),
                environment.getProperty("GIT_WEBHOOK_KAFKA_SASL_MECHANISM"),
                "PLAIN");
        String username = firstNonBlank(
                environment.getProperty("git-utility.webhook-bus.kafka.sasl-username"),
                environment.getProperty("GIT_WEBHOOK_KAFKA_SASL_USERNAME"));
        String password = firstNonBlank(
                environment.getProperty("git-utility.webhook-bus.kafka.sasl-password"),
                environment.getProperty("GIT_WEBHOOK_KAFKA_SASL_PASSWORD"),
                "");
        if ("SASL_SSL".equalsIgnoreCase(protocol) || "SASL_PLAINTEXT".equalsIgnoreCase(protocol)) {
            if (username == null || username.isBlank()) {
                throw new IllegalStateException(
                        "Kafka SASL requires GIT_WEBHOOK_KAFKA_SASL_USERNAME and GIT_WEBHOOK_KAFKA_SASL_PASSWORD");
            }
        }
        String group = firstNonBlank(
                environment.getProperty("git-utility.webhook-bus.kafka.group-id"),
                environment.getProperty("GIT_WEBHOOK_KAFKA_GROUP_ID"),
                "git-mirror-hub");
        String clientId = firstNonBlank(
                environment.getProperty("git-utility.webhook-bus.kafka.client-id"),
                environment.getProperty("GIT_WEBHOOK_KAFKA_CLIENT_ID"),
                "git-mirror-hub");
        String offsetReset = firstNonBlank(
                environment.getProperty("git-utility.webhook-bus.kafka.auto-offset-reset"),
                environment.getProperty("GIT_WEBHOOK_KAFKA_AUTO_OFFSET_RESET"),
                "earliest");
        String maxPoll = firstNonBlank(
                environment.getProperty("git-utility.webhook-bus.kafka.max-poll-interval-ms"),
                environment.getProperty("GIT_WEBHOOK_KAFKA_MAX_POLL_INTERVAL_MS"),
                "600000");
        String sessionTimeout = firstNonBlank(
                environment.getProperty("git-utility.webhook-bus.kafka.session-timeout-ms"),
                environment.getProperty("GIT_WEBHOOK_KAFKA_SESSION_TIMEOUT_MS"),
                "45000");

        overrides.put("spring.kafka.bootstrap-servers", bootstrap);
        overrides.put("spring.kafka.client-id", clientId);
        overrides.put("spring.kafka.consumer.group-id", group);
        overrides.put("spring.kafka.consumer.auto-offset-reset", offsetReset);
        overrides.put("spring.kafka.consumer.enable-auto-commit", "false");
        overrides.put("spring.kafka.consumer.key-deserializer",
                "org.apache.kafka.common.serialization.StringDeserializer");
        overrides.put("spring.kafka.consumer.value-deserializer",
                "org.apache.kafka.common.serialization.StringDeserializer");
        overrides.put("spring.kafka.producer.key-serializer",
                "org.apache.kafka.common.serialization.StringSerializer");
        overrides.put("spring.kafka.producer.value-serializer",
                "org.apache.kafka.common.serialization.StringSerializer");
        overrides.put("spring.kafka.consumer.properties.max.poll.interval.ms", maxPoll);
        overrides.put("spring.kafka.consumer.properties.session.timeout.ms", sessionTimeout);
        overrides.put("spring.kafka.consumer.properties.partition.assignment.strategy",
                "org.apache.kafka.clients.consumer.CooperativeStickyAssignor");
        overrides.put("spring.kafka.properties.security.protocol", protocol);
        overrides.put("spring.kafka.listener.ack-mode", "manual");
        if (username != null && !username.isBlank()) {
            overrides.put("spring.kafka.properties.sasl.mechanism", mechanism);
            overrides.put("spring.kafka.properties.sasl.jaas.config",
                    "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
                            + username.replace("\"", "\\\"")
                            + "\" password=\""
                            + password.replace("\"", "\\\"")
                            + "\";");
        }
    }

    private static void applyRabbit(ConfigurableEnvironment environment, Map<String, Object> overrides) {
        String addresses = firstNonBlank(
                environment.getProperty("git-utility.webhook-bus.rabbitmq.addresses"),
                environment.getProperty("GIT_WEBHOOK_RABBITMQ_ADDRESSES"),
                environment.getProperty("SPRING_RABBITMQ_ADDRESSES"),
                environment.getProperty("spring.rabbitmq.addresses"));
        if (addresses == null) {
            throw new IllegalStateException(
                    "GIT_WEBHOOK_BUS_PROVIDER=rabbitmq requires GIT_WEBHOOK_RABBITMQ_ADDRESSES "
                            + "or SPRING_RABBITMQ_ADDRESSES");
        }
        String webhookQueue = firstNonBlank(
                environment.getProperty("git-utility.webhook-bus.rabbitmq.queue"),
                environment.getProperty("GIT_WEBHOOK_RABBITMQ_QUEUE"),
                "git.sync.incremental.queue");
        String messaging = firstNonBlank(
                environment.getProperty("git-utility.messaging.provider"),
                environment.getProperty("GIT_MESSAGING_PROVIDER"),
                "rabbitmq");
        if ("rabbitmq".equalsIgnoreCase(messaging)) {
            String fullQueue = firstNonBlank(
                    environment.getProperty("git-utility.queue.main-queue"),
                    "git.sync.queue");
            String incrementalQueue = firstNonBlank(
                    environment.getProperty("git-utility.queue.incremental-queue"),
                    "git.sync.incremental.queue");
            if (webhookQueue.equals(fullQueue) || webhookQueue.equals(incrementalQueue)) {
                throw new IllegalStateException(
                        "GIT_WEBHOOK_RABBITMQ_QUEUE must differ from the full-mirror and incremental "
                                + "execution queues when GIT_MESSAGING_PROVIDER=rabbitmq (both are '"
                                + webhookQueue + "')");
            }
        }
        overrides.put("git-utility.webhook-bus.rabbitmq.addresses", addresses);
        overrides.put("git-utility.webhook-bus.rabbitmq.queue", webhookQueue);
        if (environment.getProperty("spring.rabbitmq.addresses") == null
                && environment.getProperty("SPRING_RABBITMQ_ADDRESSES") == null) {
            overrides.put("spring.rabbitmq.addresses", addresses);
        }
    }

    private static void appendExclude(
            ConfigurableEnvironment environment,
            Map<String, Object> overrides,
            String className
    ) {
        String existing = firstNonBlank(overrides.get("spring.autoconfigure.exclude") == null
                ? null : String.valueOf(overrides.get("spring.autoconfigure.exclude")),
                environment.getProperty("spring.autoconfigure.exclude"),
                "");
        List<String> excludes = new ArrayList<>();
        if (existing != null && !existing.isBlank()) {
            Arrays.stream(existing.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(excludes::add);
        }
        if (!excludes.contains(className)) {
            excludes.add(className);
        }
        overrides.put("spring.autoconfigure.exclude", String.join(",", excludes));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 25;
    }
}
