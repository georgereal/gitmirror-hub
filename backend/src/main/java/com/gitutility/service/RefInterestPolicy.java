package com.gitutility.service;

import com.gitutility.model.entity.RepoMapping;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Decides which branch/PR heads warrant an immediate webhook-driven sync versus
 * waiting for the next Smart full sync (tip probe). Critical for agentic repos
 * that create short-lived {@code agents/…} branches at high frequency.
 */
@Component
@Slf4j
public class RefInterestPolicy {

    public static final String DISCARD_EPHEMERAL = "EPHEMERAL_REF_IGNORED";
    public static final String DISCARD_PATTERN = "BRANCH_PATTERN_IGNORED";
    public static final String DISCARD_COALESCED = "INCREMENTAL_COALESCED";

    private final List<String> ephemeralPrefixes;
    private final long coalesceWindowMs;
    private final ConcurrentHashMap<Long, AtomicLong> lastIncrementalEnqueueMs = new ConcurrentHashMap<>();

    @Getter
    private final boolean coalesceEnabled;

    public RefInterestPolicy(
            @Value("${git-utility.sync.ephemeral-branch-prefixes:agents/,agent/,dependabot/,renovate/,snyk-bot/,greenkeeper/,fork-pr-,sync-conflict/}")
            String ephemeralPrefixesCsv,
            @Value("${git-utility.sync.incremental-coalesce-window-ms:45000}") long coalesceWindowMs,
            @Value("${git-utility.sync.incremental-coalesce-enabled:true}") boolean coalesceEnabled) {
        this.ephemeralPrefixes = parsePrefixes(ephemeralPrefixesCsv);
        this.coalesceWindowMs = Math.max(0L, coalesceWindowMs);
        this.coalesceEnabled = coalesceEnabled;
        log.info("RefInterestPolicy: ephemeralPrefixes={}, coalesceWindowMs={}, coalesceEnabled={}",
                this.ephemeralPrefixes, this.coalesceWindowMs, this.coalesceEnabled);
    }

    static List<String> parsePrefixes(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public List<String> ephemeralPrefixes() {
        return List.copyOf(ephemeralPrefixes);
    }

    public boolean isEphemeralAutomationBranch(String branchOrRef) {
        String branch = RefOriginService.branchName(branchOrRef);
        if (branch == null || branch.isBlank()) {
            return false;
        }
        String lower = branch.toLowerCase(Locale.ROOT);
        return ephemeralPrefixes.stream().anyMatch(lower::startsWith);
    }

    /** Trunks and release lines always get live webhook sync. */
    public boolean isHighPriorityWebhookBranch(String branchOrRef) {
        String branch = RefOriginService.branchName(branchOrRef);
        if (branch == null || branch.isBlank()) {
            return false;
        }
        if (RefOriginService.isProtectedTrunk(branch)) {
            return true;
        }
        String lower = branch.toLowerCase(Locale.ROOT);
        return lower.startsWith("release/") || lower.startsWith("releases/");
    }

    /**
     * Whether a push webhook should enqueue an incremental sync.
     * Ephemeral agent/bot refs are skipped (caught by Smart full sync).
     * When {@code branchPattern} is not {@code *}, only matching branches enqueue.
     */
    public boolean shouldEnqueuePushWebhook(RepoMapping mapping, String branch) {
        if (branch == null || branch.isBlank()) {
            return false;
        }
        if (isEphemeralAutomationBranch(branch)) {
            return false;
        }
        String pattern = mapping != null ? mapping.getBranchPattern() : null;
        return matchesBranchPattern(pattern, branch);
    }

    public boolean shouldHandlePrWebhook(RepoMapping mapping, String headBranch) {
        if (headBranch == null || headBranch.isBlank()) {
            return true;
        }
        if (isEphemeralAutomationBranch(headBranch)) {
            return false;
        }
        String pattern = mapping != null ? mapping.getBranchPattern() : null;
        return matchesBranchPattern(pattern, headBranch);
    }

    /**
     * Comma/space-separated patterns. {@code *} or blank = all. Supports trailing {@code *} wildcards
     * (e.g. {@code release/*}) and exact names.
     */
    public static boolean matchesBranchPattern(String pattern, String branch) {
        if (branch == null || branch.isBlank()) {
            return false;
        }
        if (pattern == null || pattern.isBlank() || "*".equals(pattern.trim())) {
            return true;
        }
        String b = branch.trim();
        for (String raw : pattern.split("[,\\s]+")) {
            String part = raw.trim();
            if (part.isEmpty()) {
                continue;
            }
            if ("*".equals(part)) {
                return true;
            }
            if (part.endsWith("/*")) {
                String prefix = part.substring(0, part.length() - 1); // keep trailing /
                if (b.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    return true;
                }
            } else if (part.endsWith("*")) {
                String prefix = part.substring(0, part.length() - 1);
                if (b.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    return true;
                }
            } else if (b.equalsIgnoreCase(part)) {
                return true;
            }
        }
        return false;
    }

    /**
     * For non-priority branches, skip enqueue if another incremental job was accepted
     * for this pair within the coalesce window. High-priority (trunk/release) never coalesced away.
     *
     * @return true if this enqueue should proceed
     */
    public boolean tryAcceptIncrementalEnqueue(Long mappingId, String branch) {
        if (!coalesceEnabled || coalesceWindowMs <= 0 || mappingId == null) {
            return true;
        }
        if (isHighPriorityWebhookBranch(branch)) {
            touchEnqueue(mappingId);
            return true;
        }
        long now = System.currentTimeMillis();
        AtomicLong last = lastIncrementalEnqueueMs.computeIfAbsent(mappingId, id -> new AtomicLong(0L));
        long prev = last.get();
        if (prev > 0 && (now - prev) < coalesceWindowMs) {
            return false;
        }
        last.set(now);
        return true;
    }

    private void touchEnqueue(Long mappingId) {
        lastIncrementalEnqueueMs.computeIfAbsent(mappingId, id -> new AtomicLong(0L))
                .set(System.currentTimeMillis());
    }

    /** Test helper / operator diagnostics. */
    void clearCoalesceState() {
        lastIncrementalEnqueueMs.clear();
    }
}
