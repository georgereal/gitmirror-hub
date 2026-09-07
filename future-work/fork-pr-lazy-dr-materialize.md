# Fork PR lazy DR materialize

## Shipped (default on)

- Bulk / webhook fork PRs **cache tip objects** (`refs/pull/{n}/head` locally; optional `refs/gitmirror/fork-pr/{n}` on dest).
- **No** dest `fork-pr-*` branch and **no** dest GitHub PR until operator materializes.
- Stub `pr_mappings` row: `state=objects_cached`, `target_pr_number` null, `fork_pr_head=true`.
- API: `POST /api/v1/mappings/{id}/prs/{sourcePrNumber}/materialize-fork`
- Flags: `git-utility.git.pr-fork-lazy-materialize` (default true), `pr-fork-push-object-refs` (default true).

## Why

Dest-only `fork-pr-*` branches inflated branch counts and slowed PR prep. DR still needs tip **objects** while source is up; reviewable dest PRs can wait.

## Still to do

- UI: show `objects_cached` fork PRs and a Materialize action.
- Optional cleanup job for legacy `fork-pr-*` heads already on dest.
- Diff inspection: treat `objects_cached` as “cached for DR” not “pending mirror”.
