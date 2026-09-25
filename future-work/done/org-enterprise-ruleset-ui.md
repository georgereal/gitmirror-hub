# Org and enterprise write authority

> **Status:** Implemented. Settings → Write authority (`/settings/write-authority`).  
> **Depends on:** the per-repo slice in [`readonly-replica-rulesets.md`](readonly-replica-rulesets.md).  
> **Screen:** a new settings page, not another block on the pair page.

The repository ruleset (`POST /repos/{owner}/{repo}/rulesets`) locks one repo. An org lock is `POST /orgs/{org}/rulesets`. An enterprise lock is `POST /enterprises/{enterprise}/rulesets` on GitHub Enterprise Cloud. This slice adds one screen that can do all three, using the GitHub Apps and installations already stored on SCM credentials.

## Why a separate screen

Pair settings already have Lock replica / Unlock replica / Swap primary for the two repos in that pair. Org and enterprise rulesets cover many repos, and the actor that may call them is an App installation (an org) or an enterprise slug, not a pair. Putting that on the pair page hides it and invites locking the wrong scope by accident.

The new page lists what those Apps can actually manage. A pair is one way to use the page. An org or an enterprise can also be set on its own.

## What the screen shows

Route: `/settings/write-authority`. Nav item in [`SettingsLayout`](../../frontend/src/pages/settings/SettingsLayout.tsx), next to SCM Providers & Auth: **Write authority**. Description: which side of a mirror may accept pushes.

The page has two modes, switched at the top.

### Linked pair

Pick an existing mirror pair. The screen draws the two sides as one control:

```
[ Side A · acme/api · writable ] —— linked —— [ Side B · acme-dr/api · read-only ]
```

The link is on by default. Moving one side to read-only moves the other to writable. The server locks the side that is becoming read-only **before** it unlocks the side that is becoming writable, same order as swap today. A third state, both writable, is the unlock.

Each side has a scope chip. The chip is only enabled when that side's credential can perform it:

| Chip | Enabled when | API |
|---|---|---|
| Repo | GitHub.com or GHES credential on that side | `POST /repos/{owner}/{repo}/rulesets` (already shipped) |
| Org | That side's installation `accountType` is `Organization` | `POST /orgs/{org}/rulesets` |
| Enterprise | An enterprise slug is saved on that App, and the host is GitHub.com | `POST /enterprises/{slug}/rulesets` |

A user-account installation does not get the Org chip. GHES does not get the Enterprise chip: an appliance has no `/enterprises/{slug}` cloud API. On GHES the top of the tree is the org ruleset.

Default target for Org and Enterprise is **this repo only** (`repository_name.include` is that repo, not `~ALL`). A second choice, **all repos in this org** or **all repos in this enterprise**, requires a typed confirm. That choice is how one switch covers a fleet. It is never the default.

Bypass actor stays the App id (`actor_type: Integration`), from `ScmCredential.appId` on that side. Not the installation id.

### Individual

Turn the link off. Each side is its own Write / Read-only pill. Both read-only is allowed (freeze). Both writable is allowed. Changing one side does not touch the other.

The same screen, without a pair selected, lists the orgs from the App installations operators already selected (`accountLogin` + `accountType` on [`ScmCredential`](../../backend/src/main/java/com/gitutility/model/entity/ScmCredential.java) / [`ScmInstallationOption`](../../backend/src/main/java/com/gitutility/model/dto/ScmInstallationOption.java)). Each org row is Write or Read-only. If an enterprise slug is saved on that App, one enterprise row sits above its orgs. Those rows do not need a mirror pair. They create the org or enterprise ruleset directly.

A repo that is also in a pair still shows a line on the pair card: "Read-only because of the org ruleset on acme", so the pair lock and the org lock are not mistaken for each other.

## Where the enterprise slug comes from

Installations are org or user. GitHub does not put an enterprise slug on the installation. Provider settings, on the GitHub App card in [`ProvidersAuthPage`](../../frontend/src/pages/settings/ProvidersAuthPage.tsx), gains an optional **Enterprise slug**. Empty means the Enterprise chip and the enterprise row stay disabled. GHES App cards do not show the field.

## Ruleset shape

Same rules as the repo slice: name, all branches (`ref_name.include = ["~ALL"]`), `creation`, `update` with `update_allows_fetch_and_merge: false`, `deletion`, one bypass actor.

Names:

- Repo: `gitmirror-replica-readonly` (unchanged).
- Org, this repo: `gitmirror-readonly-{repo}`.
- Org, all repos: `gitmirror-org-readonly`.
- Enterprise, this repo: `gitmirror-readonly-{org}-{repo}`.
- Enterprise, all repos: `gitmirror-enterprise-readonly`.

On and off is `PUT` with `enforcement` `active` or `disabled`. `evaluate` is not used. Lookup is `GET` on that collection and match the name, then store the id.

Org conditions include `repository_name`. Enterprise conditions include the org and the repo unless the operator confirmed all repos.

## Permissions

The App needs more than Contents write.

- Repo: repository Administration write. Already required to create repos.
- Org: **organization** Administration write on that installation. Repository Administration does not grant this. If `GET /app/installations` omits organization `administration: write`, the Org chip is disabled and the row says so.
- Enterprise: the token must be allowed to call `/enterprises/{slug}/rulesets`. A normal repo installation token usually cannot. If the probe returns 403, the Enterprise chip stays disabled with that reason. Do not create a repo ruleset instead and call it an enterprise lock.

## State

One `replicaRulesetId` on the pair is not enough once each side can have its own scope. Store per side on the pair: scope (`repo` | `org` | `enterprise`), target (`this_repo` | `all_repos`), ruleset id, enforcement, and whether the two sides are linked. Org-only and enterprise-only rows that are not a pair get their own record keyed by credential id + org login, or credential id + enterprise slug.

## API

`POST /api/v1/write-authority` with the screen's selection:

- `link`: `linked` or `independent`
- `pairId` when a pair is selected
- `sides`: `{ side, access: write|readonly, scope, target }`
- or `org` / `enterprise` when no pair is selected

Linked changes lock the side moving to read-only first, and only then open the other side. A failed lock does not unlock anything.

`GET /api/v1/write-authority` returns the Apps, installations, enterprise slug, which chips are enabled, and current enforcement, so the screen does not guess from the pair form.

## Out of scope

- Tag rulesets.
- GitLab and Bitbucket.
- GHES enterprise cloud API.
- Applying an enterprise ruleset to every org when the operator only confirmed one repo.
- Replacing the divergence backstop in `GitSyncEngine`. A ruleset stops the push. Isolation still runs when the ruleset is off.

## Exit

- The new settings page can link a pair so one side is writable and the other is read-only, at repo, org, or enterprise scope when that scope is actually available.
- Unlinking the pair sets each side on its own, including both read-only.
- An org installation can be set read-only or writable without opening a pair.
- An enterprise row appears only after a slug is saved, and only if the API probe succeeds.
- The mirror App remains the only bypass actor, by App id.
