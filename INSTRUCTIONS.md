# GitMirror Hub - Operational Runbook & Instructions

This document provides complete instructions for setting up, running, configuring, testing, and operating the **GitMirror Hub** utility.

---

## 1. Prerequisites

Before running the application, ensure the following tools are installed:


| Component        | Minimum Version | Verified Version                  | Notes                                 |
| ---------------- | --------------- | --------------------------------- | ------------------------------------- |
| **Java JDK**     | 21+             | JDK 23 (Oracle / OpenJDK)         | Required for Spring Boot 4 & JGit     |
| **Apache Maven** | 3.8+            | Maven 3.9.9                       | Preferred for local `bootRun` / day-to-day iteration (`mvn -f backend/pom.xml`) |
| **Gradle**       | 8.10+ (Wrapper) | Gradle 8.10.2                     | Optional alternate; useful for wrapper-based / pod-style runs (`backend/gradlew`) |
| **Node.js**      | 18+             | Node.js v20.19.5                  | Frontend build & dev server           |
| **npm**          | 9+              | npm 10.8.2                        | Frontend package management           |
| **AMQP Broker**  | AMQP 0-9-1      | CloudAMQP (Free) / RabbitMQ 3.12+ | Message queue & DLQ orchestration     |


---



## 2. Cloud Queue & Local Broker Setup



### Option C: No broker (`none` messaging)

For first enterprise bring-up without sorting AMQP:

```bash
export GIT_MESSAGING_PROVIDER=none
# Optional: keep default pause-on-startup off for none (already defaulted when provider=none)
source env && mvn -f backend/pom.xml spring-boot:run
```

Sync Repo and HTTP webhooks still work (in-process). Edge Worker → inbound AMQP and multi-pod competing consumers require `rabbitmq` (default). The UI nav shows **Execution** instead of full broker Queue Manager.

### Messaging provider enums (`GIT_MESSAGING_PROVIDER`)

| Value | Status | Use when |
| :--- | :--- | :--- |
| **`rabbitmq`** | Implemented (default) | Durable AMQP / CloudAMQP / local RabbitMQ; multi-pod; DLQ |
| **`kafka`** | Reserved | Not implemented yet — startup fails with a pointer to `future-work/kafka-mirroring-partitions.md` |
| **`none`** | Implemented | Single-node / bring-up without a broker (in-process sync) |

Aliases accepted for convenience: `rabbit`, `amqp` → `rabbitmq`; legacy `inline` / `local` → `none`.

### Option A: Free CloudAMQP Setup (Recommended for Testing & Cloud Deployments)

CloudAMQP provides a 100% free forever tier ("Little Lemur") with 1M messages/month, 20 concurrent connections, and full RabbitMQ management capabilities.

