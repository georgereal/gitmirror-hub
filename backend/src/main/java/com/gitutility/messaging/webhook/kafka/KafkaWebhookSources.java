package com.gitutility.messaging.webhook.kafka;

import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads named Kafka sources from a JSON or YAML file and picks the single enabled source.
 */
public final class KafkaWebhookSources {

    private KafkaWebhookSources() {
    }

    public record Source(
            String id,
            boolean enabled,
            String bootstrapServers,
            String incrementalTopic,
            String groupId,
            String clientId,
            String securityProtocol,
            String saslMechanism,
            String saslUsername,
            String saslPassword,
            String sslTruststoreLocation,
            String sslKeystoreLocation,
            String sslKeyLocation,
            String sslKeyPassword,
            String valueCodec,
            String schemaRegistryUrl,
            String schemaRegistryUsername,
            String schemaRegistryPassword
    ) {
    }

    /**
     * @return the enabled source, or {@code null} when {@code sourcesFile} is blank
     */
    public static Source loadActive(String sourcesFile) {
        if (sourcesFile == null || sourcesFile.isBlank()) {
            return null;
        }
        Path path = Path.of(sourcesFile);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalStateException(
                    "GIT_WEBHOOK_KAFKA_SOURCES_FILE is not a readable file: " + sourcesFile);
        }
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read Kafka sources file: " + e.getMessage(), e);
        }
        List<Source> sources = path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")
                ? parseJson(text, sourcesFile)
                : parseYaml(text, sourcesFile);
        if (sources.isEmpty()) {
            throw new IllegalStateException("Kafka sources file has no sources: " + sourcesFile);
        }
        List<Source> enabled = sources.stream().filter(Source::enabled).toList();
        if (enabled.isEmpty()) {
            throw new IllegalStateException(
                    "Kafka sources file must enable exactly one source (none enabled): " + sourcesFile);
        }
        if (enabled.size() > 1) {
            throw new IllegalStateException(
                    "Kafka sources file must enable exactly one source (enabled: "
                            + enabled.stream().map(Source::id).reduce((a, b) -> a + ", " + b).orElse("")
                            + ")");
        }
        Source active = enabled.get(0);
        if (active.bootstrapServers() == null || active.bootstrapServers().isBlank()) {
            throw new IllegalStateException(
                    "Enabled Kafka source '" + active.id() + "' is missing bootstrapServers");
        }
        return active;
    }

    static List<Source> parseJson(String text, String label) {
        try {
            JsonNode root = JsonMapper.builder().build().readTree(text);
            JsonNode list = root.isArray() ? root : root.get("sources");
            if (list == null || !list.isArray() || list.isEmpty()) {
                throw new IllegalStateException("Kafka sources JSON needs a non-empty sources array: " + label);
            }
            List<Source> sources = new ArrayList<>();
            for (JsonNode node : list) {
                sources.add(fromMap(asMap(node), label));
            }
            return List.copyOf(sources);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid Kafka sources JSON (" + label + "): " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Source> parseYaml(String text, String label) {
        try {
            Object loaded = new Yaml().load(text);
            List<?> list;
            if (loaded instanceof List<?> direct) {
                list = direct;
            } else if (loaded instanceof Map<?, ?> map) {
                Object sources = firstKey(map, "sources");
                if (!(sources instanceof List<?> nested)) {
                    throw new IllegalStateException(
                            "Kafka sources YAML needs a non-empty sources list: " + label);
                }
                list = nested;
            } else {
                throw new IllegalStateException("Kafka sources YAML must be a map or list: " + label);
            }
            if (list.isEmpty()) {
                throw new IllegalStateException("Kafka sources YAML needs a non-empty sources list: " + label);
            }
            List<Source> sources = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    throw new IllegalStateException("Each Kafka source must be a map: " + label);
                }
                sources.add(fromMap((Map<String, Object>) map, label));
            }
            return List.copyOf(sources);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid Kafka sources YAML (" + label + "): " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> asMap(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        node.propertyNames().forEach(name -> {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) {
                return;
            }
            if (value.isBoolean()) {
                map.put(name, value.asBoolean());
            } else if (value.isNumber()) {
                map.put(name, value.asText());
            } else {
                map.put(name, value.asText());
            }
        });
        return map;
    }

    private static Source fromMap(Map<String, ?> map, String label) {
        String id = text(map, "id");
        if (id == null) {
            throw new IllegalStateException("Kafka source is missing id in " + label);
        }
        boolean enabled = bool(map, "enabled", false);
        return new Source(
                id,
                enabled,
                text(map, "bootstrapServers", "bootstrap-servers", "bootstrap_servers"),
                text(map, "incrementalTopic", "incremental-topic", "incremental_topic", "topic"),
                text(map, "groupId", "group-id", "group_id"),
                text(map, "clientId", "client-id", "client_id"),
                text(map, "securityProtocol", "security-protocol", "security_protocol"),
                text(map, "saslMechanism", "sasl-mechanism", "sasl_mechanism"),
                text(map, "saslUsername", "sasl-username", "sasl_username"),
                text(map, "saslPassword", "sasl-password", "sasl_password"),
                text(map, "sslTruststoreLocation", "ssl-truststore-location", "ssl_truststore_location"),
                text(map, "sslKeystoreLocation", "ssl-keystore-location", "ssl_keystore_location"),
                text(map, "sslKeyLocation", "ssl-key-location", "ssl_key_location"),
                text(map, "sslKeyPassword", "ssl-key-password", "ssl_key_password"),
                text(map, "valueCodec", "value-codec", "value_codec"),
                text(map, "schemaRegistryUrl", "schema-registry-url", "schema_registry_url"),
                text(map, "schemaRegistryUsername", "schema-registry-username", "schema_registry_username"),
                text(map, "schemaRegistryPassword", "schema-registry-password", "schema_registry_password")
        );
    }

    private static Object firstKey(Map<?, ?> map, String key) {
        if (map.containsKey(key)) {
            return map.get(key);
        }
        return null;
    }

    private static String text(Map<String, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty() && !"null".equals(text)) {
                    return text;
                }
            }
        }
        return null;
    }

    private static boolean bool(Map<String, ?> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value).trim());
    }
}
