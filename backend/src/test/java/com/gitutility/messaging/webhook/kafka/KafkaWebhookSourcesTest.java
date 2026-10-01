package com.gitutility.messaging.webhook.kafka;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaWebhookSourcesTest {

    @TempDir
    Path dir;

    @Test
    void blankFilePathReturnsNull() {
        assertNull(KafkaWebhookSources.loadActive(""));
        assertNull(KafkaWebhookSources.loadActive(null));
    }

    @Test
    void jsonPicksSingleEnabledSource() throws Exception {
        Path file = dir.resolve("sources.json");
        Files.writeString(file, """
                {
                  "sources": [
                    {"id":"a","enabled":false,"bootstrapServers":"a:9092","incrementalTopic":"t-a"},
                    {"id":"b","enabled":true,"bootstrapServers":"b:9092","incrementalTopic":"t-b","groupId":"hub-b"}
                  ]
                }
                """);
        KafkaWebhookSources.Source active = KafkaWebhookSources.loadActive(file.toString());
        assertEquals("b", active.id());
        assertEquals("b:9092", active.bootstrapServers());
        assertEquals("t-b", active.incrementalTopic());
        assertEquals("hub-b", active.groupId());
    }

    @Test
    void yamlPicksSingleEnabledSource() throws Exception {
        Path file = dir.resolve("sources.yml");
        Files.writeString(file, """
                sources:
                  - id: uat-a
                    enabled: true
                    bootstrap-servers: uat-a:9092
                    incremental-topic: git.events
                    security-protocol: SSL
                  - id: uat-b
                    enabled: false
                    bootstrap-servers: uat-b:9092
                """);
        KafkaWebhookSources.Source active = KafkaWebhookSources.loadActive(file.toString());
        assertEquals("uat-a", active.id());
        assertEquals("uat-a:9092", active.bootstrapServers());
        assertEquals("git.events", active.incrementalTopic());
        assertEquals("SSL", active.securityProtocol());
    }

    @Test
    void rejectsZeroOrManyEnabled() throws Exception {
        Path none = dir.resolve("none.json");
        Files.writeString(none, """
                {"sources":[{"id":"a","enabled":false,"bootstrapServers":"a:9092"}]}
                """);
        IllegalStateException noEnabled = assertThrows(IllegalStateException.class,
                () -> KafkaWebhookSources.loadActive(none.toString()));
        assertTrue(noEnabled.getMessage().contains("exactly one"));

        Path many = dir.resolve("many.json");
        Files.writeString(many, """
                {"sources":[
                  {"id":"a","enabled":true,"bootstrapServers":"a:9092"},
                  {"id":"b","enabled":true,"bootstrapServers":"b:9092"}
                ]}
                """);
        IllegalStateException both = assertThrows(IllegalStateException.class,
                () -> KafkaWebhookSources.loadActive(many.toString()));
        assertTrue(both.getMessage().contains("a, b") || both.getMessage().contains("exactly one"));
    }
}
