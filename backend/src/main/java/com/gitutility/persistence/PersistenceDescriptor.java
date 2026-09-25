package com.gitutility.persistence;

import lombok.Builder;
import lombok.Value;

/**
 * Runtime description of the active persistence module — used by APIs and the operator UI.
 */
@Value
@Builder
public class PersistenceDescriptor {
    PersistenceProvider provider;
    String displayName;
    String description;
    /** Store bundled with the Hub as a local file (H2). */
    boolean fileBacked;
    /** External server the Hub connects to (MongoDB). */
    boolean externalStore;
    /** Store must be reachable at startup; no fallback (true for Mongo). */
    boolean requiresConnection;
    /** H2 web console can be toggled via {@code GIT_H2_CONSOLE_ENABLED}. */
    boolean supportsConsole;
}
