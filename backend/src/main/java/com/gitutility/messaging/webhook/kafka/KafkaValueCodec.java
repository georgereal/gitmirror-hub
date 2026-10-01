package com.gitutility.messaging.webhook.kafka;

import com.gitutility.messaging.webhook.WebhookBusConditions;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import jakarta.annotation.PreDestroy;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.IndexedRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns Kafka record bytes into a JSON string for {@link IncrementalEventDecoder}.
 * {@code json} is UTF-8 text. {@code avro} uses Schema Registry (Confluent wire format).
 */
@Component
@WebhookBusConditions.OnKafka
public class KafkaValueCodec {

    public static final String JSON = "json";
    public static final String AVRO = "avro";

    private final String codec;
    private final ObjectMapper mapper;
    private final KafkaAvroDeserializer avroDeserializer;

    @Autowired
    public KafkaValueCodec(
            ObjectMapper objectMapper,
            @Value("${git-utility.webhook-bus.kafka.value-codec:json}") String codec,
            @Value("${git-utility.webhook-bus.kafka.schema-registry-url:}") String registryUrl,
            @Value("${git-utility.webhook-bus.kafka.schema-registry-username:}") String registryUser,
            @Value("${git-utility.webhook-bus.kafka.schema-registry-password:}") String registryPassword,
            @Value("${spring.kafka.properties.ssl.truststore.certificates:}") String trustCertificates,
            @Value("${spring.kafka.properties.ssl.keystore.certificate.chain:}") String clientCertificateChain,
            @Value("${spring.kafka.properties.ssl.keystore.key:}") String clientKey,
            @Value("${spring.kafka.properties.ssl.key.password:}") String keyPassword) {
        this.mapper = objectMapper;
        this.codec = normalize(codec);
        if (AVRO.equals(this.codec)) {
            Map<String, Object> props = new HashMap<>();
            props.put(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, registryUrl);
            props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, false);
            if (registryUser != null && !registryUser.isBlank()) {
                props.put("basic.auth.credentials.source", "USER_INFO");
                props.put("basic.auth.user.info",
                        registryUser + ":" + (registryPassword == null ? "" : registryPassword));
            }
            if (trustCertificates != null && !trustCertificates.isBlank()) {
                props.put("schema.registry.ssl.truststore.type", "PEM");
                props.put("schema.registry.ssl.truststore.certificates", trustCertificates);
                props.put("schema.registry.ssl.keystore.type", "PEM");
                props.put("schema.registry.ssl.keystore.certificate.chain", clientCertificateChain);
                props.put("schema.registry.ssl.keystore.key", clientKey);
                if (keyPassword != null && !keyPassword.isBlank()) {
                    props.put("schema.registry.ssl.key.password", keyPassword);
                }
            }
            KafkaAvroDeserializer deserializer = new KafkaAvroDeserializer();
            deserializer.configure(props, false);
            this.avroDeserializer = deserializer;
        } else {
            this.avroDeserializer = null;
        }
    }

    /** Package-visible for tests that supply a deserializer stub. */
    KafkaValueCodec(ObjectMapper objectMapper, String codec, KafkaAvroDeserializer avroDeserializer) {
        this.mapper = objectMapper;
        this.codec = normalize(codec);
        this.avroDeserializer = avroDeserializer;
    }

    public String codec() {
        return codec;
    }

    /**
     * @return JSON text suitable for {@link IncrementalEventDecoder} and for {@code sourceMessage}
     */
    public String toJson(String topic, byte[] value) {
        if (value == null || value.length == 0) {
            throw new IncrementalEventDecodeException("empty Kafka record");
        }
        if (JSON.equals(codec)) {
            return new String(value, StandardCharsets.UTF_8);
        }
        Object decoded;
        try {
            decoded = avroDeserializer.deserialize(topic, value);
        } catch (RuntimeException e) {
            throw new IncrementalEventDecodeException("avro deserialize failed: " + e.getMessage());
        }
        try {
            return mapper.writeValueAsString(toJava(decoded));
        } catch (IncrementalEventDecodeException e) {
            throw e;
        } catch (Exception e) {
            throw new IncrementalEventDecodeException("avro to JSON failed: " + e.getMessage());
        }
    }

    @PreDestroy
    void close() {
        if (avroDeserializer != null) {
            avroDeserializer.close();
        }
    }

    public static String normalize(String raw) {
        String value = raw == null || raw.isBlank() ? JSON : raw.trim().toLowerCase();
        if (!JSON.equals(value) && !AVRO.equals(value)) {
            throw new IllegalStateException(
                    "GIT_WEBHOOK_KAFKA_VALUE_CODEC must be json or avro (was '" + raw + "')");
        }
        return value;
    }

    static Object toJava(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof IndexedRecord record) {
            Map<String, Object> object = new LinkedHashMap<>();
            Schema schema = record.getSchema();
            for (Schema.Field field : schema.getFields()) {
                object.put(field.name(), toJava(record.get(field.pos())));
            }
            return object;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> object = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                object.put(String.valueOf(entry.getKey()), toJava(entry.getValue()));
            }
            return object;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> list = new ArrayList<>(collection.size());
            for (Object item : collection) {
                list.add(toJava(item));
            }
            return list;
        }
        if (value instanceof ByteBuffer buffer) {
            ByteBuffer copy = buffer.duplicate();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            return bytes;
        }
        if (value instanceof GenericData.EnumSymbol symbol) {
            return symbol.toString();
        }
        if (value instanceof GenericRecord) {
            return toJava((IndexedRecord) value);
        }
        return value;
    }
}
