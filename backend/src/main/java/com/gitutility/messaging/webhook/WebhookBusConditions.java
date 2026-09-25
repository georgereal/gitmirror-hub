package com.gitutility.messaging.webhook;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public final class WebhookBusConditions {

    private WebhookBusConditions() {
    }

    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @ConditionalOnProperty(name = "git-utility.webhook-bus.provider", havingValue = "kafka")
    public @interface OnKafka {
    }

    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @ConditionalOnProperty(name = "git-utility.webhook-bus.provider", havingValue = "rabbitmq")
    public @interface OnRabbit {
    }
}
