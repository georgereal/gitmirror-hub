# Silent per-ref fetch failures & unguarded bare-repo dir (fetch-ref verification + RepoDirLockService)

> **Status:** Core shipped (this change). Follow-ups below are still open.
> **Confirmed on:** pair with `feature/…Lab4` reporting `PENDING_SYNC` while `git ls-remote` showed the real destination tip already mirrored.

## Problem statement

Two cooperating defects made a *successfully completed* sync job leave a branch's local
tracking ref stale forever, so every later diff read the wrong tip and the pair page kept
flipping the branch between Source and Target columns.

### 1. Silent per-ref fetch failures (JGit `FetchResult` discarded)

JGit's `git.fetch().call()` returns a `FetchResult` containing a `TrackingRefUpdate` per
ref, each with its own `RefUpdate.Result` (`NEW` / `FAST_FORWARD` / `FORCED` / `NO_CHANGE`
on success; `LOCK_FAILURE` / `IO_FAILURE` / `REJECTED` / `NOT_ATTEMPTED` / `AWAITING_REPORT`
on failure). **Several fetch call sites threw the return value away** (`.call();` discarded).
When an individual ref write failed — e.g. a `.lock` file held by a concurrent process —
the overall fetch "succeeded": the job logged *"Destination inspection complete"* / finished
`SUCCESS`, and every downstream reader (DB rollup counts, `GitComparisonService`'s refresh
diff, the branch comparison table) kept reading the same stale local ref indefinitely,
because nothing ever noticed or re-attempted that one ref's write.

### 2. No mutual exclusion between sync jobs and diff refresh on the same bare repo

`QueueConsumerService` serialized sync jobs per mapping via a **private**
`Map<Long, ReentrantLock> repoLocks` (keyed by `mappingId`). But
`GitComparisonService.computeSyncDiffUncached` (page-load quick diff and *"Refresh Diff"*)
performed its **own** `git.fetch()` calls against source and target of the *same* bare repo
directory with **no lock at all**. When a scheduled/manual sync job fetched/pushed into
`pair-<id>.git` at the same moment a user (or auto-refresh poll) triggered a diff refresh,
both JGit operations raced for the same loose-ref lock files; one lost with
`LOCK_FAILURE`, which — combined with defect 1 — was invisible.

**Why the branch table "flipped":** whichever of the two concurrent readers/writers won the
ref write last determined which value ended up in which column, because both code paths
read and wrote the same on-disk refs with no mutual exclusion between them.

## Shipped fix (this change)

1. **New `RepoDirLockService`** (small `@Service`): a single
   `ConcurrentHashMap<Long, ReentrantLock>` keyed by `mappingId` with a `lockFor(Long
   mappingId)` accessor. It becomes the one source of truth for "is anything currently
   mutating this mapping's bare repo directory".
2. **`QueueConsumerService`**: its private `repoLocks` map is replaced with the injected
   `RepoDirLockService.lockFor(…)` at the same lock/unlock call sites — sync jobs stay
   serialized per mapping, now through the shared registry.
3. **`GitComparisonService`**: the network-refresh block inside
   `computeSyncDiffUncached` (the `if (inspect.refresh()) { … }` section doing the
   source/target `git.fetch()` calls) is wrapped with
   `repoDirLockService.lockFor(mappingId).lock()` / `.unlock()` in a `try/finally`, so a
   diff refresh can never run concurrently with a sync job's fetch/push on the same bare
   repo, and vice versa.
4. **`BareRepoHousekeeping.logFailedTrackingRefUpdates(FetchResult, String contextLabel,
   Consumer<String> auditLogger)`**: classifies each `TrackingRefUpdate`'s
   `RefUpdate.Result` as success/failure, logs a WARN per failed ref (ref name, old/new
   object ids, result code) through the caller-supplied logger (SLF4J `log.warn` when
   null), and returns the list of failed ref names.
5. **`GitSyncEngine`**: `SyncResult` gains `public List<String> failedFetchRefs`.
   - *Inspect destination* (`INSPECT_DEST`) fetch now captures the `FetchResult`, calls the
     helper, appends failures to `result.failedFetchRefs`, and logs a WARN summary line.
   - *Source fetch* (`doSourceFetch`, used by the public-first path) does the same capture +
     helper call and propagates failures up to the caller, which appends to
     `result.failedFetchRefs` and logs the WARN summary.
6. **`GitComparisonService`** refresh-mode fetches: same capture + helper call, logged via
   `log.warn` (HTTP request scope — no job audit log there).

This does **not** eliminate the possibility of a transient `LOCK_FAILURE` from some other
cause (e.g. an OS crash leaving a stale `.lock` file); it removes the specific, now-confirmed
cause — two JGit operations inside the same JVM touching the same bare repo concurrently
with no coordination — and makes any remaining failure loud instead of silent.

## Explicitly out of scope for this pass

- No automatic repair/retry of a failed ref (log the real `RefUpdate.Result` first).
- No new persisted per-branch DB table (aggregate-only `PairDiffSnapshot` stays as-is).
- No frontend/UI changes (`quickDiffCache` / `branchListCache` TTLs were ruled out as the
  root cause for this specific case).
- `PullRequestSyncService`'s several `git.fetch()` call sites are not yet instrumented
  (they are per-PR refspecs; lower risk, but should adopt the same helper — see follow-ups).

## Verification

1. Trigger a manual full sync and, at the same time, load/refresh the repo detail page (or
   click *Refresh Diff*) for the same mapping — before the fix this reliably raced; after
   the fix one of the two should visibly wait (blocked on the lock) rather than both
   proceeding and one hitting `LOCK_FAILURE`.
2. Re-run the original repro (full sync on the affected mapping) and confirm the WARN lines
   for `refs/remotes/target/…` no longer appear.
3. Confirm the affected branch settles to a single consistent Source/Target pair instead of
   flipping between runs.
4. Regression: full sync on a healthy mapping — confirm no new WARN spam and
   `pendingBranchesCount` stays accurate.
5. `mvn compile` + unit tests on the modified files; existing unit tests updated for the
   new `QueueConsumerService` constructor parameter.

## Follow-ups (not done here)

- [ ] Adopt `logFailedTrackingRefUpdates` in `PullRequestSyncService`'s fetch call sites.
- [ ] Consider a single bounded retry of just the failed refspecs once real-world result
      codes are observed.
- [ ] Consider surfacing a distinct "stale — local fetch failed" indicator in the UI,
      separate from `PENDING_SYNC`, once the failure mode is confirmed.
- [ ] Warn-on-write for case-colliding branch pairs (Windows/OS X case-insensitive
      filesystems) — a *separate* structural issue: two distinct refs colliding onto one
      physical file path. Track independently if it recurs.
