package com.gitutility.persistence;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public final class PersistenceConditions {

    private PersistenceConditions() {
    }

    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @ConditionalOnProperty(name = "git-utility.persistence.provider", havingValue = "h2", matchIfMissing = true)
    public @interface OnH2 {
    }

    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @ConditionalOnProperty(name = "git-utility.persistence.provider", havingValue = "mongo")
    public @interface OnMongo {
    }
}
