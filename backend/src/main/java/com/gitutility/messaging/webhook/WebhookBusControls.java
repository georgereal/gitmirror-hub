package com.gitutility.messaging.webhook;

/**
 * Pause and resume the incremental webhook-bus listener without leaving the
 * Kafka consumer group (pause) or dropping the Rabbit connection permanently.
 */
public interface WebhookBusControls {

    void pause();

    void resume();
}
