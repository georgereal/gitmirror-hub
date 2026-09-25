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
| [kafka-incremental-upstream-sync.md](kafka-incremental-upstream-sync.md) | Core shipped | Incremental lane on Kafka or Rabbit (`GIT_WEBHOOK_BUS_PROVIDER`). Cloudflare publisher in `webhook-worker-kafka/`. Full mirror stays on `GIT_MESSAGING_PROVIDER` |
| [kafka-mirroring-partitions.md](kafka-mirroring-partitions.md) | Backlog (design only) | Later cutover if full **and** incremental both move to Kafka. Not this slice |
| [ephemeral-agentic-ref-webhooks.md](ephemeral-agentic-ref-webhooks.md) | Partial (core shipped) | Pair UI for prefixes; scheduled Smart sync; discard filter chips |
| [fork-pr-lazy-dr-materialize.md](fork-pr-lazy-dr-materialize.md) | Partial (core shipped) | UI materialize action; legacy fork-pr cleanup; diff status for objects_cached |
| [fetch-ref-verification-and-repo-dir-mutex.md](fetch-ref-verification-and-repo-dir-mutex.md) | Partial (core shipped) | Surface silent per-ref fetch failures + shared bare-repo lock; PR-service fetch sites, bounded retry, UI staleness indicator |

## Done

| Doc | Notes |
| :--- | :--- |
| [done/micrometer-internals-observability.md](done/micrometer-internals-observability.md) | Internals UI + Micrometer v1. Optional: Prometheus/Grafana |
| [done/bulk-repo-migration.md](done/bulk-repo-migration.md) | Bulk tab, `POST /api/v1/mappings/bulk`, submission records + cancel, parallelism hint |
| [done/multi-store-persistence-h2-mongo.md](done/multi-store-persistence-h2-mongo.md) | H2 \| Mongo provider, string ids, store facades, contract tests, Settings store indicator |
| [done/readonly-replica-rulesets.md](done/readonly-replica-rulesets.md) | Per-repo ruleset lock/unlock/swap, shared `echo_ledger`, App-sender skip |
| [done/org-enterprise-ruleset-ui.md](done/org-enterprise-ruleset-ui.md) | Write authority screen: linked or individual write/read at repo, org, or enterprise scope |

## Conventions

- Keep pending plans concrete: phases, exit criteria, touchpoints, non-goals.
- Tag shipped slices with **Shipped** so the remaining work is obvious.
- Do **not** treat this folder as living architecture truth — update [`ARCHITECTURE.md`](../ARCHITECTURE.md) / [`REPO_MAP.md`](../REPO_MAP.md) when work ships (per project doc sync rules).
- When a plan is fully implemented, move it to `done/` (or delete after folding into ARCHITECTURE).
