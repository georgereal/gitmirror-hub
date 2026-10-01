package com.gitutility.messaging.webhook.kafka;

import com.gitutility.model.dto.IncrementalGitEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalEventDecoderTest {

    @TempDir
    Path dir;

    @Test
    void normalizedBindsTheCurrentEvent() {
        IncrementalEventDecoder decoder = IncrementalEventDecoder.open(JsonMapper.builder().build(), null);
        IncrementalGitEvent event = decoder.decode("""
                {"provider":"github","repoUrl":"https://github.com/acme/origin.git","ref":"refs/heads/main",
                 "beforeSha":"a","afterSha":"b","deliveryId":"d1","eventType":"push",
                 "receivedAt":"2026-09-26T12:00:00Z"}
                """);
        assertEquals("push", event.getEventType());
        assertEquals("normalized-v1", event.getSchemaVersion());
        assertEquals("https://github.com/acme/origin.git", event.getRepoUrl());
    }

    @Test
    void enrichedMapsADifferentShapeAndDoesNotRequireCanonicalFieldNames() throws Exception {
        Files.writeString(dir.resolve("10-git.json"), """
                {
                  "id": "enriched-git-v1",
                  "when": { "pointer": "/kind", "in": ["ref.updated", "ref.deleted"] },
                  "fields": {
                    "repoUrl": "/repository/cloneUrl",
                    "ref": "/git/ref",
                    "beforeSha": "/git/before",
                    "afterSha": "/git/after",
                    "deliveryId": "/deliveryId",
                    "provider": "/provider",
                    "eventType": "/kind"
                  },
                  "eventTypeMap": { "ref.updated": "push", "ref.deleted": "delete" }
                }
                """);
        IncrementalEventDecoder decoder = IncrementalEventDecoder.open(JsonMapper.builder().build(), dir.toString());
        IncrementalGitEvent event = decoder.decode("""
                {"kind":"ref.updated","deliveryId":"d1","provider":"github",
                 "repository":{"cloneUrl":"https://github.com/acme/origin.git"},
                 "git":{"ref":"refs/heads/main","before":"aaa","after":"bbb"}}
                """);
        assertEquals("push", event.getEventType());
        assertEquals("https://github.com/acme/origin.git", event.getRepoUrl());
        assertEquals("refs/heads/main", event.getRef());
        assertEquals("enriched-git-v1", event.getSchemaVersion());
        assertEquals("bbb", event.getAfterSha());
        assertTrue(event.getReceivedAt() != null && !event.getReceivedAt().isBlank());
    }

    @Test
    void enrichedRejectsARecordThatMatchesNoFile() throws Exception {
        Files.writeString(dir.resolve("10-git.json"), """
                {"id":"enriched-git-v1","when":{"pointer":"/kind","in":["ref.updated"]},
                 "fields":{"repoUrl":"/repository/cloneUrl","eventType":"/kind"},
                 "eventTypeMap":{"ref.updated":"push"}}
                """);
        IncrementalEventDecoder decoder = IncrementalEventDecoder.open(JsonMapper.builder().build(), dir.toString());
        IncrementalEventDecodeException error = assertThrows(
                IncrementalEventDecodeException.class,
                () -> decoder.decode("{\"kind\":\"other\"}"));
        assertEquals("no format matched", error.getMessage());
    }

    @Test
    void gitWebhookServiceUnwrapsStringPayload() throws Exception {
        Files.writeString(dir.resolve("30-git-webhook-service-v1.json"), """
                {
                  "id": "git-webhook-service-v1",
                  "when": { "pointer": "/source", "in": ["GitWebhookService"] },
                  "fields": {
                    "repoUrl": "/payload/repository/ssh_url",
                    "ref": "/payload/ref",
                    "beforeSha": "/payload/before",
                    "afterSha": "/payload/after",
                    "deliveryId": "/uuid",
                    "eventType": "/destinationList/0",
                    "rawPayload": "/payload"
                  },
                  "eventTypeMap": {
                    "PUSH_EVENT_PROCESSOR": "push",
                    "PULL_REQUEST_PROCESSOR": "pull_request"
                  }
                }
                """);
        var mapper = JsonMapper.builder().build();
        String inner = "{\"ref\":\"refs/heads/main\",\"before\":\"aaa\",\"after\":\"bbb\","
                + "\"repository\":{\"ssh_url\":\"git@github-uat.example.com:acme/origin.git\","
                + "\"clone_url\":\"https://github-uat.example.com/acme/origin.git\"}}";
        var outerNode = mapper.createObjectNode();
        outerNode.put("source", "GitWebhookService");
        outerNode.put("uuid", "deliv-1");
        outerNode.put("payload", inner);
        outerNode.set("destinationList", mapper.createArrayNode().add("PUSH_EVENT_PROCESSOR"));
        IncrementalEventDecoder decoder = IncrementalEventDecoder.open(mapper, dir.toString());
        IncrementalGitEvent event = decoder.decode(mapper.writeValueAsString(outerNode));
        assertEquals("push", event.getEventType());
        assertEquals("git-webhook-service-v1", event.getSchemaVersion());
        assertEquals("git@github-uat.example.com:acme/origin.git", event.getRepoUrl());
        assertEquals("refs/heads/main", event.getRef());
        assertEquals("bbb", event.getAfterSha());
        assertEquals("deliv-1", event.getDeliveryId());
        assertTrue(event.getRawPayload() != null && event.getRawPayload().contains("refs/heads/main"));
    }
}
