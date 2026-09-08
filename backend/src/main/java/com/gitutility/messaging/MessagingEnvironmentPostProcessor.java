package com.gitutility.messaging;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes {@code GIT_MESSAGING_PROVIDER} to a canonical wire id ({@code rabbitmq}|{@code kafka}|{@code none}),
 * excludes AMQP autoconfig for {@code none}, and fails fast when {@code kafka} is selected (not implemented yet).
 */
public class MessagingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String RABBIT_AUTO_CONFIGURATION =
            "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = firstNonBlank(
                environment.getProperty("git-utility.messaging.provider"),
                environment.getProperty("GIT_MESSAGING_PROVIDER"),
                "rabbitmq"
        );
        MessagingProvider provider = MessagingProvider.from(raw);

        if (provider == MessagingProvider.KAFKA) {
            throw new IllegalStateException(
                    "GIT_MESSAGING_PROVIDER=kafka is reserved but not implemented yet. "
                            + "Use rabbitmq (durable AMQP) or none (in-process). "
                            + "See future-work/kafka-mirroring-partitions.md");
        }

        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("git-utility.messaging.provider", provider.wireId());

        if (provider == MessagingProvider.NONE) {
            appendExclude(environment, overrides, RABBIT_AUTO_CONFIGURATION);
            if (!environment.containsProperty("git-utility.queue.pause-consumers-on-startup")
                    && System.getenv("GIT_QUEUE_PAUSE_ON_STARTUP") == null) {
                overrides.put("git-utility.queue.pause-consumers-on-startup", "false");
            }
        }

        environment.getPropertySources().addFirst(new MapPropertySource("gitUtilityMessaging", overrides));
    }

    private static void appendExclude(
            ConfigurableEnvironment environment,
            Map<String, Object> overrides,
            String className
    ) {
        String existing = environment.getProperty("spring.autoconfigure.exclude", "");
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
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
