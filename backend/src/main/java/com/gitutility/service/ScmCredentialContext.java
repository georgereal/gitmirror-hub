package com.gitutility.service;

/**
 * Thread-local GitHub/GHES credential selected for the current git/API operation.
 * Bound from pair source/target ids or from picker/settings {@code credentialId}.
 */
public final class ScmCredentialContext {

    private static final ThreadLocal<Long> CURRENT_ID = new ThreadLocal<>();

    private ScmCredentialContext() {
    }

    public static void bind(Long credentialId) {
        CURRENT_ID.set(credentialId);
    }

    public static void clear() {
        CURRENT_ID.remove();
    }

    public static Long currentId() {
        return CURRENT_ID.get();
    }

    public static Scope open(Long credentialId) {
        Long previous = CURRENT_ID.get();
        CURRENT_ID.set(credentialId);
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final Long previous;

        private Scope(Long previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT_ID.remove();
            } else {
                CURRENT_ID.set(previous);
            }
        }
    }
}