1. Go to [https://www.cloudamqp.com/](https://www.cloudamqp.com/) and sign up for a free account.
2. Click **Create New Instance**.
3. Select the **Little Lemur (Free)** plan and choose your preferred cloud region (e.g., AWS us-east-1).
4. Once created, click on your instance to view its details.
5. Copy the **AMQP URL** (format: `amqps://username:password@hostname/vhost`).
6. Set the environment variable before starting the backend:

```bash
export SPRING_RABBITMQ_ADDRESSES="amqps://your-user:your-pass@your-subdomain.cloudamqp.com/your-vhost"
```



### Option B: Local RabbitMQ Instance

If you prefer running RabbitMQ locally:

- **Using Docker**:
  ```bash
  docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
  ```
- **Using Homebrew (macOS)**:
  ```bash
  brew install rabbitmq
  brew services start rabbitmq
  ```
- Default connection URL used by the app: `amqp://guest:guest@localhost:5672` (no environment variable needed).

---



## 3. Running the Application



### Step 1: Start the Spring Boot Backend

Open a terminal at the **repo root** (`gitUtility/`). The gitignored `env` file lives here (not under `backend/`).

```bash
# Optional: JDK 21+ on PATH (macOS example: export JAVA_HOME="$(/usr/libexec/java_home -v 21)")
# Required if not already in ./env: unique key used to encrypt PATs and App private keys at rest
# export GIT_UTILITY_ENCRYPTION_KEY="$(openssl rand -hex 32)"

# (Optional) Export CloudAMQP URL if using cloud broker:
# export SPRING_RABBITMQ_ADDRESSES="amqps://user:pass@YOUR-INSTANCE.cloudamqp.com/YOUR_VHOST"

# (Optional) Enable the H2 web console on /h2-console
# export GIT_H2_CONSOLE_ENABLED=true

# Prefer the local gitignored env file for day-to-day secrets, then run Maven from root
# (usually faster local iteration). Keep `backend/pom.xml` and `backend/build.gradle.kts` in sync.
source env && mvn -f backend/pom.xml spring-boot:run
```

For **two backends on one machine**, see [`INSTRUCTIONS-MULTI-POD.md`](INSTRUCTIONS-MULTI-POD.md) (`source env.pod-a` / `env.pod-b`).

**Gradle alternate** (wrapper; no global Gradle install — good for pod-style / reproducible runs):

```bash
# From repo root:
source env && ./backend/gradlew -p backend bootRun

# Or from backend/:
cd backend
source ../env && ./gradlew bootRun
```

- **Backend REST API Root**: `http://localhost:8080/api/v1` (or `http://localhost:8080/`)
  - **Repository Pairs**: `http://localhost:8080/api/v1/mappings`
  - **Recent Sync Jobs**: `http://localhost:8080/api/v1/jobs/recent`
  - **Dashboard Stats**: `http://localhost:8080/api/v1/jobs/stats`
  - **Queue & DLQ Status**: `http://localhost:8080/api/v1/queue/status` — Ready (pending) vs Unacked (in-flight) per lane, plus consumer thread snapshot (`consumers[]`)
  - **Cancel queued jobs**: `POST http://localhost:8080/api/v1/jobs/cancel-queued` (marks DB jobs cancelled; workers skip-ACK RabbitMQ leftovers)
  - **Simulation State**: `http://localhost:8080/api/v1/simulation/state`
- **H2 Database Web Console** (disabled unless `GIT_H2_CONSOLE_ENABLED=true`): `http://localhost:8080/h2-console`
  - JDBC URL: `jdbc:h2:file:./data/gitutility` (Persistent file-backed database)
  - User: `sa`
  - Password: *(leave blank)*
- **WebSocket Endpoint**: `http://localhost:8080/ws`

**Startup behavior (default):** On boot, `StartupJobRecoveryService` pauses both sync consumers (`git-utility.queue.pause-consumers-on-startup: true`) and marks stale `IN_PROGRESS` jobs as `INTERRUPTED`. Open **Queue Manager**, click **Resume Consumer**, then **Dispatch** or **Resume** the jobs you want to run — the AMQP backlog does not auto-drain after restart.

---



### Step 2: Start the React Frontend

Open a second terminal window:

```bash
cd frontend

# Install dependencies (first time only)
npm install

# Start Vite dev server
npm run dev
```

- **Frontend Dashboard**: Open your browser at `http://localhost:3000`

---



### Step 3: (Optional) Start & Deploy Serverless Webhook Worker (Cloudflare Workers)

To decouple ingestion and guarantee **100% webhook uptime with 0ms cold-start**:

```bash
cd webhook-worker

# Ensure Node 22+ is active
nvm use 22

# 1. Install dependencies
npm install

# 2. Authenticate terminal with Cloudflare (opens browser OAuth approval)
npx wrangler login

# 3. Check logged-in account
npx wrangler whoami

# 4. Local env file (gitignored). Fill in the CloudAMQP host, vhost, user, password, and HMAC.
cp .env.example .env

# 5. Deploy. This uploads .env to Cloudflare as encrypted secrets.
npm run deploy
```

- **Live Edge Webhook Endpoint**: `https://gitmirror-webhook-worker.<your-username>.workers.dev`
- **Live Health Check**: `curl https://gitmirror-webhook-worker.<your-username>.workers.dev/health`
- **Real-time Tail Logs**: `npx wrangler tail`

**Local preview** (`npm run dev` in `webhook-worker/`) is only your machine. Stop it with Ctrl+C. That does not stop a Worker already deployed on Cloudflare.

**Stop the deployed Rabbit worker, keep secrets:** set `ENABLED=false` in `webhook-worker/.env`, then `npm run deploy`. `GET /health` reports `enabled: false`. `POST` returns 503 and does not publish. Start again with `ENABLED=true` and `npm run deploy`.

**Take the public URL down** (secrets stay): set `workers_dev = false` in that `wrangler.toml` and `npm run deploy`. Set it back to `true` and deploy to open the URL.

**Delete the Worker** (secrets are removed; the next deploy uploads `.env` again):

```bash
cd webhook-worker
npx wrangler delete
```

### Kafka webhook worker (separate Cloudflare script)

[`webhook-worker-kafka/`](webhook-worker-kafka/README.md) is a second Worker. It produces normalized git events to a Kafka topic over the Confluent REST API. Deploy it only when `GIT_WEBHOOK_BUS_PROVIDER=kafka`. Its cluster and API key live in gitignored `webhook-worker-kafka/.env`, same as the Rabbit worker. Start, stop, and delete use `ENABLED` in that file, `workers_dev` in `wrangler.toml`, and `wrangler delete`. The script name is `gitmirror-webhook-worker-kafka`. The Confluent walkthrough is [`INSTRUCTIONS-KAFKA-WEBHOOK.md`](INSTRUCTIONS-KAFKA-WEBHOOK.md).

Both Workers accept only `push`, `create`, `delete`, `pull_request`, `release`, `status`, and `check_run`. After those events are added on the GitHub App, redeploy the Worker for the bus you use. An older bundle answers `200 ignored` and Hub never stores the delivery.

---



## 4. Failure Testing & Chaos Simulation Recipes

The system includes a built-in **Simulation Lab** to validate resilience without requiring live GitHub outages.

### Recipe 1: Simulating Sync System Downtime & Queue Buffering

- **Goal**: Verify that when the sync consumer worker is down, incoming GitHub webhooks do not get lost and buffer safely in the AMQP queue.
- **Steps**:
  1. Open the dashboard at `http://localhost:3000` and go to **Queues** (or **Observability** / **Simulation Lab**).
  2. Click **"Pause Consumer (Simulate Downtime)"**.
  3. Go to the **Simulation Lab** tab and use the **Synthetic Webhook Generator** to emit 3 test push events.
  4. Notice the **Webhook syncs** counter increase. The events remain safely buffered in `git.sync.incremental.queue`.
  5. Return to **Queues** and click **"Resume"**.
  6. Observe the incremental worker drain the backlog. Cancelled jobs (if any) are skipped and ACK'd without running Git.

### Stall triage via Internals

When a sync appears stuck (job stays `IN_PROGRESS`, LFS/PR stage hangs, or queues stop draining):

1. Open **Internals** at `http://localhost:3000/observability/internals` (defaults to **cluster** view via `GET /api/v1/runtime-metrics/cluster`).
2. Check **Pods live/known** and pick a pod — stale heartbeats mean that replica is down or partitioned.
3. Check **Rabbit lanes** on the active pod — `unacked` rising with listeners stopped means consumers are paused or the circuit breaker is open (pause/CB are cluster-wide).
4. Check **Executor pools** — high `queued` on LFS/PR pools with saturated `active` means that pool is the bottleneck (tune `GIT_LFS_*` / `GIT_PR_CREATE_CONCURRENCY` **per pod**).
5. Check **Heap** per pod — sustained >85% can stall JGit push/LFS transfers.
6. Check **Usage by job** when remaining quota drops or 429s appear — remaining is shared; rank overlapping jobs by REST + GraphQL + Git call counts, then open the heavy job’s execution log.
7. In-flight jobs show `workerInstanceId` on Queues; job-level detail remains on Observability / Queues.

---

## Multi-pod / Kubernetes deploy

> **Local two-instance runbook:** see [`INSTRUCTIONS-MULTI-POD.md`](INSTRUCTIONS-MULTI-POD.md) for running two backends on one machine (`SERVER_PORT` + `GIT_UTILITY_INSTANCE_ID`).

**Scale model:** see [`ARCHITECTURE.md`](ARCHITECTURE.md) §3.6.1. Local two-instance runs test **fleet** parallelism (two jobs / two pairs), not splitting one Sync Repo across JVMs. Git for a job stays on one `workerInstanceId`.

**Three planes:** HTTP (LB / ports) ≠ Rabbit (durable async jobs; Edge Worker → inbound unchanged) ≠ shared DB (pod tracking via `instance_heartbeats`, leases, `cluster_runtime`, job ledger). Rabbit does not manage pod lifecycle.

For `replicas > 1`, all Hub pods **complement each other** as competing Rabbit consumers (`GIT_MESSAGING_PROVIDER=rabbitmq`). Do not use `none` for multi-pod.

**Required shared dependencies**

| Dependency | Why |
| :--- | :--- |
| Shared DB (H2 `AUTO_SERVER` locally; **MongoDB** for real fleets) | Fleet heartbeats, pair leases, cluster pause/CB, job ledger |
| Shared RabbitMQ / CloudAMQP | Competing consumers on full / incremental / inbound queues |
| Shared bare-repo storage (`NAS_MOUNT`) or accept cold re-fetch | Local NVMe is not shared across pods |

### Storage providers (`GIT_PERSISTENCE_PROVIDER`)

Exactly one persistence store is active per Hub process — selected by env, enforced at boot, **no fallbacks**:

| Value | Store | Use when |
| :--- | :--- | :--- |
| **`h2`** (default) | File H2 + Spring Data JPA (`./data/gitutility`) | Dev and local multi-pod smoke (same cwd / data dir) |
| **`mongo`** | MongoDB + Spring Data MongoDB | Enterprise scale-out; requires a reachable `MONGODB_URI` at startup |

| Environment variable | Default | Applies to | Notes |
| :--- | :--- | :--- | :--- |
| `GIT_PERSISTENCE_PROVIDER` | `h2` | both | `h2` \| `mongo`; unknown values fail fast at startup |
| `MONGODB_URI` | `mongodb://localhost:27017/gitutility` | `mongo` | Bound as `spring.mongodb.uri` (Boot 4.x key — `spring.data.mongodb.*` is unbound since 4.0.0); Atlas (`mongodb+srv://…`) works; unreachable DB aborts startup (ping fail-fast) |
| `MONGODB_DATABASE` | `gitutility` | `mongo` | Bound as `spring.mongodb.database`, which **overrides** the database in the URI — set it only to switch databases |

**Descriptor:** `GET /api/v1/persistence` reports the active store (mirrors `GET /api/v1/messaging`).

**Store contract tests:** the persistence facades are proven by an abstract contract suite executed against both stores. The H2 side (`H2StoreContractTest`) runs on every `mvn test` (in-memory H2). The Mongo side (`MongoStoreContractTest`) runs **only** against a real MongoDB you provide — no Docker / Testcontainers:

```bash
# Point the mongo contract suite at a real MongoDB (replica set recommended —
# single node is fine — because @Transactional service methods use
# MongoTransactionManager). Unset ⇒ the suite skips and mvn test stays green.
export MONGO_CONTRACT_URI="mongodb://user:pass@host:27017/gitutility?replicaSet=rs0"
mvn -f backend/pom.xml test -Dtest=MongoStoreContractTest
# …or inline: mvn -f backend/pom.xml test -Dtest=MongoStoreContractTest -DMONGO_CONTRACT_URI="mongodb+srv://…"
```

`MongoContractDb` publishes the URI as `MONGODB_URI` + `spring.mongodb.uri` system properties so the test context binds it regardless of initializer ordering; `@EnabledIf(StoreContractEnvironment#mongoContractAvailable)` skips the suite when the variable is absent.

### Breaking changes when adopting this build (ObjectId-string ids)

1. **Wipe existing H2 data**: entity ids changed from BIGINT to 24-char ObjectId-hex strings. Existing `./data/` file databases (BIGINT ids) are incompatible — delete `./data/` (or the configured data dir) before first boot.
2. **Drain queues before deploying**: queued AMQP messages from a pre-upgrade build carry numeric ids and will dead-letter on the new build.

### Storage roadmap (cluster tables)

| Stage | Store | Use |
| :--- | :--- | :--- |
| **Now** | File H2 + `AUTO_SERVER=TRUE` (default) | Dev and local multi-pod smoke (same cwd / data dir) |
| **Enterprise** | **MongoDB** | Production fleets — every store facade, including `sync_jobs`, `pair_leases`, `cluster_runtime`, `instance_heartbeats` (atomic single-document ops; see `future-work/done/multi-store-persistence-h2-mongo.md`) |

Runbook migration between stores is not implemented (fresh start per store); see `future-work/done/multi-store-persistence-h2-mongo.md` for the shipped design.

**Identity:** set `GIT_UTILITY_INSTANCE_ID` or rely on `HOSTNAME` / `POD_NAME`.

**Safety:** `pair_leases` prevent two pods from syncing the same `mappingId`. Cancel/pause flags on `sync_jobs` are visible to the owning worker. Consumer pause and circuit-breaker OPEN are written to `cluster_runtime` and applied on every pod.

**Observability:** each pod heartbeats Micrometer snapshots (JVM heap/threads, executors, lanes, install-scoped REST usage) to `instance_heartbeats`. Internals and Observability poll `GET /api/v1/runtime-metrics/cluster` for live/known pod counts, per-pod thread usage, and fleet roll-up of external API usage by GitHub App install id. Local debug: `GET /api/v1/runtime-metrics`.

**Stuck lease recovery:** delete the row from `pair_leases` for that `mapping_id` if a pod died before TTL expiry (default 90s) and work is blocked.

**Affinity** (warm-cache stickiness) remains deferred — see `future-work/cache-resume-worker-affinity.md`.

---



### Recipe 2: Simulating SCM Outages, Retry Backoff & Dead Letter Queue (DLQ)

- **Goal**: Verify that when a destination repository is unreachable, the worker retries 3 times with exponential backoff and routes the failed task into the DLQ.
- **Steps**:
  1. Go to the **Simulation Lab** tab.
  2. Under *Fault Injection Scenarios*, check **"Simulate Destination Repo Down (500 Connection Refused)"**.
  3. Use the **Synthetic Webhook Generator** to emit a synthetic push event.
  4. Watch the job in the **Live Sync Table**:
    - Attempt 1 fails (1.5s backoff).
    - Attempt 2 fails (3.0s backoff).
    - Attempt 3 fails (6.0s backoff).
    - The event is moved to **Dead Letter Queue (DLQ)** with status `DEAD_LETTERED`.
  5. Click the **"Logs"** button next to the job to inspect the full failure diagnostic trace.
  6. Return to **Simulation Lab** and uncheck **"Simulate Destination Repo Down"** (simulating service recovery).
  7. Go to **Queue & DLQ** and click **"Redrive All DLQ"**.
  8. The poisoned message is automatically re-injected onto the matching execution lane and completes with status `SUCCESS`.

---



### Recipe 3: Testing Bidirectional Loop & Echo Prevention

- **Goal**: Verify that a webhook for a change the other repository already has does not trigger an infinite ping-pong loop.
- **Steps**:
  1. The engine still records the tip SHA, ref delete, or mirrored pull-request number in `echo_ledger` when it writes. That row is not what the inbound webhook uses to skip.
  2. `PairTipEchoService` asks the other repository what it advertises. A push is `LOOP_DETECTED_SYSTEM_ECHO` only when that peer already has the same tip. A delete is an echo only when the lookup succeeded and the ref is already gone. An unknown peer is not an echo, so the event is queued.
  3. A push whose sender is the mirror App bot (`{slug}[bot]`) is dropped as `MIRROR_APP_PUSH`. The same App check drops `pull_request` events the App itself opened.
  4. In the **Live Sync Table**, a push echo appears with status `SKIPPED` and skip reason `LOOP_DETECTED_SYSTEM_ECHO` or `MIRROR_APP_PUSH`. No outbound push is made.

### Automated behavioral scenarios

Cucumber runs inside the normal backend test task. Feature files are in `backend/src/test/resources/features/`:

| Feature | What it locks |
| :--- | :--- |
| Trunk divergence | Isolate, origin-wins, fail-job, overwrite, fast-forward, adopt-destination, unidirectional overwrite, conflict pull-request placement |
| Release and status echo | Delete, unpublish, and status echoes; one published release kept per tag |
| Pull request mirror rules | Fork heads stay off `main`; close versus open; closed pull requests stay out of the open mirror |
| Simulation lab | Pause and resume, both listeners, origin and destination faults, synthetic push |
| Queue acknowledgement | Cancelled and missing jobs are skipped so the broker can ack |
| Webhook echo | Hub-written mirror tips and deletes are skipped; new origin work and a new mirror `main` commit are queued; dependabot branches are ignored |

```bash
# JUnit and Cucumber together. The Gradle JVM must be 21 or 23; Java 25 cannot configure this wrapper.
./backend/gradlew -p backend test

# Cucumber scenarios only
./backend/gradlew -p backend test -PtestEngine=cucumber
```

Maven runs both as well: `mvn -f backend/pom.xml test`.

### Replica read-only ruleset (GitHub and GHES)

A branch ruleset makes the replica read-only for people. The mirror GitHub App is the only bypass actor, so Hub pushes still land. GitLab and Bitbucket pairs do not have this API.

The badge on **Settings → Replica rulesets** (`/settings/write-authority`) is **Missing ruleset** until GitHub has a ruleset with one of the names below. **Enforced** means `enforcement` is `active`. **Off** means the ruleset exists and enforcement is `disabled`.

#### Permissions

Repository rulesets need repository **Administration: Read and write** and **Contents: Read and write** on that installation.

An organization ruleset also needs organization **Administration: Read and write**. GitHub grants that only after an org owner accepts the new permission on the installation (Organization settings → GitHub Apps → the mirror App → Review request). The App registration page updating is not enough. **Refresh** on Replica rulesets drops cached installation tokens and reads `GET /app/installations` again.

An enterprise ruleset is GitHub.com only. Save an **Enterprise slug** on the App credential. The installation token must be allowed to call `/enterprises/{slug}/rulesets`. GHES has no enterprise ruleset API.

`actor_id` is the GitHub App id (`ScmCredential.appId`, the `id` from `GET /app`). It is not the installation id. Bypass type is `Integration`. `IntegrationInstallation` is not a valid bypass actor.

#### Set it from the Hub

On a pair, choose the writable primary and click **Lock replica**. Hub creates `gitmirror-replica-readonly` on the other repository if it is missing and sets enforcement to `active`. **Unlock replica** sets enforcement to `disabled` and does not delete the ruleset. **Swap primary** locks the old primary first, then unlocks the old replica.

When the source host is down, open **Settings → Disaster recovery** and use **Activate DR** on the provider-to-provider lane. Every pair that shares that source and destination switches together. Hub locks the old source at enterprise scope when the credential has an enterprise slug, otherwise at organization scope, and uses a repository ruleset only when those scopes cannot be written. The live replica unlocks immediately. Incrementals park (they do not go to the DLQ). A 2-minute heartbeat (`GIT_PEER_HEARTBEAT_SECONDS`) keeps probing the dark host and applies that same lock as soon as the host answers, then drains held events. **Fail back** needs both providers up. **Check providers** runs the probe immediately. Repositories under the lane are a detail list.

**Settings → Replica rulesets** covers organization and enterprise scope, and the same repository lock. Under each organization, set **Read-only** or **Write**, then **Apply**. Read-only creates the ruleset when it is missing and enforces it immediately. Write sets an existing ruleset to `disabled` and does not create one that is missing. A linked pair cannot be read-only on both sides; locking one side opens the other. Organization and enterprise rulesets start at this repository. Covering every repository asks you to type `ALL REPOS`.

| Scope | Name | API |
| --- | --- | --- |
| This repository | `gitmirror-replica-readonly` | `POST /repos/{owner}/{repo}/rulesets` |
| Organization, this repository | `gitmirror-readonly-{repo}` | `POST /orgs/{org}/rulesets` |
| Organization, all repositories | `gitmirror-org-readonly` | `POST /orgs/{org}/rulesets` |
| Enterprise, this repository | `gitmirror-readonly-{org}-{repo}` | `POST /enterprises/{slug}/rulesets` |
| Enterprise, all repositories | `gitmirror-enterprise-readonly` | `POST /enterprises/{slug}/rulesets` |

Turn an existing ruleset on or off with `PUT` to the same path plus `/{ruleset_id}` and `"enforcement": "active"` or `"disabled"`.

#### Set it in GitHub (manual fallback)

On the replica: **Settings → Rules → Rulesets → New branch ruleset**.

- Name `gitmirror-replica-readonly`, enforcement **Active**.
- Target branches: include all branches (`~ALL`).
- Rules: restrict creations, restrict updates (leave “Allow fork sync” off), restrict deletions.
- Bypass list: the mirror GitHub App only, bypass mode **Always**.

GHES uses the same screens on that appliance. For an organization or enterprise ruleset, create it under the org or enterprise rulesets page with the matching name from the table, and limit **Repository** (and **Organization**, for enterprise) to this repo unless you intend every repository.

#### JSON Hub sends

Repository ruleset. `actor_id` is the GitHub App id. The sample uses `5049517`. Substitute the App id for the credential that should still be allowed to push.

```json
{
  "name": "gitmirror-replica-readonly",
  "target": "branch",
  "enforcement": "active",
  "bypass_actors": [
    { "actor_id": 5049517, "actor_type": "Integration", "bypass_mode": "always" }
  ],
  "conditions": { "ref_name": { "include": ["~ALL"], "exclude": [] } },
  "rules": [
    { "type": "creation" },
    { "type": "update", "parameters": { "update_allows_fetch_and_merge": false } },
    { "type": "deletion" }
  ]
}
```

#### Test this on one public repository

Use this when the organization ruleset banner says enforcement waits for GitHub Team. A repository ruleset is a different object. GitHub enforces it on a **public** repository on the Free plan. Create it on the repository, under **Settings → Rules → Rulesets**, not under the organization's rulesets.

Pick a public test repository. From a shell, with a token that has repository **Administration: Read and write** on that repo:

```bash
curl -sS -X POST \
  -H "Accept: application/vnd.github+json" \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  https://api.github.com/repos/OWNER/REPO/rulesets \
  -d @- <<'EOF'
{
  "name": "gitmirror-replica-readonly",
  "target": "branch",
  "enforcement": "active",
  "bypass_actors": [
    { "actor_id": 5049517, "actor_type": "Integration", "bypass_mode": "always" }
  ],
  "conditions": { "ref_name": { "include": ["~ALL"], "exclude": [] } },
  "rules": [
    { "type": "creation" },
    { "type": "update", "parameters": { "update_allows_fetch_and_merge": false } },
    { "type": "deletion" }
  ]
}
EOF
```

Replace `OWNER/REPO`. Keep `actor_id` as the App id, not an installation id.

Then, as your user account (not the App), try `git push` and try to create a branch. GitHub should reject both. Clone and opening a pull request still work. Merging that pull request should fail.

The same push from the mirror App is allowed, because that App id is the bypass actor.

Turn the test off without deleting it:

```bash
curl -sS -X PUT \
  -H "Accept: application/vnd.github+json" \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  https://api.github.com/repos/OWNER/REPO/rulesets/RULESET_ID \
  -d '{"enforcement":"disabled"}'
```

`RULESET_ID` is the `id` in the create response. On a **private** repository in a Free organization, GitHub stores this ruleset and still does not enforce it until the organization is on GitHub Team.

Organization and enterprise bodies keep that bypass list and those three rules. Conditions add `repository_name`. `~ALL` covers every repository; a single short name is wrapped as `{ "include": ["repo"], "exclude": [], "protected": true }`. An enterprise ruleset also sets `organization_name` to `~ALL` or that org’s login. The all-repositories names are `gitmirror-org-readonly` and `gitmirror-enterprise-readonly`.

#### What an enforced ruleset blocks

The three rules apply to every branch (`~ALL`). They apply to people and to any token that is not the mirror App. A local `git commit` still succeeds; the push is what GitHub rejects.

Blocked:

- Pushing commits, including a commit made in the GitHub web editor.
- Creating a branch, deleting a branch, and force-pushing.
- Merging a pull request, because a merge moves the base branch.
- The pull request **Update branch** button (`update_allows_fetch_and_merge` is `false`).

Still allowed:

- Clone, fetch, and browse code, history, and existing branches.
- Open, edit, comment on, review, approve, and close a pull request. Merging it is the step that fails. A new pull request needs a head branch that already exists here, or a head branch on a fork, because creating a branch on this repository is blocked.
- Issues, discussions, wiki, release notes, repository settings, and Actions runs that do not push a branch.

Tags are not covered. `target` is `branch` only, so creating or moving a tag still works.

---



## 5. Connecting Real GitHub Repositories

To mirror real GitHub repositories:

> **GitHub App creation (recommended):** step-by-step App registration, permissions, install ID, and Hub credential mapping — see [`SCM_PROVIDER_SETUP.md`](SCM_PROVIDER_SETUP.md). That guide will expand for GitLab, Bitbucket, and other providers.

### Step 1: Create GitHub Fine-Grained Personal Access Tokens (PAT)

1. On GitHub, go to **Settings > Developer settings > Personal access tokens > Fine-grained tokens**.
2. Generate a token with:
  - **Repository access**: Select target repositories (e.g., `org/repo-a` and `backup/repo-b`).
  - **Permissions**: `Contents` (Read and Write).
3. Copy the generated token (`ghp_...`).



### Step 2: Add Mirror Pair in Dashboard

1. On the dashboard, click **"+ Add New Mirror Pair"**.
2. Fill in:
  - **Pair Identifier Name**: e.g., `production-core-service`
  - **Repository A Clone URL**: `https://github.com/my-org/core-service.git`
  - **Repository B Clone URL**: `https://github.com/my-backup-org/core-service.git`
  - **PAT Token for Repo A / B**: Paste your GitHub tokens.
  - **Sync Direction**: `Bidirectional`
  - **Trunk conflict policy**: `Isolate` (default). Use `Origin wins` only when B is a designated replica.
  - **Branch Filter Pattern**: `*` (or `main,develop,release/`*)
  - **Webhook Secret**: Choose a secret passphrase (e.g., `my-super-secret-key-123`).
3. Click **Save Mirror Pair**.

For **third-party public sources** (e.g. `https://github.com/microsoft/vscode`):
- Requires **Settings → Feature toggles → Public repositories** to be **On** (default on for local/test; turn off in production). When off, Public visibility is hidden in the pair UI and the API rejects public/anonymous pairing.
- **Visibility** (Public / Private / Auto) is the repo’s SCM privacy. **Access** is how the Hub authenticates (GitHub App/PAT by default). They are independent: a public repo browsed under your App still shows Visibility Public and Access via that App.
- **Access** on the pair form binds one Settings credential (shown as a label when you have a single App/PAT; a chooser only when multiple exist). **Anonymous** is an explicit opt-in (source anytime when public repos are enabled; destination only for B→A read). **Add / Re-check** and **Browse** verify that bound Access credential — they never strip App/PAT just because the repo is public, and they do not try every credential.
- If App/PAT check finds a public repo, the UI may note it is also anonymously readable; that hint is informational only — Access stays on the credential.
- **Create private destination** uses the destination Access credential. Owner defaults from the App account; name defaults to the source repo name and is editable.
- Bind Access via the Access row, credential modal, or **Browse Repos**. Provider Settings alone does not select which credential a pair uses.
- GitHub App **Subscribe to events → Push** is webhook delivery only. Git clone/push requires **Repository permissions → Contents: Read and write** (scroll above the event list), plus **Actions: Read and write** (cancel mirror-triggered workflow runs) and **Workflows: Read and write** if the source has `.github/workflows`. After changing permissions, GitHub asks you to review the installation on the destination repository.
- The destination still needs write credentials (GitHub App or PAT with Contents: Write) unless you are on B→A with Anonymous Access.
- You cannot attach a webhook to a repo you do not administer. Use **Sync Now**, initial bootstrap, or a synthetic event to run the first mirror.



### Step 3: Configure Webhook in GitHub App or Repository

Full App setup (create → permissions → install → Hub card): [`SCM_PROVIDER_SETUP.md`](SCM_PROVIDER_SETUP.md) §1.

#### Option A: In your GitHub App (*Developer settings > GitHub Apps > [Your App]*):

- **Webhook URL (per GitHub/GHES credential)**: `https://gitmirror-webhook-worker.<your-username>.workers.dev/webhook/github/credential/<id>` or `/webhook/ghes/credential/<id>` (copy from Settings). Direct backend: `http://localhost:8080/api/v1/webhooks/github/credential/<id>`.
- **Webhook secret**: The secret stored on that credential card. Worker `WEBHOOK_SECRETS_JSON` maps credential id → secret; `WEBHOOK_SECRET` remains a legacy fallback.
- **SSL verification**: `Enable SSL verification`
- **Active**: `[x] Active`
- **Permissions**: `Contents: Read & write`, `Metadata: Read-only`, `Pull requests: Read & write`, `Commit statuses: Read & write`, `Checks: Read & write`, **`Actions: Read & write`** (to cancel mirror-triggered workflow runs), and **`Workflows: Read & write`** if the source contains `.github/workflows` (otherwise GitHub returns `REJECTED_OTHER_REASON` on every branch). Organization **Administration: Read & write** is required for org-level read-only rulesets.
- **Events**: Push, Create, Delete, Pull request, Release, and Status. There is no **Check run** checkbox on the App. **Checks: Read and write** subscribes the App to `check_run` automatically. The full table is [`SCM_PROVIDER_SETUP.md`](SCM_PROVIDER_SETUP.md) §1.3. Accept the new Checks permission on each installation, then redeploy the Cloudflare Worker for this bus.

#### Preventing Actions from running on mirror sync (important)

GitMirror Hub pushes with a **GitHub App installation token or PAT**. Those **do trigger GitHub Actions**. Only the built-in Actions `GITHUB_TOKEN` (available inside a workflow job) is ignored by GitHub for recursion prevention — the Hub cannot use that token.

With **System Engine → Suppress mirror-triggered Actions** enabled (default):

1. Destination (and write-back) auth for GitHub/GHES **must be a GitHub App**, not a PAT. On save, the Hub resolves the App slug and stores the bot actor (e.g. `my-mirror-app[bot]`).
2. After each Hub push / PR create, the Hub lists workflow runs for that bot actor and **cancels** queued/in-progress runs.
3. For hard prevention on GitHub.com / Enterprise Cloud (runs never start), configure **Actions → Policies → Workflow execution protections** so the actor allow-list includes humans/roles needed for CI but **excludes** the mirror App. GHES may not have this feature yet — rely on the cancel sweeper there.
4. Optional per-workflow guard: `if: github.actor != 'my-mirror-app[bot]'`.

Do **not** rewrite commits with `[skip ci]` — that breaks SHA-preserving mirrors.



#### Option B: In an individual GitHub Repository (*Settings > Webhooks > Add webhook*):

- **Payload URL**:
  - **Serverless Cloudflare Worker URL (Recommended)**: `https://gitmirror-webhook-worker.<your-username>.workers.dev/webhook/github/<mappingId>` (or generic `/webhook/github`)
  - **Direct Backend URL**: `https://<your-server-domain>/api/v1/webhooks/github/<mappingId>`
- **Content type**: `application/json`
- **Secret**: The secret passphrase configured in `WEBHOOK_SECRET` / Step 2.
- **SSL verification**: `Enable SSL verification`
- **Which events would you like to trigger this webhook?**: send the individual events, not “just the push event”: Push, Create, Delete, Pull request, Release, Status, and **Check runs**. On a repository webhook that last box is labeled **Check runs**. A GitHub App has no such box; Checks write subscribes it. Same set as [`SCM_PROVIDER_SETUP.md`](SCM_PROVIDER_SETUP.md) §1.3.
- Click **Add webhook**.

---



## 6. Disaster recovery

DR is a provider-to-provider lane, not a switch on each repository. Open **Settings → Disaster recovery** (`/settings/dr`).

Each card is one source host and one destination host. Every pair on those two hosts moves together. The boxes show whether each provider is up. The arrow aimed at a down provider turns red and ends in a cross. **Ruleset updates** expands enterprises, then the organizations used by those pairs, then repositories.

**Metadata sync** (`/settings/metadata`) is separate. It turns pull requests, releases and assets, CI checks, and Git LFS on or off for every pair. Git ref push stays on. The GitHub App events for those switches are in [`SCM_PROVIDER_SETUP.md`](SCM_PROVIDER_SETUP.md) §1.3.

### When the source goes down

1. Confirm the down provider on the lane (red cross on that box, red line toward it). **Check providers** probes immediately. The background heartbeat is `GIT_PEER_HEARTBEAT_SECONDS` (default 120).
2. Click **Activate DR** on that lane. Hub unlocks the live replica now, even if the old source does not answer, and points incremental sync back toward the demoted source.
3. The read-only lock is attempted in this order: enterprise ruleset (GitHub.com credential with an enterprise slug), then each organization, then a repository ruleset if the wider scope cannot be written. While the demoted host is down the lock stays pending. The heartbeat applies it as soon as that host answers.
4. Incremental git and metadata events park in `failover_parked_event`. They are not sent to the DLQ for connection refused, timeout, or HTTP 502/503/504. Processing stays paused until both providers are up and the lock is applied. Then Hub drains the parked events. This is not a full `*` mirror.
5. Point CI remotes at the DR repositories only after the lane shows the replica writable. People should not push to the old source once its ruleset is applied.

### Fail back

**Fail back** stays disabled until both providers are up. It locks the side that was writable during DR, opens the original source, restores the previous sync direction, and drains anything still parked. Do not fail back from a single repository page. The pair page only links to this lane.

GitLab and Bitbucket lanes still park events. They have no ruleset API, so the read-only lock is GitHub and GHES only.

---



## 7. Enterprise Storage Tiering & Rate Limiting Configuration

To configure disk quotas, NAS mounts, rate-limiting, and circuit breakers for high-volume enterprise deployments:

```bash
# Storage Tiering Configuration
export GIT_STORAGE_LOCAL_DIR="/var/data/git-mirrors"
export GIT_STORAGE_NAS_DIR="/mnt/nas/git-mirrors"
export GIT_STORAGE_MAX_DISK_QUOTA_MB=102400   # 100 GB Local Quota
export GIT_STORAGE_MAX_CACHED_REPOS=2000
export GIT_STORAGE_RETENTION_HOURS=72

# Concurrency & Provider Rate Limiting Shield
export GIT_THROTTLE_MAX_CONCURRENT_PUSHES=10 # Max simultaneous Git push operations
export GIT_METADATA_SYNC_INTERVAL_SEC=30     # Metadata sync window per pair

# Circuit Breaker Protection
export GIT_CIRCUIT_BREAKER_THRESHOLD=5       # Auto-pauses consumer after 5 consecutive errors
export GIT_CIRCUIT_BREAKER_RESET_SEC=30      # Reset health probe interval

# Local mirror workspace (avoid /tmp for vscode-scale first bootstrap — OS may evict it)
export GIT_WORKSPACE_DIR="$HOME/git-utility-mirrors"

# Large-repo Git transport (first fat pack of microsoft/vscode can run many minutes)
export GIT_HTTP_TIMEOUT_SECONDS=600          # JGit fetch/push HTTP timeout
export GIT_PUSH_BATCH_SIZE=8                 # Remaining refs per push after default branch
export GIT_PUSH_BATCH_RETRIES=3              # Inner retries on connection reset / 502–504
export GIT_PUSH_BULK_BOOTSTRAP=true          # Single-connection mirror-style push when the destination is blank (first sync)
export GIT_HTTP_POST_BUFFER_BYTES=524288000  # 500 MiB http.postBuffer

# Git LFS parallel sync
export GIT_LFS_BATCH_SIZE=50
export GIT_LFS_DISCOVERY_THREADS=4           # Max in-pod parallel tree walks (extra tips wait for a free thread)
export GIT_LFS_TRANSFER_CONCURRENCY=4        # Max in-pod concurrent LFS blob uploads/downloads

# GitHub GraphQL fast path (PR pages, mirror snapshot, releases — REST fallback when disabled)
export GITHUB_GRAPHQL_ENABLED=true

# PR mirror concurrency
export GIT_PR_CREATE_CONCURRENCY=6
export GIT_PR_FORK_FETCH_BATCH_SIZE=32
export GIT_PR_LIST_PAGE_SIZE=100

# Release & CI metadata mirror
export GIT_RELEASE_PAGE_SIZE=50              # Releases per GraphQL/REST page when listing both sides
export GIT_RELEASE_CONCURRENCY=4             # Parallel per-release mirror tasks (create/update/asset streaming)
export GIT_CI_CHECK_TIP_LIMIT=8              # Recent tip commits backfilled with CI check runs / statuses

# Agentic / ephemeral webhook gate (live incremental only; Smart full sync still tip-probes all refs)
# export GIT_SYNC_EPHEMERAL_PREFIXES="agents/,agent/,dependabot/,renovate/,snyk-bot/,greenkeeper/,fork-pr-,sync-conflict/"
export GIT_SYNC_INCREMENTAL_COALESCE=true
export GIT_SYNC_INCREMENTAL_COALESCE_MS=45000

# Queue startup & orphan job recovery
export GIT_QUEUE_PAUSE_ON_STARTUP=true       # Pause consumers on boot; manual dispatch from Queue Manager
export GIT_QUEUE_ORPHAN_GRACE_SEC=120        # Grace before marking IN_PROGRESS jobs INTERRUPTED
```

**Laptop vs always-on host:** Day-2 webhook syncs of a few commits are fine on a laptop. The **first bootstrap** of a vscode-scale repository still transfers one large Git pack for `main`. Use a machine that will not sleep or drop the network during that first pack. After `completed_push_refs` records a **successful** `main` (not a GitHub `REJECTED_*`), a connection-reset retry continues with remaining branches/tags instead of restarting the whole mirror. Destination rejects abort the rest of the full-mirror instead of looping 600+ fat packs. Do not keep large pair workspaces under `/tmp`. If a previous run recorded rejected refs as completed, retry after this build: resume now trusts destination SHAs for heads, not a poisoned ledger.

### Pausing, resuming & skipping stages
Long full mirrors and LFS transfers support cooperative control from **Queue Manager** or the job log drawer:
1. **Pause** (`POST /api/v1/jobs/:id/pause`) — stops after the current JGit phase; preserves `pipeline_json` and LFS OIDs on the pair.
2. **Resume** (`POST /api/v1/jobs/:id/resume`) — continues from the saved pipeline cursor (or re-queues an `INTERRUPTED` job).
3. **Skip stage** (`POST /api/v1/jobs/:id/skip-stage`) — advances the resume cursor past a stuck outer stage (e.g. skip remaining LFS when blobs are already on disk).
4. After server restart, filter jobs by `INTERRUPTED` or `PAUSED` and use **Resume** — do not rely on automatic queue drain while consumers are paused.

---



## 8. Live Sync Diff & Metadata Drill-Down Inspection Matrix

To inspect the real-time health and replication fidelity of any configured repository pair:

> **Note:** *Refresh Diff* (and the sync engine) hold the shared per-mapping bare-repo lock (`RepoDirLockService`), so a refresh started while a sync job is running on the same pair will visibly wait instead of racing it. If the job log ever shows `local ref update failed for '...' (LOCK_FAILURE)`, that ref's tip stayed stale — re-run the sync; do not trust the branch table for that branch until a sync succeeds.

1. Click on any repository card on the **Repositories** page (`/repos`).
2. Navigate across the 4 specialized tabs:
  - **Branches & Commits**: Real-time ahead/behind commit calculation and 1-click branch sync triggers.
  - **Pull Requests Mirror**: Cross-repository PR ID mappings and synchronizations.
  - **Git Metadata, LFS & Releases**:
    - **Tags & Notes**: Searchable catalog of lightweight and annotated tags, tagger details, messages, and Git Notes.
    - **Releases**: Detailed GitHub/GitLab release changelogs, draft/pre-release badges, downloadable binary assets, and a per-release mirror badge (`Mirrored` / `Pending mirror` / `Not supported on destination`) computed against the real destination listing. **Sync Releases & Assets** launches a visible job (source → destination create/update + binary asset streaming) — track it in Queue Manager and the audit log.
    - **Git LFS**: OID hashes, pointer byte sizes, and tracking branch names for mirrored binary assets.
    - **CI Checks**: Live CI/CD check run status, conclusions (success/failure), elapsed execution times, and pipeline links. **Sync CI Checks** backfills check runs (GitHub/GHES) or build statuses (GitLab/Bitbucket) onto the most recent mirrored tips as a visible job.
  - **Storage & Settings**: Custom storage tier classification (`HOT_PERSISTENT`, `AUTO_LRU`, `EPHEMERAL_STREAM`, `NAS_MOUNT`), sync rules, and branch filters.

