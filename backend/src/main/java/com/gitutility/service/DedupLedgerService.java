package com.gitutility.service;

import com.gitutility.model.entity.EchoLedgerEntry;
import com.gitutility.repository.EchoLedgerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class DedupLedgerService {

    @Value("${git-utility.dedup.ledger-ttl-seconds:600}")
    private long ledgerTtlSeconds;

    /**
     * Shared store. Null only in unit tests that construct this service directly;
     * a running Hub always has the H2 or Mongo implementation.
     */
    @Autowired(required = false)
    private EchoLedgerRepository echoLedgerRepository;

    // Used when no shared store is wired (unit tests).
    private final Map<String, Instant> pushLedger = new ConcurrentHashMap<>();
    private final Map<String, Instant> deleteLedger = new ConcurrentHashMap<>();

    /**
     * Record a commit pushed by this utility to a target repo to prevent bidirectional echo loops.
     */
    public void recordSystemPush(String targetRepoUrl, String commitSha) {
        if (commitSha == null || commitSha.isBlank()) {
            return;
        }
        if (writeShared(targetRepoUrl, commitSha.trim())) {
            return;
        }
        String key = buildKey(targetRepoUrl, commitSha);
        pushLedger.put(key, Instant.now());
        log.debug("Recorded automated system push in dedup ledger: {}", key);
        cleanupExpiredEntries();
    }

    /**
     * Marks a pull request this Hub just created so a replica {@code pull_request} webhook
     * that arrives on another pod before {@code pr_mappings} is saved is not opened again.
     */
    public void recordMirroredPullRequest(String repoUrl, long prNumber) {
        if (prNumber <= 0) {
            return;
        }
        writeShared(repoUrl, "pr:" + prNumber);
    }

    public boolean isMirroredPullRequest(String repoUrl, long prNumber) {
        if (prNumber <= 0) {
            return false;
        }
        return readShared(repoUrl, "pr:" + prNumber);
    }

    /**
     * Check if a commit was recently pushed to this repo by our system.
     * If true, it indicates a webhook echo caused by our own automated mirror push.
     */
    public boolean isSystemGeneratedEcho(String repoUrl, String commitSha) {
        if (commitSha == null || commitSha.isBlank()) {
            return false;
        }
        if (readShared(repoUrl, commitSha.trim())) {
            return true;
        }
        String key = buildKey(repoUrl, commitSha);
        Instant pushedTime = pushLedger.get(key);
        if (pushedTime != null) {
            long ageInSeconds = Instant.now().getEpochSecond() - pushedTime.getEpochSecond();
            if (ageInSeconds < ledgerTtlSeconds) {
                log.info("Loop detected! Commit {} on {} was pushed by this system {}s ago. Skipping mirror.",
                        commitSha, repoUrl, ageInSeconds);
                return true;
            } else {
                pushLedger.remove(key);
            }
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
        if (writeShared(targetRepoUrl, deleteToken(refName))) {
            return;
        }
        String key = buildDeleteKey(targetRepoUrl, refName);
        deleteLedger.put(key, Instant.now());
        log.debug("Recorded automated system ref delete in dedup ledger: {}", key);
        cleanupExpiredEntries();
    }

    public boolean isSystemGeneratedRefDelete(String repoUrl, String refName) {
        if (refName == null || refName.isBlank()) {
            return false;
        }
        if (readShared(repoUrl, deleteToken(refName))) {
            return true;
        }
        String key = buildDeleteKey(repoUrl, refName);
        Instant pushedTime = deleteLedger.get(key);
        if (pushedTime != null) {
            long ageInSeconds = Instant.now().getEpochSecond() - pushedTime.getEpochSecond();
            if (ageInSeconds < ledgerTtlSeconds) {
                log.info("Loop detected! Delete of {} on {} was pushed by this system {}s ago. Skipping mirror.",
                        refName, repoUrl, ageInSeconds);
                return true;
            }
            deleteLedger.remove(key);
        }
        return false;
    }

    private String buildKey(String repoUrl, String commitSha) {
        return normalizeRepo(repoUrl) + ":" + commitSha.trim();
    }

    private boolean writeShared(String repoUrl, String token) {
        if (echoLedgerRepository == null || repoUrl == null || repoUrl.isBlank() || token == null || token.isBlank()) {
            return false;
        }
        try {
            echoLedgerRepository.upsert(normalizeRepo(repoUrl), token, Instant.now().plusSeconds(ledgerTtlSeconds));
            return true;
        } catch (Exception e) {
            log.warn("Echo ledger write failed for {}: {}", normalizeRepo(repoUrl), e.getMessage());
            return false;
        }
    }

    private boolean readShared(String repoUrl, String token) {
        if (echoLedgerRepository == null || repoUrl == null || repoUrl.isBlank() || token == null || token.isBlank()) {
            return false;
        }
        try {
            return echoLedgerRepository.findById(EchoLedgerEntry.idFor(normalizeRepo(repoUrl), token))
                    .filter(row -> row.getExpiresAt() != null && row.getExpiresAt().isAfter(Instant.now()))
                    .isPresent();
        } catch (Exception e) {
            log.warn("Echo ledger read failed for {}: {}", normalizeRepo(repoUrl), e.getMessage());
            return false;
        }
    }

    private static String deleteToken(String refName) {
        String ref = refName.trim();
        if (!ref.startsWith("refs/")) {
            ref = "refs/heads/" + ref;
        }
        return "deleted:" + ref;
    }

    private String buildDeleteKey(String repoUrl, String refName) {
        return normalizeRepo(repoUrl) + ":" + deleteToken(refName);
    }

    private static String normalizeRepo(String repoUrl) {
        return RepoMappingService.normalizeRepoKey(repoUrl);
    }

    private void cleanupExpiredEntries() {
        if (pushLedger.size() > 1000) {
            Instant threshold = Instant.now().minusSeconds(ledgerTtlSeconds);
            pushLedger.entrySet().removeIf(entry -> entry.getValue().isBefore(threshold));
            deleteLedger.entrySet().removeIf(entry -> entry.getValue().isBefore(threshold));
        }
        if (deleteLedger.size() > 1000) {
            Instant threshold = Instant.now().minusSeconds(ledgerTtlSeconds);
            deleteLedger.entrySet().removeIf(entry -> entry.getValue().isBefore(threshold));
        }
    }
}
