# Future work

Deferred design plans. These are **not** delivery schedules. Shipped plans live in [`done/`](done/).

## Pending

| Doc | Status | Still to do |
| :--- | :--- | :--- |
| [cache-resume-worker-affinity.md](cache-resume-worker-affinity.md) | Partial | Distributed pair lease, fetch seal, LRU skip in-flight jobs, cold R2 snapshots, worker shard affinity |
| [pr-sync-parity.md](pr-sync-parity.md) | Partial (shell shipped) | Tiers A–E: draft/reopen/synchronize, PR webhooks, comments, reviews, merge, labels |
| [fanout-concurrency.md](fanout-concurrency.md) | Partial | Parallel Git ref-push batches after trunk; scatter-gather ACK. LFS + PR create pools already shipped |
| [scm-credential-vault-and-hub-hmac.md](scm-credential-vault-and-hub-hmac.md) | Parked | Vault refs for secret bytes; Hub HMAC on inbound consume |
| [multi-config-webhook-ingest.md](multi-config-webhook-ingest.md) | Parked | One Worker + one inbound queue; identity on envelope; no Kafka / per-card Worker |
| [ephemeral-agentic-ref-webhooks.md](ephemeral-agentic-ref-webhooks.md) | Partial (core shipped) | Pair UI for prefixes; scheduled Smart sync; discard filter chips |
| [fork-pr-lazy-dr-materialize.md](fork-pr-lazy-dr-materialize.md) | Partial (core shipped) | UI materialize action; legacy fork-pr cleanup; diff status for objects_cached |

## Done

| Doc | Notes |
| :--- | :--- |
| [done/micrometer-internals-observability.md](done/micrometer-internals-observability.md) | Internals UI + Micrometer v1. Optional: Prometheus/Grafana |

## Conventions

- Keep pending plans concrete: phases, exit criteria, touchpoints, non-goals.
- Tag shipped slices with **Shipped** so the remaining work is obvious.
- Do **not** treat this folder as living architecture truth — update [`ARCHITECTURE.md`](../ARCHITECTURE.md) / [`REPO_MAP.md`](../REPO_MAP.md) when work ships (per project doc sync rules).
- When a plan is fully implemented, move it to `done/` (or delete after folding into ARCHITECTURE).
