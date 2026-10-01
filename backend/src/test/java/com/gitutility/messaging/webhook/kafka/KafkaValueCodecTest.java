package com.gitutility.messaging.webhook.kafka;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaValueCodecTest {

    @Test
    void jsonCodecReturnsUtf8Body() {
        KafkaValueCodec codec = new KafkaValueCodec(
                JsonMapper.builder().build(), "json", "", "", "", "", "", "", "");
        String json = codec.toJson("git.sync.incremental", "{\"repoUrl\":\"https://example/a\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("{\"repoUrl\":\"https://example/a\"}", json);
    }

    @Test
    void combinedPemSplitsCertAndEncryptedKey(@TempDir Path dir) throws Exception {
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
        KafkaSslPemSupport.PemMaterial pem = KafkaSslPemSupport.load(ca.toString(), client.toString(), null);
        assertTrue(pem.clientCertificateChain().contains("CERT"));
        assertTrue(pem.clientKey().contains("ENCRYPTED PRIVATE KEY"));
        assertThrows(IllegalStateException.class,
                () -> KafkaSslPemSupport.requireKeyPasswordIfEncrypted(pem.clientKey(), ""));
        KafkaSslPemSupport.requireKeyPasswordIfEncrypted(pem.clientKey(), "secret");
    }

    @Test
    void normalizeRejectsUnknownCodec() {
        assertThrows(IllegalStateException.class, () -> KafkaValueCodec.normalize("protobuf"));
    }

    @Test
    void genericRecordBecomesJsonObject() throws Exception {
        String schemaJson = """
                {"type":"record","name":"Evt","fields":[
                  {"name":"repoUrl","type":"string"},
                  {"name":"eventType","type":"string"}
                ]}
                """;
        Schema schema = new Schema.Parser().parse(schemaJson);
        GenericRecord record = new GenericData.Record(schema);
        record.put("repoUrl", "https://github.com/acme/origin");
        record.put("eventType", "push");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) KafkaValueCodec.toJava(record);
        assertEquals("https://github.com/acme/origin", map.get("repoUrl"));
        assertEquals("push", map.get("eventType"));
        String json = JsonMapper.builder().build().writeValueAsString(map);
        assertTrue(json.contains("\"repoUrl\""));
    }

    @Test
    void pemLoadRequiresReadableFiles(@TempDir Path dir) throws Exception {
        Path ca = dir.resolve("ca.pem");
        Path cert = dir.resolve("client.pem");
        Path key = dir.resolve("client.key");
        Files.writeString(ca, "-----BEGIN CERTIFICATE-----\nCA\n-----END CERTIFICATE-----\n");
        Files.writeString(cert, "-----BEGIN CERTIFICATE-----\nCERT\n-----END CERTIFICATE-----\n");
        Files.writeString(key, "-----BEGIN PRIVATE KEY-----\nKEY\n-----END PRIVATE KEY-----\n");
        KafkaSslPemSupport.PemMaterial pem = KafkaSslPemSupport.load(
                ca.toString(), cert.toString(), key.toString());
        assertTrue(pem.trustCertificates().contains("CA"));
        assertTrue(pem.clientCertificateChain().contains("CERT"));
        assertTrue(pem.clientKey().contains("KEY"));
    }
}
