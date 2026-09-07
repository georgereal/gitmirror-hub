# Ephemeral / agentic ref webhook policy

## Shipped

- [`RefInterestPolicy`](../backend/src/main/java/com/gitutility/service/RefInterestPolicy.java): ephemeral prefix denylist, `branchPattern` allowlist, non-trunk coalesce.
- Live push/PR webhooks skip `EPHEMERAL_REF_IGNORED` / `BRANCH_PATTERN_IGNORED` / `INCREMENTAL_COALESCED`.
- Smart full sync tip-probe unchanged (DR catch-up for agent churn).
- Documented as architectural tenet + §3.9 in [`ARCHITECTURE.md`](../ARCHITECTURE.md).

## Config

- `git-utility.sync.ephemeral-branch-prefixes`
- `git-utility.sync.incremental-coalesce-enabled`
- `git-utility.sync.incremental-coalesce-window-ms`

## Still to do

- Pair-level override UI for ephemeral prefixes.
- Optional scheduled Smart sync so deferred agent tips land without an operator click.
- Discarded-webhooks filter chip for the new reason codes in the frontend.
