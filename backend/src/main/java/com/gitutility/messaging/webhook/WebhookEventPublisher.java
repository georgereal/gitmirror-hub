package com.gitutility.messaging.webhook;

import com.gitutility.model.dto.IncrementalGitEvent;

/**
 * Tail publish for the incremental lane. Kafka uses the repo URL as the record key.
 */
public interface WebhookEventPublisher {

    void publish(IncrementalGitEvent event);

    void deadLetter(IncrementalGitEvent event, String reason);

    String destination();
}
