package com.gitutility.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Guards the MongoDB (and AMQP retry) configuration keys in {@code application.yml}
 * against Spring Boot's 4.0 property relocation.
 *
 * <p>Boot 4.0 moved {@code spring.data.mongodb.*} to {@code spring.mongodb.*} and left the
 * old keys behind as {@code deprecation.level=error} tombstones: they are published in the
 * configuration metadata for IDE warnings but are <b>not bound</b> anymore. Writing the URI
 * under the old key therefore fails silently — {@code MongoProperties} keeps its built-in
 * default {@code mongodb://localhost/test}, {@code MONGODB_URI} is ignored, and (with no
 * local mongod) startup dies in {@code MongoSchemaInitializer}'s ping fail-fast with
 * {@code MongoSocketOpenException: Connection refused} on {@code localhost:27017}.</p>
 *
 * <p>These assertions bind the real shipped YAML rather than a fixture, so a future
 * re-introduction of a stale key breaks the build instead of a mongo deployment.</p>
 */
class PersistenceConfigKeysTest {

    /**
     * Loads the shipped {@code application.yml} with the given overrides at highest precedence.
     *
     * <p>{@link MockEnvironment} (not {@code StandardEnvironment}) keeps the assertions hermetic:
     * a real {@code MONGODB_URI} in the developer's shell must not leak into the default-value
     * assertions below.</p>
     */
    private MockEnvironment environment(Map<String, Object> overrides) throws IOException {
        MockEnvironment environment = new MockEnvironment();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        assertEquals(1, sources.size(), "application.yml must load as a single YAML document");
        environment.getPropertySources().addFirst(sources.get(0));
        environment.getPropertySources().addFirst(new MapPropertySource("testOverrides", overrides));
        return environment;
    }

    @Test
    void mongodbUriIsBoundUnderTheBoot4Key() throws IOException {
        String atlasUri = "mongodb+srv://user:pass@cluster0.example.mongodb.net/gitutility?appName=Cluster0";
        MockEnvironment environment = environment(Map.of("MONGODB_URI", atlasUri));

        assertEquals(atlasUri, environment.getProperty("spring.mongodb.uri"));
        assertEquals("gitutility", environment.getProperty("spring.mongodb.database"));
    }

    @Test
    void deprecatedBoot3MongodbKeysAreNotPresent() throws IOException {
        MockEnvironment environment = environment(Map.of());

        assertFalse(environment.containsProperty("spring.data.mongodb.uri"),
                "spring.data.mongodb.uri is unbound since Boot 4.0 — use spring.mongodb.uri");
        assertFalse(environment.containsProperty("spring.data.mongodb.database"),
                "spring.data.mongodb.database is unbound since Boot 4.0 — use spring.mongodb.database");
    }

    @Test
    void mongodbUriFallsBackToDocumentedLocalDefault() throws IOException {
        MockEnvironment environment = environment(Map.of());

        assertEquals("mongodb://localhost:27017/gitutility", environment.getProperty("spring.mongodb.uri"));
        assertEquals("gitutility", environment.getProperty("spring.mongodb.database"));
    }

    @Test
    void mongodbDatabaseOverrideIsBound() throws IOException {
        MockEnvironment environment = environment(Map.of("MONGODB_DATABASE", "gitutility_stage"));

        assertEquals("gitutility_stage", environment.getProperty("spring.mongodb.database"));
    }

    @Test
    void rabbitRetryKeysUseTheBoot4Names() throws IOException {
        MockEnvironment environment = environment(Map.of());

        assertEquals(3, environment.getProperty("spring.rabbitmq.template.retry.max-retries", Integer.class));
        assertEquals(3, environment.getProperty("spring.rabbitmq.listener.simple.retry.max-retries", Integer.class));
        assertFalse(environment.containsProperty("spring.rabbitmq.template.retry.max-attempts"),
                "renamed to max-retries in Boot 4.0 — the old key is silently ignored");
        assertFalse(environment.containsProperty("spring.rabbitmq.listener.simple.retry.max-attempts"),
                "renamed to max-retries in Boot 4.0 — the old key is silently ignored");
    }
}
