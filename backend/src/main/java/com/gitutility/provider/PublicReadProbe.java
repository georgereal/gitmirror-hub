package com.gitutility.provider;

import com.gitutility.model.dto.PermissionCheckReport;
import com.gitutility.model.dto.TestConnectionRequest;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Shared helpers for public-first repository access: anonymous read is the primary path,
 * credentials are used only when public read fails or write access is required.
 */
public final class PublicReadProbe {

    private PublicReadProbe() {}

    public static boolean writeRequired(TestConnectionRequest req) {
        if (req == null || req.getRequiredAccess() == null) {
            return false;
        }
        String access = req.getRequiredAccess().trim();
        return "WRITE".equalsIgnoreCase(access) || "BOTH".equalsIgnoreCase(access);
    }

    /** Skip anonymous probes for known-private remotes, or whenever write is required. */
    public static boolean skipAnonymousProbe(TestConnectionRequest req) {
        if (req == null) {
            return false;
        }
        return Boolean.TRUE.equals(req.getKnownPrivate()) || writeRequired(req);
    }

    /**
     * Anonymous git ls-remote. Returns true when at least one ref is advertised without credentials.
     */
    public static boolean lsRemoteAnonymous(String repoUrl) {
        return lsRemote(repoUrl, null);
    }

    public static boolean lsRemote(String repoUrl, CredentialsProvider credentials) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return false;
        }
        try {
            Collection<Ref> refs = Git.lsRemoteRepository()
                    .setRemote(repoUrl.trim())
                    .setHeads(true)
                    .setTimeout(20)
                    .setCredentialsProvider(credentials)
                    .call();
            return refs != null && !refs.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public static PermissionCheckReport publicReadSuccess(String repoFullName, String defaultBranch, String providerLabel) {
        List<String> passed = new ArrayList<>();
        passed.add(providerLabel + " public repository reachable via anonymous HTTPS");
        if (defaultBranch != null && !defaultBranch.isBlank()) {
            passed.add("Default branch identified: " + defaultBranch);
        }
        passed.add("Read / Pull available without credentials (public clone)");
        return PermissionCheckReport.builder()
                .valid(true)
                .httpStatusCode(200)
                .repoFullName(repoFullName)
                .defaultBranch(defaultBranch != null ? defaultBranch : "main")
                .isPrivate(false)
                .accessMode("PUBLIC")
                .message("Public read access — no credentials required.")
                .permissions(PermissionCheckReport.PermissionsDetail.builder()
                        .contentsRead(true)
                        .contentsWrite(false)
                        .pullRequests(true)
                        .commitStatuses(false)
                        .webhooks(false)
                        .admin(false)
                        .build())
                .passedChecks(passed)
                .warnings(List.of())
                .errors(List.of())
                .build();
    }

    public static PermissionCheckReport publicReadButWriteNeedsCredentials(String repoFullName, String defaultBranch) {
        return PermissionCheckReport.builder()
                .valid(false)
                .httpStatusCode(401)
                .repoFullName(repoFullName)
                .defaultBranch(defaultBranch != null ? defaultBranch : "main")
                .isPrivate(false)
                .accessMode("PUBLIC")
                .message("Public read access verified, but write/push requires credentials.")
                .permissions(PermissionCheckReport.PermissionsDetail.builder()
                        .contentsRead(true)
                        .contentsWrite(false)
                        .build())
                .passedChecks(List.of("Public read access verified (anonymous HTTPS)"))
                .errors(List.of("Write/Push requires a provider token or App with Contents: Write on this repository."))
                .build();
    }
}
