package com.gitutility.service;

/**
 * Request-scoped SCM quota context (installation + repo) for {@link ScmQuotaTracker}.
 */
public final class ScmQuotaContext {

    private static final ThreadLocal<Bound> CURRENT = new ThreadLocal<>();

    private ScmQuotaContext() {
    }

    public static void bind(String provider, String installationKey, String repoFullName) {
        CURRENT.set(new Bound(provider, installationKey, repoFullName));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Bound current() {
        return CURRENT.get();
    }

    public record Bound(String provider, String installationKey, String repoFullName) {
    }
}
