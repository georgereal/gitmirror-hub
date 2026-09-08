package com.gitutility.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;

/**
 * Align a few Jackson 3 defaults with prior Jackson 2 behavior used across SCM/webhook DTOs
 * that often omit primitive fields in partial JSON payloads.
 */
@Configuration
public class JacksonConfig {

    @Bean
    JsonMapperBuilderCustomizer jacksonPrimitiveNullDefaults() {
        return builder -> builder
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
