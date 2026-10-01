package com.gitutility.messaging.webhook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

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
    void missingFormatsDirectoryFailsStartup() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("GIT_WEBHOOK_BUS_PROVIDER", "kafka");
        env.setProperty("GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        env.setProperty("GIT_WEBHOOK_EVENT_FORMATS_DIR", "/tmp/git-utility-missing-formats");

        assertThrows(IllegalStateException.class,
                () -> new WebhookBusEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication()));
    }

    @Test
    void avroWithoutRegistryUrlFailsStartup() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("GIT_WEBHOOK_BUS_PROVIDER", "kafka");
        env.setProperty("GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        env.setProperty("GIT_WEBHOOK_KAFKA_VALUE_CODEC", "avro");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new WebhookBusEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication()));
        assertTrue(error.getMessage().contains("SCHEMA_REGISTRY_URL"));
    }

    @Test
    void sslWithoutPemPathsFailsStartup() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("GIT_WEBHOOK_BUS_PROVIDER", "kafka");
        env.setProperty("GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        env.setProperty("GIT_WEBHOOK_KAFKA_SECURITY_PROTOCOL", "SSL");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new WebhookBusEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication()));
        assertTrue(error.getMessage().contains("SSL_TRUSTSTORE_LOCATION")
                || error.getMessage().contains("SSL"));
    }

    @Test
    void sslCombinedPemWiresKeyPassword(@TempDir Path dir) throws Exception {
        Path ca = dir.resolve("ca.pem");
        Path client = dir.resolve("client.pem");
        Files.writeString(ca, "-----BEGIN CERTIFICATE-----\nCA\n-----END CERTIFICATE-----\n");
        Files.writeString(client, """
                -----BEGIN CERTIFICATE-----
                CERT
                -----END CERTIFICATE-----
                -----BEGIN ENCRYPTED PRIVATE KEY-----
                KEY
                -----END ENCRYPTED PRIVATE KEY-----
                """);
        MockEnvironment env = new MockEnvironment();
        env.setProperty("GIT_WEBHOOK_BUS_PROVIDER", "kafka");
        env.setProperty("GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        env.setProperty("GIT_WEBHOOK_KAFKA_SECURITY_PROTOCOL", "SSL");
        env.setProperty("GIT_WEBHOOK_KAFKA_SSL_TRUSTSTORE_LOCATION", ca.toString());
        env.setProperty("GIT_WEBHOOK_KAFKA_SSL_KEYSTORE_LOCATION", client.toString());
        env.setProperty("GIT_WEBHOOK_KAFKA_SSL_KEY_PASSWORD", "pem-secret");

        new WebhookBusEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

        assertEquals("PEM", env.getProperty("spring.kafka.properties.ssl.keystore.type"));
        assertTrue(env.getProperty("spring.kafka.properties.ssl.keystore.key").contains("ENCRYPTED"));
        assertEquals("pem-secret", env.getProperty("spring.kafka.properties.ssl.key.password"));
    }

    @Test
    void sourcesFileOverridesBootstrapAndTopic(@TempDir Path dir) throws Exception {
        Path sources = dir.resolve("sources.json");
        Files.writeString(sources, """
                {"sources":[
                  {"id":"primary","enabled":true,"bootstrapServers":"primary:9092",
                   "incrementalTopic":"git.from-file","groupId":"hub-from-file"}
                ]}
                """);
        MockEnvironment env = new MockEnvironment();
        env.setProperty("GIT_WEBHOOK_BUS_PROVIDER", "kafka");
        env.setProperty("GIT_WEBHOOK_KAFKA_SOURCES_FILE", sources.toString());

        new WebhookBusEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

        assertEquals("primary:9092", env.getProperty("spring.kafka.bootstrap-servers"));
        assertEquals("git.from-file", env.getProperty("git-utility.webhook-bus.kafka.incremental-topic"));
        assertEquals("hub-from-file", env.getProperty("spring.kafka.consumer.group-id"));
        assertEquals("primary", env.getProperty("git-utility.webhook-bus.kafka.source-id"));
    }

    @Test
    void sslEncryptedKeyWithoutPasswordFailsStartup(@TempDir Path dir) throws Exception {
        Path ca = dir.resolve("ca.pem");
        Path client = dir.resolve("client.pem");
        Files.writeString(ca, "-----BEGIN CERTIFICATE-----\nCA\n-----END CERTIFICATE-----\n");
        Files.writeString(client, """
                -----BEGIN CERTIFICATE-----
                CERT
                -----END CERTIFICATE-----
                -----BEGIN ENCRYPTED PRIVATE KEY-----
                KEY
                -----END ENCRYPTED PRIVATE KEY-----
                """);
        MockEnvironment env = new MockEnvironment();
        env.setProperty("GIT_WEBHOOK_BUS_PROVIDER", "kafka");
        env.setProperty("GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        env.setProperty("GIT_WEBHOOK_KAFKA_SECURITY_PROTOCOL", "SSL");
        env.setProperty("GIT_WEBHOOK_KAFKA_SSL_TRUSTSTORE_LOCATION", ca.toString());
        env.setProperty("GIT_WEBHOOK_KAFKA_SSL_KEYSTORE_LOCATION", client.toString());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new WebhookBusEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication()));
        assertTrue(error.getMessage().contains("SSL_KEY_PASSWORD"));
    }

    @Test
    void jsonCodecUsesByteArrayDeserializer() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("GIT_WEBHOOK_BUS_PROVIDER", "kafka");
        env.setProperty("GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");

        new WebhookBusEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

        assertEquals(
                "org.apache.kafka.common.serialization.ByteArrayDeserializer",
                env.getProperty("spring.kafka.consumer.value-deserializer"));
        assertEquals("json", env.getProperty("git-utility.webhook-bus.kafka.value-codec"));
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
