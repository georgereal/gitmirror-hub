package com.gitutility.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class MessagingEnvironmentPostProcessorTest {

    @Test
    void noneModeForcesPauseOffEvenWhenYamlPropertyPresent() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("git-utility.messaging.provider", "none");
        // Simulates application.yml always defining the pause key
        env.setProperty("git-utility.queue.pause-consumers-on-startup", "true");

        new MessagingEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

        assertEquals("false", env.getProperty("git-utility.queue.pause-consumers-on-startup"));
        assertEquals("none", env.getProperty("git-utility.messaging.provider"));
    }

    @Test
    void explicitPauseEnvWinsOverNoneAutoDefault() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("git-utility.messaging.provider", "none");
        env.setProperty("GIT_QUEUE_PAUSE_ON_STARTUP", "true");
        env.setProperty("git-utility.queue.pause-consumers-on-startup", "true");

        new MessagingEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

        // Explicit GIT_QUEUE_PAUSE_ON_STARTUP present → do not override YAML/default true
        assertEquals("true", env.getProperty("git-utility.queue.pause-consumers-on-startup"));
    }

    @Test
    void rabbitmqDoesNotForcePauseOff() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("git-utility.messaging.provider", "rabbitmq");
        env.setProperty("git-utility.queue.pause-consumers-on-startup", "true");

        new MessagingEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

        assertEquals("true", env.getProperty("git-utility.queue.pause-consumers-on-startup"));
        assertEquals("rabbitmq", env.getProperty("git-utility.messaging.provider"));
    }
}
