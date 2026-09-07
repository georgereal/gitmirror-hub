package com.gitutility.service;

import lombok.extern.slf4j.Slf4j;
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

    // Key: targetRepoUrl + ":" + commitSha, Value: Timestamp of push
    private final Map<String, Instant> pushLedger = new ConcurrentHashMap<>();
    // Key: normalizedRepo + ":deleted:" + ref, Value: Timestamp of our dest ref delete
    private final Map<String, Instant> deleteLedger = new ConcurrentHashMap<>();

    /**
     * Record a commit pushed by this utility to a target repo to prevent bidirectional echo loops.
     */
    public void recordSystemPush(String targetRepoUrl, String commitSha) {
        if (commitSha == null || commitSha.isBlank()) {
            return;
        }
        String key = buildKey(targetRepoUrl, commitSha);
        pushLedger.put(key, Instant.now());
        log.debug("Recorded automated system push in dedup ledger: {}", key);
        cleanupExpiredEntries();
    }

    /**
     * Check if a commit was recently pushed to this repo by our system.
     * If true, it indicates a webhook echo caused by our own automated mirror push.
     */
    public boolean isSystemGeneratedEcho(String repoUrl, String commitSha) {
        if (commitSha == null || commitSha.isBlank()) {
            return false;
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
        String key = buildDeleteKey(targetRepoUrl, refName);
        deleteLedger.put(key, Instant.now());
        log.debug("Recorded automated system ref delete in dedup ledger: {}", key);
        cleanupExpiredEntries();
    }

    public boolean isSystemGeneratedRefDelete(String repoUrl, String refName) {
        if (refName == null || refName.isBlank()) {
            return false;
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

    private String buildDeleteKey(String repoUrl, String refName) {
        String ref = refName.trim();
        if (!ref.startsWith("refs/")) {
            ref = "refs/heads/" + ref;
        }
        return normalizeRepo(repoUrl) + ":deleted:" + ref;
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
