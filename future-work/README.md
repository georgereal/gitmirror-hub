# Future work

Deferred design plans. These are **not** delivery schedules. Shipped plans live in [`done/`](done/).

## Pending

| Doc | Status | Still to do |
| :--- | :--- | :--- |
| [cache-resume-worker-affinity.md](cache-resume-worker-affinity.md) | Partial (resume + DB lease shipped) | Corrupt-repo ledger clear, fetch seal, LRU skip in-flight jobs, cold R2 snapshots, worker shard affinity, ops metrics |
| [pr-sync-parity.md](pr-sync-parity.md) | Partial (shell + PR webhooks shipped) | Comments, reviews, merge, labels/assignees/milestone, draft-on-create, reopen/synchronize |
| [fanout-concurrency.md](fanout-concurrency.md) | Partial (parallel push waves shipped) | Scatter-gather fan-in ACK across git+LFS+PR+release; high-load integration tests |
| [scm-credential-vault-and-hub-hmac.md](scm-credential-vault-and-hub-hmac.md) | Parked | Vault refs for secret bytes; Hub HMAC on inbound consume |
| [multi-config-webhook-ingest.md](multi-config-webhook-ingest.md) | Parked | One Worker + one inbound queue; identity on envelope; no Kafka / per-card Worker |
| [kafka-mirroring-partitions.md](kafka-mirroring-partitions.md) | Backlog (design only) | Kafka provider not implemented; this is the cutover design if ever adopted |
| [ephemeral-agentic-ref-webhooks.md](ephemeral-agentic-ref-webhooks.md) | Partial (core shipped) | Pair UI for prefixes; scheduled Smart sync; discard filter chips |
| [fork-pr-lazy-dr-materialize.md](fork-pr-lazy-dr-materialize.md) | Partial (core shipped) | UI materialize action; legacy fork-pr cleanup; diff status for objects_cached |
| [fetch-ref-verification-and-repo-dir-mutex.md](fetch-ref-verification-and-repo-dir-mutex.md) | Partial (core shipped) | Surface silent per-ref fetch failures + shared bare-repo lock; PR-service fetch sites, bounded retry, UI staleness indicator |
| [bulk-repo-migration.md](bulk-repo-migration.md) | Reviewed (decisions resolved) | Bulk tab + multi-select picker, `POST /api/v1/mappings/bulk`, `repositoryExists`/`hasCommits` adapters, at-job-start destination creation with fail-fast, submission records + bulk cancel |

## Done

| Doc | Notes |
| :--- | :--- |
| [done/micrometer-internals-observability.md](done/micrometer-internals-observability.md) | Internals UI + Micrometer v1. Optional: Prometheus/Grafana |

## Conventions

- Keep pending plans concrete: phases, exit criteria, touchpoints, non-goals.
- Tag shipped slices with **Shipped** so the remaining work is obvious.
- Do **not** treat this folder as living architecture truth — update [`ARCHITECTURE.md`](../ARCHITECTURE.md) / [`REPO_MAP.md`](../REPO_MAP.md) when work ships (per project doc sync rules).
- When a plan is fully implemented, move it to `done/` (or delete after folding into ARCHITECTURE).
