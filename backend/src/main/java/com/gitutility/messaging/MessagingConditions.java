package com.gitutility.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public final class MessagingConditions {

    private MessagingConditions() {
    }

    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @ConditionalOnProperty(name = "git-utility.messaging.provider", havingValue = "rabbitmq", matchIfMissing = true)
    public @interface OnRabbitMq {
    }

    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @ConditionalOnProperty(name = "git-utility.messaging.provider", havingValue = "kafka")
    public @interface OnKafka {
    }

    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @ConditionalOnProperty(name = "git-utility.messaging.provider", havingValue = "none")
    public @interface OnNone {
    }
}
