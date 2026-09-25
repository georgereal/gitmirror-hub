package com.gitutility.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Shared per-mapping mutex guarding the bare repo directory on disk.
 *
 * Both the sync engine (via {@code QueueConsumerService}) and the diff/comparison
 * service ({@code GitComparisonService} refresh mode) fetch into and rewrite loose
 * refs of the SAME bare repo directory. Without a shared lock, two JGit operations
 * race for the same loose-ref / packed-refs lock files and one silently loses with
 * {@code RefUpdate.Result.LOCK_FAILURE} — the job still reports SUCCESS while a
 * tracking ref keeps its stale tip.
 *
 * The registry lives here (not on the consumer) so any component mutating the
 * mapping's bare repo directory can serialize against sync jobs.
 */
@Service
public class RepoDirLockService {

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * Returns the lock for the given mapping. Everything currently mutating this
     * mapping's bare repo directory must hold it for the duration of the mutation.
     * Null mapping ids are tolerated (coalesced onto one fallback lock) instead of
     * throwing, mirroring defensive behavior elsewhere in the consumer path.
     */
    public ReentrantLock lockFor(String mappingId) {
        return locks.computeIfAbsent(mappingId != null ? mappingId : "-unassigned", k -> new ReentrantLock());
    }
}
