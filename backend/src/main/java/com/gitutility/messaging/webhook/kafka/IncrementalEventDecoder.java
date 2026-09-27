package com.gitutility.messaging.webhook.kafka;

import com.gitutility.model.dto.IncrementalGitEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Adapter registry for the incremental topic. {@code normalized-v1} is always registered.
 * Each JSON file in the formats directory is another adapter, selected by the {@code schemaVersion}
 * header or by a unique {@code when} match. The result is always {@link IncrementalGitEvent}.
 */
public final class IncrementalEventDecoder {

    static final List<String> GIT_TYPES = List.of("push", "create", "delete");
    static final List<String> METADATA_TYPES = List.of("pull_request", "release", "status", "check_run");
    static final List<String> HUB_TYPES = Stream.concat(GIT_TYPES.stream(), METADATA_TYPES.stream()).toList();
    private static final Set<String> METADATA = Set.copyOf(METADATA_TYPES);
    private static final List<String> FIELDS = List.of(
            "provider", "repoUrl", "ref", "beforeSha", "afterSha", "deliveryId", "eventType", "rawPayload", "receivedAt");

    static final String NORMALIZED = "normalized-v1";

    private final ObjectMapper mapper;
    private final List<EventFormat> formats;

    private IncrementalEventDecoder(ObjectMapper mapper, List<EventFormat> formats) {
        this.mapper = mapper;
        this.formats = formats;
    }

    public static IncrementalEventDecoder open(ObjectMapper mapper, String formatsDir) {
        ObjectMapper json = mapper == null ? JsonMapper.builder().build() : mapper;
        List<EventFormat> loaded = formatsDir == null || formatsDir.isBlank()
                ? List.of()
                : readFormats(formatsDir, json);
        return new IncrementalEventDecoder(json, loaded);
    }

    /** Fails startup when a configured formats directory cannot be read. */
    public static void requireFormats(String formatsDir) {
        readFormats(formatsDir, JsonMapper.builder().build());
    }

    public IncrementalGitEvent decode(String json) {
        return decode(json, null);
    }

    public IncrementalGitEvent decode(String json, String schemaVersionHeader) {
        if (json == null || json.isBlank()) {
            throw new IncrementalEventDecodeException("empty Kafka record");
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new IncrementalEventDecodeException("unreadable JSON: " + e.getMessage());
        }
        String named = firstText(schemaVersionHeader, text(root.at("/schemaVersion")));
        if (named != null) {
            return applyNamed(named, json, root);
        }
        List<String> hits = new ArrayList<>();
        if (matchesNormalized(root)) {
            hits.add(NORMALIZED);
        }
        for (EventFormat format : formats) {
            if (format.matches(root)) {
                hits.add(format.id());
            }
        }
        if (hits.isEmpty()) {
            throw new IncrementalEventDecodeException("no format matched");
        }
        if (hits.size() > 1) {
            throw new IncrementalEventDecodeException("matched " + String.join(", ", hits));
        }
        return applyNamed(hits.get(0), json, root);
    }

    private IncrementalGitEvent applyNamed(String schemaVersion, String json, JsonNode root) {
        if (NORMALIZED.equals(schemaVersion)) {
            IncrementalGitEvent event;
            try {
                event = mapper.readValue(json, IncrementalGitEvent.class);
            } catch (Exception e) {
                throw new IncrementalEventDecodeException(e.getMessage());
            }
            event.setSchemaVersion(NORMALIZED);
            return event;
        }
        for (EventFormat format : formats) {
            if (format.id().equals(schemaVersion)) {
                IncrementalGitEvent event = format.apply(mapper, root);
                event.setSchemaVersion(format.id());
                return event;
            }
        }
        throw new IncrementalEventDecodeException("no adapter for schemaVersion " + schemaVersion);
    }

