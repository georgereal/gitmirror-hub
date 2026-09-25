package com.gitutility.persistence.store;

/**
 * Write-time preparation contract shared by both persistence providers.
 * Entities implement the JPA {@code @PrePersist}/{@code @PreUpdate} bodies here so the
 * MongoDB store's {@code onBeforeConvert} listener applies byte-identical preparation
 * (id generation, timestamps, clamping/clipping).
 */
public interface WritePreparer {

    /** Applies id generation / timestamp stamping / length clamping before a write. */
    void prepareForWrite();
}
