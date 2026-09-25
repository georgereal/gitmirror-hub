# Done: Bulk Repo Migration (Multi-Pair Submission from the Add Repo Pair Modal)

> **Status:** Complete.  
> **Shipped:** Bulk tab in the Add Repo Pair modal, multi-select picker, `POST /api/v1/mappings/bulk`, submission records, `POST /api/v1/bulk/{id}/cancel`, and the Bulk tab parallelism hint (`Rabbit lane · up to N consumers` / `In-process threads · M`, linking to System Engine).

---

## 1. Executive Summary & Goal

Add a **Bulk migration** tab to the Add Repo Pair modal so an operator can mirror **many repositories in one submission**:

1. Select **multiple source repos** via the existing Browse explorer, extended for **multi-select that survives paging and searches**.
2. Choose a **destination strategy**:
   * **Option 1 — Create destination repos** (same name by default, editable per row; created lazily at job start).
   * **Option 2 — Use existing destination repos** (multi-select, paired 1:1 with sources).
3. Show and apply the **shared pair parameters** (branch pattern, direction, conflict policy, storage tier, active) to every row.
4. On submit, the backend **creates one mapping + one bootstrap full-mirror job per source**, skipping rows that already mirror or already exist as a destination (with an explicit warning, never silent mapping). Jobs then flow through the **existing execution pipeline** — running in parallel on whichever worker capacity is available and waiting as `QUEUED` when it is exhausted.
5. Every submission gets a **bulk submission id**: per-row outcomes (including skipped rows — see §3.2) persist on a lightweight submission record, the whole batch of jobs can be **cancelled in one action** (§3.5), and an **access failure at the destination-creation step fails that job immediately and stops the rest of the batch** (§3.3).

### Concurrency models already in place (no new execution code required)

| `GIT_MESSAGING_PROVIDER` | Where bulk jobs wait | Parallelism | Overflow behavior |
| :--- | :--- | :--- | :--- |
| **`rabbitmq`** (default) | Durable broker queue `git.sync.queue` | Full-lane `@RabbitListener` per pod, `concurrency: 1`, `max-concurrency: 5`, prefetch 1 (`application.yml`); more pods = more competing consumers | Messages wait in the broker; `SyncJob` rows stay `QUEUED`; pause/resume/cancel/dispatch controls work unchanged |
| **`none`** (in-process) | `NoneSyncEventBus` in-process queue | Fixed pool `GIT_MESSAGING_NONE_WORKER_THREADS` (default **8**) | Excess events wait; jobs stay `QUEUED`; consumer-pause defer logic unchanged |

Existing pod-wide safety nets still bound the flood: push semaphore `GIT_THROTTLE_MAX_CONCURRENT_PUSHES` (5), push-batch waves + per-destination-host cap + throttle cooldown, App-install quota context, circuit breakers, and the per-pair lease in multi-pod fleets.

**UI nicety:** the Bulk tab shows the active provider's effective parallelism via the existing `GET /api/v1/messaging` descriptor ("Rabbit lane · up to N consumers" vs "in-process threads · M") and links to where `max-concurrency` / `worker-threads` can be raised.

---

## 2. Frontend (React 19 + Tailwind 4)

### 2.1 Tabs in the Add Repo Pair modal — `frontend/src/components/PairConfigModal.tsx`
* Tab strip in the modal header: **Single pair** | **Bulk migration**. Bulk tab hidden in **edit** mode.
* Bulk tab content lives in a new component `frontend/src/components/BulkMigrationTab.tsx` (keeps `PairConfigModal` from growing further; reuses its credential catalog, create-dest owner/installation logic, and shared-parameter controls).

### 2.2 Multi-select source browser — extend `frontend/src/components/RepoPickerModal.tsx`
* New props: `multiSelect?: boolean`, `initialSelection?`, `onConfirmMulti(repos: GitHubRepoOption[])`, `conflictKeys?: Set<string>` (repos already in an active pair → amber "already mirrored" badge; selection still allowed — the server enforces the skip).
* **Selection survives across pages and searches**: internal `Map<normalizedCloneUrl, GitHubRepoOption>`; changing search / provider / credential / page never clears it; per-row checkbox + "Select all on this page"; sticky footer with `N selected · Clear · Confirm`.
* Dedupe on confirm by normalized URL (`utils/repoUrl.normalizeRepoKey`).
* Confirmed selection renders in the bulk tab as a review table (fullName · provider · credential · remove ✕).