    private static boolean matchesNormalized(JsonNode root) {
        String eventType = text(root.at("/eventType"));
        String repoUrl = text(root.at("/repoUrl"));
        return eventType != null && HUB_TYPES.contains(eventType) && repoUrl != null;
    }

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static List<EventFormat> readFormats(String formatsDir, ObjectMapper mapper) {
        if (formatsDir == null || formatsDir.isBlank()) {
            throw new IllegalStateException(
                    "GIT_WEBHOOK_EVENT_FORMAT=enriched requires GIT_WEBHOOK_EVENT_FORMATS_DIR");
        }
        Path dir = Path.of(formatsDir);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("GIT_WEBHOOK_EVENT_FORMATS_DIR is not a directory: " + formatsDir);
        }
        List<Path> files;
        try (Stream<Path> listing = Files.list(dir)) {
            files = listing
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read GIT_WEBHOOK_EVENT_FORMATS_DIR: " + e.getMessage(), e);
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("No mapping files in " + formatsDir);
        }
        ObjectMapper json = mapper == null ? JsonMapper.builder().build() : mapper;
        List<EventFormat> loaded = new ArrayList<>();
        for (Path file : files) {
            try {
                loaded.add(EventFormat.parse(file.getFileName().toString(), json.readTree(Files.readString(file))));
            } catch (Exception e) {
                throw new IllegalStateException("Cannot read " + file.getFileName() + ": " + e.getMessage(), e);
            }
        }
        return List.copyOf(loaded);
    }

    private record EventFormat(
            String id,
            String whenPointer,
            List<String> whenIn,
            Map<String, String> fields,
            Map<String, String> eventTypeMap
    ) {
        static EventFormat parse(String fileName, JsonNode root) {
            String id = text(root.get("id"));
            if (id == null) {
                throw new IllegalStateException(fileName + " is missing id");
            }
            JsonNode when = root.path("when");
            String pointer = text(when.get("pointer"));
            JsonNode inNode = when.path("in");
            if (pointer == null || !pointer.startsWith("/") || !inNode.isArray() || inNode.isEmpty()) {
                throw new IllegalStateException(fileName + " needs when.pointer and a non-empty when.in list");
            }
            List<String> in = new ArrayList<>();
            for (JsonNode item : inNode) {
                String value = text(item);
                if (value != null) {
                    in.add(value);
                }
            }
            if (in.isEmpty()) {
                throw new IllegalStateException(fileName + " needs when.pointer and a non-empty when.in list");
            }
            JsonNode fieldNode = root.path("fields");
            if (!fieldNode.isObject()) {
                throw new IllegalStateException(fileName + " needs a fields object of JSON Pointers");
            }
            Map<String, String> fields = new LinkedHashMap<>();
            for (String field : FIELDS) {
                String pointerValue = text(fieldNode.get(field));
                if (pointerValue != null) {
                    fields.put(field, pointerValue);
                }
            }
            Map<String, String> eventTypeMap = new LinkedHashMap<>();
            JsonNode mapNode = root.path("eventTypeMap");
            if (mapNode.isObject()) {
                mapNode.propertyNames().forEach(name -> {
                    String mapped = text(mapNode.get(name));
                    if (mapped != null) {
                        eventTypeMap.put(name, mapped);
                    }
                });
            }
            return new EventFormat(id, pointer, List.copyOf(in), Map.copyOf(fields), Map.copyOf(eventTypeMap));
        }

        boolean matches(JsonNode root) {
            String value = text(root.at(whenPointer));
            return value != null && whenIn.contains(value);
        }

        IncrementalGitEvent apply(ObjectMapper mapper, JsonNode root) {
            IncrementalGitEvent event = new IncrementalGitEvent();
            for (String field : FIELDS) {
                String pointer = fields.get(field);
                if (pointer == null) {
                    continue;
                }
                JsonNode value = root.at(pointer);
                if (value.isMissingNode() || value.isNull()) {
                    continue;
                }
                String text;
                if ("rawPayload".equals(field) && (value.isObject() || value.isArray())) {
                    text = mapper.writeValueAsString(value);
                } else {
                    text = value.asText("").trim();
                }
                if (text == null || text.isBlank()) {
                    continue;
                }
                if ("eventType".equals(field)) {
                    String mapped = eventTypeMap.get(text);
                    text = (mapped == null || mapped.isBlank() ? text : mapped).trim().toLowerCase();
                }
                assign(event, field, text);
            }
            if (event.getReceivedAt() == null || event.getReceivedAt().isBlank()) {
                event.setReceivedAt(Instant.now().toString());
            }
            List<String> errors = validate(event);
            if (!errors.isEmpty()) {
                throw new IncrementalEventDecodeException(id + ": " + String.join("; ", errors));
            }
            return event;
        }
    }

    private static List<String> validate(IncrementalGitEvent event) {
        List<String> errors = new ArrayList<>();
        if (event.getRepoUrl() == null || event.getRepoUrl().isBlank()) {
            errors.add("missing repoUrl");
        }
        String eventType = event.getEventType() == null ? "" : event.getEventType().trim();
        if (!HUB_TYPES.contains(eventType)) {
            errors.add("eventType '" + eventType + "' is not processed");
        }
        if (METADATA.contains(eventType) && (event.getRawPayload() == null || event.getRawPayload().isBlank())) {
            errors.add("metadata event " + eventType + " has empty rawPayload");
        }
        return errors;
    }

    private static void assign(IncrementalGitEvent event, String field, String value) {
        switch (field) {
            case "provider" -> event.setProvider(value);
            case "repoUrl" -> event.setRepoUrl(value);
            case "ref" -> event.setRef(value);
            case "beforeSha" -> event.setBeforeSha(value);
            case "afterSha" -> event.setAfterSha(value);
            case "deliveryId" -> event.setDeliveryId(value);
            case "eventType" -> event.setEventType(value);
            case "rawPayload" -> event.setRawPayload(value);
            case "receivedAt" -> event.setReceivedAt(value);
            default -> {
            }
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
