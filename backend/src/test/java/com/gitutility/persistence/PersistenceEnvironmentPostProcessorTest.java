package com.gitutility.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceEnvironmentPostProcessorTest {

    private MockEnvironment env(String provider) {
        MockEnvironment env = new MockEnvironment();
        if (provider != null) {
            env.setProperty("git-utility.persistence.provider", provider);
        }
        return env;
    }

    @Test
    void h2IsDefaultAndExcludesMongoStack() {
        MockEnvironment env = env(null);
        new PersistenceEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

        assertEquals("h2", env.getProperty("git-utility.persistence.provider"));
        String excludes = env.getProperty("spring.autoconfigure.exclude");
        assertTrue(excludes.contains(PersistenceEnvironmentPostProcessor.MONGO_AUTO_CONFIGURATION));
        assertTrue(excludes.contains(PersistenceEnvironmentPostProcessor.MONGO_HEALTH_AUTO_CONFIGURATION));
        assertTrue(excludes.contains(PersistenceEnvironmentPostProcessor.MONGO_METRICS_AUTO_CONFIGURATION));
        assertTrue(excludes.contains(PersistenceEnvironmentPostProcessor.DATA_MONGO_AUTO_CONFIGURATION));
        assertTrue(excludes.contains(PersistenceEnvironmentPostProcessor.DATA_MONGO_REPOSITORIES_AUTO_CONFIGURATION));
        assertFalse(excludes.contains(PersistenceEnvironmentPostProcessor.DATASOURCE_AUTO_CONFIGURATION));
        assertFalse(excludes.contains(PersistenceEnvironmentPostProcessor.HIBERNATE_JPA_AUTO_CONFIGURATION));
    }

    @Test
    void mongoExcludesRelationalStack() {
        MockEnvironment env = env("mongo");
        new PersistenceEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

        assertEquals("mongo", env.getProperty("git-utility.persistence.provider"));
        String excludes = env.getProperty("spring.autoconfigure.exclude");
        assertTrue(excludes.contains(PersistenceEnvironmentPostProcessor.DATASOURCE_AUTO_CONFIGURATION));
        assertTrue(excludes.contains(PersistenceEnvironmentPostProcessor.DATASOURCE_HEALTH_AUTO_CONFIGURATION));
        assertTrue(excludes.contains(PersistenceEnvironmentPostProcessor.DATA_JPA_REPOSITORIES_AUTO_CONFIGURATION));
        assertTrue(excludes.contains(PersistenceEnvironmentPostProcessor.HIBERNATE_JPA_AUTO_CONFIGURATION));
        assertFalse(excludes.contains(PersistenceEnvironmentPostProcessor.MONGO_AUTO_CONFIGURATION));
        assertFalse(excludes.contains(PersistenceEnvironmentPostProcessor.DATA_MONGO_REPOSITORIES_AUTO_CONFIGURATION));
    }

    @Test
    void envVariableIsHonored() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("GIT_PERSISTENCE_PROVIDER", "MongoDB");
        new PersistenceEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

        assertEquals("mongo", env.getProperty("git-utility.persistence.provider"));
        assertTrue(env.getProperty("spring.autoconfigure.exclude")
                .contains(PersistenceEnvironmentPostProcessor.DATASOURCE_AUTO_CONFIGURATION));
    }

    @Test
    void mergesWithExistingExcludes() {
        MockEnvironment env = env("mongo");
        env.setProperty("spring.autoconfigure.exclude", "com.example.SomeAutoConfiguration");
        new PersistenceEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

        String excludes = env.getProperty("spring.autoconfigure.exclude");
        assertTrue(excludes.contains("com.example.SomeAutoConfiguration"));
        assertTrue(excludes.contains(PersistenceEnvironmentPostProcessor.DATASOURCE_AUTO_CONFIGURATION));
    }

    @Test
    void doesNotDuplicateExcludes() {
        MockEnvironment env = env("mongo");
        env.setProperty("spring.autoconfigure.exclude",
                PersistenceEnvironmentPostProcessor.DATASOURCE_AUTO_CONFIGURATION);
        new PersistenceEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

        String excludes = env.getProperty("spring.autoconfigure.exclude");
        assertEquals(1, excludes.split(PersistenceEnvironmentPostProcessor.DATASOURCE_AUTO_CONFIGURATION, -1).length - 1);
    }

    @Test
    void rejectsUnknownProvider() {
        MockEnvironment env = env("postgres");
        assertThrows(IllegalArgumentException.class,
                () -> new PersistenceEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication()));
    }
}
