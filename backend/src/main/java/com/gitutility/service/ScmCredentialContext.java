package com.gitutility.service;

/**
 * Thread-local GitHub/GHES credential selected for the current git/API operation.
 * Bound from pair source/target ids or from picker/settings {@code credentialId}.
 */
public final class ScmCredentialContext {

    private static final ThreadLocal<String> CURRENT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_INSTALLATION = new ThreadLocal<>();

    private ScmCredentialContext() {
    }

    public static void bind(String credentialId) {
        CURRENT_ID.set(credentialId);
    }

    /** Optionally pin the GitHub App installation that owns the repo being operated on. */
    public static void bindInstallation(String installationId) {
        CURRENT_INSTALLATION.set(installationId);
    }

    public static String currentId() {
        return CURRENT_ID.get();
    }

    public static String currentInstallationId() {
        return CURRENT_INSTALLATION.get();
    }

    public static void clear() {
        CURRENT_ID.remove();
        CURRENT_INSTALLATION.remove();
    }

    public static Scope open(String credentialId) {
        return open(credentialId, null);
    }

    public static Scope open(String credentialId, String installationId) {
        String previousId = CURRENT_ID.get();
        String previousInstallation = CURRENT_INSTALLATION.get();
        CURRENT_ID.set(credentialId);
        if (installationId != null && !installationId.isBlank()) {
            CURRENT_INSTALLATION.set(installationId);
        }
        return new Scope(previousId, previousInstallation);
    }

    public static final class Scope implements AutoCloseable {
        private final String previousId;
        private final String previousInstallation;

        private Scope(String previousId, String previousInstallation) {
            this.previousId = previousId;
            this.previousInstallation = previousInstallation;
        }

        @Override
        public void close() {
            if (previousId == null) {
                CURRENT_ID.remove();
            } else {
                CURRENT_ID.set(previousId);
            }
            if (previousInstallation == null) {
                CURRENT_INSTALLATION.remove();
            } else {
                CURRENT_INSTALLATION.set(previousInstallation);
            }
        }
    }
}
