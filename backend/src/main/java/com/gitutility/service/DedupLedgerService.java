package com.gitutility.service;

import com.gitutility.model.entity.EchoLedgerEntry;
import com.gitutility.repository.EchoLedgerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class DedupLedgerService {

    /**
     * SHA, ref-tip, and pull-request action markers stay until a later write replaces the tip.
     * A short TTL made origin-side a stand-in for "we wrote this", which dropped real merges.
     */
    static final Instant IDENTITY_EXPIRES_AT = Instant.parse("9999-01-01T00:00:00Z");

    /**
     * Shared store. Null only in unit tests that construct this service directly;
     * a running Hub always has the H2 or Mongo implementation.
     */
    @Autowired(required = false)
    private EchoLedgerRepository echoLedgerRepository;

    /** In-memory identity when no shared store is wired (unit tests). Value is the payload, or empty. */
    private final Map<String, String> identityPayload = new ConcurrentHashMap<>();

    /**
     * Record a commit pushed by this utility to a target repo to prevent bidirectional echo loops.
     */
    public void recordSystemPush(String targetRepoUrl, String commitSha) {
        if (commitSha == null || commitSha.isBlank()) {
            return;
        }
        writeIdentity(targetRepoUrl, commitSha.trim(), null);
    }

    /**
     * Last tip this Hub wrote to {@code refName} on {@code targetRepoUrl}.
     * A later webhook whose {@code after} SHA equals this tip is an echo of that write.
     */
    public void recordRefTip(String targetRepoUrl, String refName, String commitSha) {
        if (commitSha == null || commitSha.isBlank()) {
            return;
        }
        String ref = canonicalRef(refName);
        if (ref == null) {
            return;
        }
        writeIdentity(targetRepoUrl, tipToken(ref), commitSha.trim());
        recordSystemPush(targetRepoUrl, commitSha);
    }

    public String refTip(String repoUrl, String refName) {
        String ref = canonicalRef(refName);
        if (ref == null) {
            return null;
        }
        String payload = readIdentityPayload(repoUrl, tipToken(ref));
        return payload == null || payload.isBlank() ? null : payload;
    }

    /**
     * A push is an echo only when {@code afterSha} is a commit Hub wrote to this repo,
     * or it equals the last tip Hub wrote to this ref.
     */
    public boolean isEchoPush(String repoUrl, String refName, String afterSha) {
        if (afterSha == null || afterSha.isBlank() || RefOriginService.isDeletedSha(afterSha)) {
            return false;
        }
        if (isSystemGeneratedEcho(repoUrl, afterSha)) {
            return true;
        }
        String tip = refTip(repoUrl, refName);
        return afterSha.trim().equals(tip);
    }

    /**
     * Marks the pull request this Hub just created. Only the {@code opened} webhook for that
     * number is an echo; a later merge uses a different SHA and is not covered by this marker.
     */
    public void recordPullRequestOpened(String repoUrl, long prNumber, String headSha) {
        if (prNumber <= 0) {
            return;
        }
        writeIdentity(repoUrl, prOpenedToken(prNumber), headSha);
        if (headSha != null && !headSha.isBlank()) {
            recordSystemPush(repoUrl, headSha);
        }
    }

    /** Marks a close this Hub just performed, so the returning {@code closed} webhook is an echo. */
    public void recordPullRequestClosed(String repoUrl, long prNumber) {
        if (prNumber <= 0) {
            return;
        }
        writeIdentity(repoUrl, prClosedToken(prNumber), null);
    }

    /**
     * A pull-request webhook is an echo only when its action matches a write Hub recorded:
     * opened (or a head SHA Hub pushed), or closed because Hub closed it or pushed the merge SHA.
     * A user merge carries a new {@code merge_commit_sha} and is not an echo.
     */
    public boolean isEchoPullRequest(String repoUrl, long prNumber, String action,
                                     String headSha, String mergeSha, boolean merged) {
        if (prNumber <= 0 || action == null || action.isBlank()) {
            return false;
        }
        if (PullRequestSyncService.isOpenAction(action)) {
            if (headSha != null && isSystemGeneratedEcho(repoUrl, headSha)) {
                return true;
            }
            return hasIdentity(repoUrl, prOpenedToken(prNumber));
        }
        if ("synchronize".equalsIgnoreCase(action) || PullRequestSyncService.isEditAction(action)) {
            return headSha != null && isSystemGeneratedEcho(repoUrl, headSha);
        }
        if (PullRequestSyncService.isCloseAction(action)) {
            if (hasIdentity(repoUrl, prClosedToken(prNumber))) {
                return true;
            }
            return merged && mergeSha != null && isSystemGeneratedEcho(repoUrl, mergeSha);
        }
        return false;
    }

    /**
     * Check if a commit was pushed to this repo by our system.
     * If true, it indicates a webhook echo caused by our own automated mirror push.
     */
    public boolean isSystemGeneratedEcho(String repoUrl, String commitSha) {
        if (commitSha == null || commitSha.isBlank()) {
            return false;
        }
        String sha = commitSha.trim();
        if (hasIdentity(repoUrl, sha)) {
            log.info("Loop detected! Commit {} on {} was pushed by this system. Skipping mirror.", sha, repoUrl);
            return true;
        }
        return false;
    }

    /**
     * Record a ref deletion we pushed so the destination delete webhook is skipped as an echo.
     */
    public void recordSystemRefDelete(String targetRepoUrl, String refName) {
        if (refName == null || refName.isBlank()) {
            return;
        }
        writeIdentity(targetRepoUrl, deleteToken(refName), null);
    }

    public boolean isSystemGeneratedRefDelete(String repoUrl, String refName) {
        if (refName == null || refName.isBlank()) {
            return false;
        }
        if (hasIdentity(repoUrl, deleteToken(refName))) {
            log.info("Loop detected! Delete of {} on {} was pushed by this system. Skipping mirror.", refName, repoUrl);
            return true;
        }
        return false;
    }

    private void writeIdentity(String repoUrl, String token, String payload) {
        if (repoUrl == null || repoUrl.isBlank() || token == null || token.isBlank()) {
            return;
        }
        if (writeShared(repoUrl, token, payload)) {
            return;
        }
        identityPayload.put(buildKey(repoUrl, token), payload == null ? "" : payload);
    }

    private boolean hasIdentity(String repoUrl, String token) {
        if (readRow(repoUrl, token).isPresent()) {
            return true;
        }
        return identityPayload.containsKey(buildKey(repoUrl, token));
    }

    private String readIdentityPayload(String repoUrl, String token) {
        Optional<EchoLedgerEntry> row = readRow(repoUrl, token);
        if (row.isPresent()) {
            return row.get().getPayload();
        }
        if (!identityPayload.containsKey(buildKey(repoUrl, token))) {
            return null;
        }
        String payload = identityPayload.get(buildKey(repoUrl, token));
        return payload == null || payload.isEmpty() ? null : payload;
    }

    private boolean writeShared(String repoUrl, String token, String payload) {
        if (echoLedgerRepository == null || repoUrl == null || repoUrl.isBlank() || token == null || token.isBlank()) {
            return false;
        }
        try {
            echoLedgerRepository.upsert(normalizeRepo(repoUrl), token, IDENTITY_EXPIRES_AT, payload);
            return true;
        } catch (Exception e) {
            log.warn("Echo ledger write failed for {}: {}", normalizeRepo(repoUrl), e.getMessage());
            return false;
        }
    }

    private Optional<EchoLedgerEntry> readRow(String repoUrl, String token) {
        if (echoLedgerRepository == null || repoUrl == null || repoUrl.isBlank() || token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            return echoLedgerRepository.findById(EchoLedgerEntry.idFor(normalizeRepo(repoUrl), token))
                    .filter(row -> row.getExpiresAt() != null && row.getExpiresAt().isAfter(Instant.now()));
        } catch (Exception e) {
            log.warn("Echo ledger read failed for {}: {}", normalizeRepo(repoUrl), e.getMessage());
            return Optional.empty();
        }
    }

    private static String prOpenedToken(long prNumber) {
        return "pr:" + prNumber + ":opened";
    }

    private static String prClosedToken(long prNumber) {
        return "pr:" + prNumber + ":closed";
    }

    private static String tipToken(String canonicalRef) {
        return "tip:" + canonicalRef;
    }

    private static String canonicalRef(String refName) {
        return RefOriginService.canonicalHeadRef(refName);
    }

    private String buildKey(String repoUrl, String token) {
        return normalizeRepo(repoUrl) + ":" + token.trim();
    }

    private static String deleteToken(String refName) {
        String ref = canonicalRef(refName);
        if (ref == null) {
            ref = refName.trim();
        }
        return "deleted:" + ref;
    }

    private static String normalizeRepo(String repoUrl) {
        return RepoMappingService.normalizeRepoKey(repoUrl);
    }
}
