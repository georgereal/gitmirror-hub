package com.gitutility.messaging.webhook.kafka;

import com.gitutility.messaging.webhook.WebhookBusConditions;
import com.gitutility.model.dto.IncrementalGitEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Adapters for the incremental topic. {@code normalized-v1} is built in. Mapping files add more versions.
 */
@Component
@WebhookBusConditions.OnKafka
public class KafkaIncrementalDecoder {

    private final IncrementalEventDecoder decoder;

    public KafkaIncrementalDecoder(
            ObjectMapper objectMapper,
            @Value("${git-utility.webhook-bus.kafka.event-formats-dir:}") String formatsDir) {
        this.decoder = IncrementalEventDecoder.open(objectMapper, formatsDir);
    }

    public IncrementalGitEvent decode(String json) {
        return decoder.decode(json, null);
    }

    public IncrementalGitEvent decode(String json, String schemaVersionHeader) {
        return decoder.decode(json, schemaVersionHeader);
    }
}