### 2.3 Destination section — 2 radio options
* **Option 1 — Create destination repos**
  * Shared destination credential select + create-permission preflight (reuse the single-pair create panel's App install `canCreateRepo` / `assertCanCreateRepository` semantics).
  * Shared owner/org (default = credential login or chosen App installation); default **private** visibility (public only when `publicReposEnabled`).
  * **Per-row editable destination name**, prefilled with the source repo name; "Reset all to source name" action; live `https://github.com/{owner}/{name}.git` URL preview per row.
* **Option 2 — Use existing destination repos**
  * Same multi-select picker in **PUSH** mode; sources paired 1:1 by selection order with smart same-name matching first, plus per-row dropdown to reassign; per-row destination credential carried from the picker.
  * Generic warning when the destination already exists / may contain content (no deep content probe in v1).

### 2.4 Shared parameters card
Branch pattern (`*`), sync direction (BIDIRECTIONAL default), trunk conflict policy (ISOLATE), storage tier (AUTO_LRU), active toggle — same controls as the single-pair form, applied to all rows.

### 2.5 Review + submit
* **Non-empty destination decision (Option 2):** after destinations are picked, a lightweight "has commits?" probe runs (chunked, §3.1). Rows whose destination already contains commits get an amber **"has content"** badge; the operator **decides per row** (include anyway / exclude) with batch actions ("Exclude all flagged" / "Include all flagged"). Only explicitly included rows are submitted.
* Live validation summary: **ready / skipped (already mirrored · duplicate · invalid) / warnings**. *Skipped rows never become pairs or jobs — so they cannot appear on the Execution page; their outcomes are recorded on the submission record (§3.1) instead.*
* `POST /api/v1/mappings/bulk` → the response renders a summary card: **X pairs created & queued** (link to `/queues`), **Y skipped with reasons**, **Z invalid**; created jobs stream into Queue Manager live via the existing WebSocket. The summary stays queryable on the submission record (Queue Manager → Bulk submissions panel) after the modal closes.

## 3. Backend (Spring Boot)

### 3.1 New endpoint — `POST /api/v1/mappings/bulk`
* DTOs: `BulkMirrorRequest` (items[] with source `{url, provider, credentialId, installationId, visibility, publicRead}`; per-item dest URL for Option 2; shared `destOwner` / `destCredentialId` / `destPrivate` + per-item `destName` for Option 1; per-item `includeNonEmptyDest` — the Option-2 "has content" decision from §2.5; shared pair params) and `BulkMirrorResponse` (per-row `CREATED_QUEUED | SKIPPED | FAILED_VALIDATION` + reason + mappingId/jobId, plus counts and a `submissionId`).
* New `service/BulkMirrorService.java` (keeps `RepoMappingService` lean; reuses its validation helpers).
* **No hard execution cap** — worker threads / consumer concurrency pace execution, so extra jobs simply wait as `QUEUED`. A **soft guard** `git-utility.bulk.max-items` (default **1000**) only bounds the *synchronous HTTP request* (submission-time probing of tens of thousands of rows would otherwise risk a client/edge timeout). It is not a concurrency cap.
* **Probes run in chunks of 250** (`git-utility.bulk.probe-chunk-size`): destination-exists (Option 1) and write-access + has-commits (Option 2) checks process items in 250-row batches with bounded concurrency (≈5) inside each batch. This chunking is the only "cap" and it applies to the quick checks — not to execution.
* **Submission record:** new table `bulk_submissions` (`id`, `created_at`, `mode`, `item_count`, `created_count`, `skipped_json` with per-row outcomes, `cancelled_at`) plus a `bulk_submission_id` column on `repo_mappings` (jobs inherit it via `mappingId`). This is what keeps skipped-row details queryable after the modal closes and what powers bulk cancel (§3.5).
* Trigger type stays `MANUAL`; commit message "Bulk migration bootstrap" so existing job-history filters keep working.

### 3.2 Submission-time rules (per row, in order)
1. **Dedupe** sources (normalized URL) → `SKIPPED — duplicate`.
2. **Intra-batch collisions**: duplicate destination URLs (Option 1: same owner+name; Option 2: same URL), source == destination → `SKIPPED`.
3. **Collision with existing active pairs** via `RepoMappingService.validateNoRepositoryCollisions` logic → `SKIPPED` with warning "already participating in active mirror pair 'X'" (decision: warn + skip, never silently map).
4. Per-row **feature-flag check** (`assertMappingAllowed`) and **GitHub credential binding** (`requireBoundIfGithub`); `assertCanCreateRepository(destCredentialId, owner)` once for Option 1.
5. **Option 1 destination-exists probe** (quick check before creating the job): for each candidate dest URL, call a new `adapter.repositoryExists(url)` under the destination credential, bounded-parallel (≈5), rate-limit aware → exists ⇒ `SKIPPED` with warning "mirror already present (destination repo already exists)".
   * 403/ambiguous probe ⇒ row proceeds with a "could not verify — creation attempted at run time" note (the job-time step is idempotent, so a false negative is safe).
   * Option 2 rows: bounded-parallel **WRITE** verification → no write access ⇒ `FAILED_VALIDATION` (hard; operator must fix credentials), plus the lightweight **has-commits** probe; rows the operator did not explicitly include (§2.5) ⇒ `SKIPPED — destination has existing content (excluded by operator)`.
6. **Surviving rows**: save the mapping (same defaults as `createMapping`) and enqueue the bootstrap full job via the existing `triggerInitialBootstrapSync` path. **Row-level failure isolation**: one bad row never aborts the batch.

### 3.3 At-job-start destination creation (Option 1) — new capability
* New persisted column `repo_mappings.destination_auto_create` (default false) via `DatabaseSchemaMigrator` (`ADD COLUMN IF NOT EXISTS`); set true for Option-1 rows.
* `GitSyncEngine.executeSync` gains an early **"create-destination"** stage (after mapping resolve, before fetch):
  * Reads the flag **from the mapping entity** (not from `SyncEventMessage`) — no event-DTO change; survives orphan recovery, re-dispatch, and multi-pod rebuilds.
  * `repositoryExists(repoBUrl)` under the target credential context → missing ⇒ `facade.createRemoteRepository(CreateRepoRequest{repoUrl=repoB, owner/name via parseRepoFullName, isPrivate per targetVisibility, credentialId=targetCredentialId})`; exists ⇒ WARN "destination already exists — continuing mirror"; then **flip the flag off** so later jobs skip the probe.
  * **Access failures fail fast** (401/403, missing Administration write permission, unusable credential): the job **fails immediately with no retry** — access will not heal through retries — and the remaining `QUEUED` jobs of the **same submission** targeting the **same destination credential + owner** are cancelled with reason "destination creation failed (access) — batch stopped" (Option 1 shares one credential + owner across the batch, so siblings would fail identically; there is no point in continuing).
  * Other creation failures (network, 5xx, 429) ⇒ clear audit-log line + normal retry path (3 attempts → DLQ). The job fails loudly, never silently.
  * **Race window (destination appears between submission probe and job run) ⇒ the job FAILS permanently** with "destination repository was created during the migration window and not by GitMirror Hub — refusing to touch it; remove or rename it, then re-run." It was not created by this tool, so overwriting it is unsafe; no retry, no auto-continue.

### 3.4 Adapter additions
* `ScmProviderAdapter.repositoryExists(String repoUrl)` (default false), implemented for GitHub, GHES, GitLab, Bitbucket, Origin: GET repo endpoint → 404 ⇒ false; 200 ⇒ true; 403 ⇒ false + log ambiguity.
* `ScmProviderAdapter.hasCommits(String repoUrl)` (default false) for the Option-2 "has content" probe (cheapest provider signal: default-branch latest commit / commit count).

### 3.5 Bulk cancel (jobs started as part of one submission)
* `POST /api/v1/bulk/{submissionId}/cancel` → resolves the submission's mappings → reuses existing per-job cancel semantics generalized to the mapping set: **queued** jobs cancelled via `SyncJobService` (the `cancelQueuedJobs(mappingId)` path), **in-progress** jobs cancelled cooperatively via `JobCancellationService`; marks the submission record `cancelled_at`; returns per-job results.
* UI: Queue Manager groups the submission's jobs under a **"Bulk migration" chip** with a **Cancel all** action; the submission panel (§3.1) lists per-row/job outcomes. Existing single-job pause/resume/cancel controls keep working per job.
* `none` provider: "queued" = in-process deferred events + `QUEUED` rows not yet picked by the worker pool — the same cancel path covers them (the cancel flag is already checked on pickup).
* Skipped rows have no jobs, so bulk cancel never touches them; the submission record keeps their outcomes.

---

## 4. Tests & Validation
* **Backend** (`mvn -f backend/pom.xml test`):
  * `BulkMirrorServiceTest` — every skip rule (duplicate, existing-pair collision, exists-probe, intra-batch dest dup, flags/credential binding, has-commits exclusion), partial-failure isolation, soft request guard, Option-2 write check.
  * `GitSyncEngine` destination-create stage — creates-when-missing, continues-when-exists (flag flip), failure → job fail path.
  * `repositoryExists` / `hasCommits` per adapter (200 / 404 / 403).
  * Fail-fast: access failure at the create step → job fails **without retry** + sibling queued jobs of the submission (same credential + owner) cancelled; transient failure → normal retry path; race (destination appeared) → permanent job failure.
  * Bulk cancel: queued + in-progress jobs of one submission cancelled; submission record marked `cancelled_at`.
  * Probe chunking: >250 items processed in 250-row chunks with bounded concurrency; soft guard 1000 enforced with a clear error.
* **Frontend** — `npm run build` (tsc) plus manual:
  * Multi-select across pages/search preserves selection; dest-name editing; both destination options; submit → summary card → Queue Manager shows N `QUEUED` jobs.
  * With `GIT_MESSAGING_PROVIDER=none` + `GIT_MESSAGING_NONE_WORKER_THREADS=2`: observe parallel-2 execution with the rest queued; repeat on rabbit with listener `max-concurrency`.

---

## 5. Non-Goals
* No new execution/concurrency machinery — bulk feeds the existing lane pipeline for both messaging providers.
* No full destination content diff in v1 — only the lightweight "has commits" probe plus an explicit operator decision (§2.5).
* No cross-pod job sharding of a single mirror (respects ARCHITECTURE tenet 7: scale out by pairs).

---

## 6. Resolved Design Decisions
1. **Job-time race** (destination appears between submission and run): **FAIL the job permanently** — the repo was created by someone else during the migration window and is not safe to touch; the message tells the operator to remove/rename it and re-run. No retry, no auto-continue.
2. **Option 2 non-empty destinations**: lightweight "has commits?" probe **warns, and the user decides** per row (include anyway / exclude), with batch include/exclude actions for flagged rows (§2.5).
3. **No execution cap.** Worker threads / consumer concurrency pace execution; extra jobs wait as `QUEUED`. Only guards added: submission-time probes run in **chunks of 250** (`git-utility.bulk.probe-chunk-size`) and a **soft request-size guard of 1000** (`git-utility.bulk.max-items`) to avoid HTTP-timeout stalls — neither limits concurrent execution.
4. **Skipped rows** = submission rows that never became pairs/jobs (duplicates, already-mirrored, existing-destination, operator-excluded, invalid). They cannot appear on the Execution page (nothing was enqueued); their per-row outcomes persist on the **submission record** (§3.1) and render in the post-submit summary and Queue Manager's Bulk submissions panel. All *created* jobs appear on the Execution/Queue pages as usual.

---

## 7. Docs
[`REPO_MAP.md`](../../REPO_MAP.md) lists `BulkMigrationController`, `BulkMirrorService`, `BulkSubmissionService`, the bulk tab components, and `POST /api/v1/mappings/bulk` plus `GET/POST /api/v1/bulk`. Bulk jobs use the existing messaging lanes, so queue topology in `ARCHITECTURE.md` is unchanged.
