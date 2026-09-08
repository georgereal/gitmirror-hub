package com.gitutility.messaging;

import lombok.Builder;
import lombok.Value;

/**
 * Runtime description of the active messaging module — used by APIs and the operator UI.
 */
@Value
@Builder
public class MessagingDescriptor {
    MessagingProvider provider;
    String displayName;
    String description;
    /** Durable external broker (Rabbit, future Kafka, etc.). */
    boolean durableBroker;
    /** Full Queue Manager (depths, DLQ, purge) is meaningful. */
    boolean supportsQueueManager;
    boolean supportsDlq;
    boolean supportsPurge;
    boolean supportsPauseConsumers;
    /** Edge Worker → inbound queue path expects a broker. */
    boolean supportsInboundBrokerQueue;
}
