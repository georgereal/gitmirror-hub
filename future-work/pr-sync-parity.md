# Future plan: PR sync parity (toward Origin-like coverage)

> **Status:** Partial — PR **shell** shipped; discussion/reviews/merge/webhooks still pending.  
> **Folder:** [`future-work/`](README.md)  
> **Context:** Cursor Origin mirrors a richer PR collaboration surface (comments, reviews, merge, checks UI). Hub today mirrors a **PR shell** for DR/failover.

## Hub coverage

| Area | Status |
| :--- | :--- |
| Create open PR (title, body, head/base) | **Shipped** (mirrored body footer) |
| Edit title/body (origin → replica, CAS) | **Shipped** |
| Close when origin closes/merges | **Shipped** (closes replica; **does not merge**) |
| Fork heads as `fork-pr-{n}` | **Shipped** |
| Fetch `refs/pull/*/head` into bare cache | **Shipped** (not pushed as `refs/pull/*` on dest) |
| Parallel PR create pool | **Shipped** (`prCreateExecutor`) |
| Issue / review comments | **Pending** (`replicateComments` unused) |
| Reviews / approvals / request reviewers | **Pending** |
| Labels / assignees / milestone | **Pending** |
| Draft flag on create | **Pending** (read for UI only) |
| Merge completion on replica | **Pending** |
| Reopen / `synchronize` | **Pending** |
| GitHub `pull_request` webhooks | **Pending** — push-only at controller + Cloudflare worker |

Bulk open-PR sync is **A → B**; realtime PR path is limited by ingress.

## Origin (documented) for comparison

- Bidirectional PR sync: comments, reactions, replies
- Reviews (approve / request changes), request reviewers
- Merge from Origin → GitHub
- Activity / commits / checks / files-changed UI
- Issues and Actions secrets **not** mirrored

## Tiered delivery (do not big-bang)

| Tier | Scope | Status | Effort | Risk |
| :--- | :--- | :--- | :--- | :--- |
| **Shell (base)** | Open/edit/close, fork heads, `refs/pull/*/head` cache | **Shipped** | — | — |
| **A – Shell polish** | Draft on create; reopen; head `synchronize`; re-enable GitHub PR webhooks safely | **Pending** | Days–~1 week | Low–medium |
| **B – Discussion** | Issue + inline review comments both ways; comment id mapping; echo dedup | **Pending** | 1–2+ weeks | Medium |
| **C – Reviews** | Request reviewers; approve / changes requested / dismiss | **Pending** | +1–2 weeks on B | High |
| **D – Merge parity** | Merge (not only close); conflict/check awareness; authority policy | **Pending** | Large | High |
| **E – Labels / assignees / milestone** | Field sync once webhooks + mapping exist | **Pending** | Medium | Medium |

**Recommended order:** A → B; defer C/D until directionality and “which side may merge” are product-clear. GitHub/GHES first; other SCMs later.

## Hard problems to design up front

1. **Identity** — App bot vs human authors (attribution footers vs user mapping).
2. **Loop control** — Bidirectional comments/reviews without echo storms.
3. **Merge authority** — Replica merge ≠ source merge under branch protection.
4. **Ingress** — Reopening `pull_request` (and review) events without drowning the incremental lane.
5. **Actions suppression** — PR API writes also trigger workflows; reuse App-bot cancel / org policy.

## Existing hooks to reuse

- `PrMapping` + CAS for title/body
- `ScmProviderAdapter.replicateComments` (implemented, unused)
- Fork head materialization + `RefOriginService`
- `SyncConflictService` metadata conflict pattern
- `ActionsTriggerSuppressionService` for post-write cancel

## New persistence (expected)

- Comment / review thread mapping tables (`sourceId` ↔ `destId`)
- Optional review-state ledger
- Webhook handlers: `pull_request_review`, `pull_request_review_comment`, `issue_comment`

## Non-goals for early tiers

- Full Origin UI parity inside Hub dashboard
- Mirroring GitHub Issues
- Perfect author impersonation without enterprise identity mapping

## Exit criteria (by tier)

- **A:** PR open/edit/close/reopen and head updates flow via webhooks on GitHub pairs; draft preserved.
- **B:** Comment posted on origin appears on replica (and reverse per policy) without loops.
- **C/D:** Documented authority model; merge/review behavior matches runbook; no silent force-merge.

## Related

- [`cache-resume-worker-affinity.md`](cache-resume-worker-affinity.md) — orthogonal scale work
- Code: `PullRequestSyncService`, `WebhookController`, `webhook-worker/src/index.ts`
