package com.gitutility.provider;

import com.gitutility.model.dto.PermissionCheckReport;
import com.gitutility.model.dto.TestConnectionRequest;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Shared helpers for anonymous public-read probes.
 * Anonymous-first applies only when Access is anonymous (no credentialId/token).
 * An explicit Access credential always wins and skips the anonymous short-circuit.
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

    /**
     * Skip anonymous probes when the remote is known-private, write is required,
     * or the operator chose Access via an explicit credential/token.
     */
    public static boolean skipAnonymousProbe(TestConnectionRequest req) {
        if (req == null) {
            return false;
        }
        if (Boolean.TRUE.equals(req.getKnownPrivate()) || writeRequired(req)) {
            return true;
        }
        if (req.getCredentialId() != null) {
            return true;
        }
        String token = req.getToken();
        return token != null && !token.isBlank();
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
                .visibility("PUBLIC")
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
                .visibility("PUBLIC")
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

    /**
     * Last-resort fallback for READ-only checks: the selected App/PAT credential failed, so probe
     * anonymous public read. When the repository is publicly readable, return a success report whose
     * message and warnings are authored here (backend) so the UI renders the fallback reason verbatim
     * instead of composing its own copy. Returns {@code null} when the repository is not publicly
     * readable and the original credential failure report must stand.
     */
    public static PermissionCheckReport publicFallbackAfterCredentialFailure(
            JsonNode publicRepoNode,
            String repoFullName,
            String providerLabel,
            int credentialHttpStatus,
            String credentialProblem) {
        if (publicRepoNode == null || publicRepoNode.path("private").asBoolean(true)) {
            return null;
        }
        String defaultBranch = publicRepoNode.path("default_branch").asText("main");
        PermissionCheckReport report = publicReadSuccess(repoFullName, defaultBranch, providerLabel);
        report.setMessage("Public read access verified — the selected credential could not access this repository, "
                + "so anonymous public read is used for this side.");
        List<String> fallbackWarnings = new ArrayList<>();
        fallbackWarnings.add("Selected credential check failed (HTTP " + credentialHttpStatus + "): " + credentialProblem);
        fallbackWarnings.add("Fell back to anonymous public read — mirroring from this side works read-only without credentials.");
        fallbackWarnings.add("Fix the credential (App installation / PAT scope) to restore authenticated access "
                + "plus PR, release and CI metadata sync.");
        report.setWarnings(fallbackWarnings);
        return report;
    }
}
