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
                 "beforeSha":"a","afterSha":"b","deliveryId":"d1","eventType":"push","receivedAt":"2026-09-26T12:00:00Z"}
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
}
