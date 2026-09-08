package com.gitutility.messaging;

import com.gitutility.model.dto.SyncEventMessage;

/**
 * Broker-agnostic publish path for sync execution events.
 * Lane routing (full vs incremental) is decided by the implementation using {@link com.gitutility.service.SyncLaneRouter}.
 */
public interface SyncEventBus {

    void publish(SyncEventMessage message);

    void republish(SyncEventMessage message);

    /** Called after operators resume consumers — deferred in-process work can drain. */
    default void onConsumersResumed() {
    }
}
