# GitMirror Hub - Operational Runbook & Instructions

This document provides complete instructions for setting up, running, configuring, testing, and operating the **GitMirror Hub** utility.

---

## 1. Prerequisites

Before running the application, ensure the following tools are installed:


| Component        | Minimum Version | Verified Version                  | Notes                                 |
| ---------------- | --------------- | --------------------------------- | ------------------------------------- |
| **Java JDK**     | 21+             | JDK 23 (Oracle / OpenJDK)         | Required for Spring Boot 3 & JGit     |
| **Apache Maven** | 3.8+            | Maven 3.9.9                       | Backend build & dependency resolution |
| **Node.js**      | 18+             | Node.js v20.19.5                  | Frontend build & dev server           |
| **npm**          | 9+              | npm 10.8.2                        | Frontend package management           |
| **AMQP Broker**  | AMQP 0-9-1      | CloudAMQP (Free) / RabbitMQ 3.12+ | Message queue & DLQ orchestration     |


---



## 2. Cloud Queue & Local Broker Setup



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

Open a terminal at the **repo root** (same pattern as multi-pod):

```bash

# Optional: JDK 21+ on PATH (macOS example: export JAVA_HOME="$(/usr/libexec/java_home -v 21)")

# Load local secrets / paths (gitignored `env` / `env.pod-*` — copy from your workstation)
# export SPRING_RABBITMQ_ADDRESSES=...   # or put them in `env`

# Required: unique key used to encrypt PATs and App private keys at rest
export GIT_UTILITY_ENCRYPTION_KEY="$(openssl rand -hex 32)"

# (Optional) Export CloudAMQP URL if using cloud broker:
# export SPRING_RABBITMQ_ADDRESSES="amqps://user:pass@YOUR-INSTANCE.cloudamqp.com/YOUR_VHOST"

# (Optional) Enable the H2 web console on /h2-console
# export GIT_H2_CONSOLE_ENABLED=true

# Prefer a local gitignored env file, then:
#   source env && mvn -f backend/pom.xml spring-boot:run
source env && mvn -f backend/pom.xml spring-boot:run
```

For **two backends on one machine**, see [`INSTRUCTIONS-MULTI-POD.md`](INSTRUCTIONS-MULTI-POD.md) (`source env.pod-a` / `env.pod-b`).

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

# 4. Set encrypted Cloudflare secrets (CloudAMQP & HMAC Webhook Secret)
npx wrangler secret put RABBITMQ_USER        # CloudAMQP / RabbitMQ username
npx wrangler secret put RABBITMQ_PASSWORD    # CloudAMQP / RabbitMQ password
npx wrangler secret put WEBHOOK_SECRET       # Legacy/global GitHub HMAC (optional if using the JSON map)
npx wrangler secret put WEBHOOK_SECRETS_JSON # Optional JSON map: {"12":"secret-for-credential-12"}
npx wrangler secret put BITBUCKET_WEBHOOK_SECRET

# 5. Deploy to Cloudflare's global edge network
npm run deploy
```

- **Live Edge Webhook Endpoint**: `https://gitmirror-webhook-worker.<your-username>.workers.dev`
- **Live Health Check**: `curl https://gitmirror-webhook-worker.<your-username>.workers.dev/health`
- **Real-time Tail Logs**: `npx wrangler tail`

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
6. In-flight jobs show `workerInstanceId` on Queues; job-level detail remains on Observability / Queues.

---

## Multi-pod / Kubernetes deploy (feature/multi-pod-cluster)

> **Local two-instance runbook:** see [`INSTRUCTIONS-MULTI-POD.md`](INSTRUCTIONS-MULTI-POD.md) for running two backends on one machine (`SERVER_PORT` + `GIT_UTILITY_INSTANCE_ID`).

**Scale model:** see [`ARCHITECTURE.md`](ARCHITECTURE.md) §3.6.1. Local two-instance runs test **fleet** parallelism (two jobs / two pairs), not splitting one Sync Repo across JVMs. Git for a job stays on one `workerInstanceId`.

For `replicas > 1`, all Hub pods **complement each other** as competing Rabbit consumers.

**Required shared dependencies**

| Dependency | Why |
| :--- | :--- |
| PostgreSQL (not file H2) | Pair leases, heartbeats, cluster pause/CB, job control flags |
| Shared RabbitMQ / CloudAMQP | Competing consumers on full / incremental / inbound queues |
| Shared bare-repo storage (`NAS_MOUNT`) or accept cold re-fetch | Local NVMe is not shared across pods |

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

- **Goal**: Verify that automated pushes by this utility do not trigger an infinite ping-pong loop between bidirectional repositories.
- **Steps**:
  1. When a push event is processed for Repo A, the JGit engine pushes commit `X` to Repo B and records `(Repo B, Commit X)` in the `DedupLedgerService`.
  2. When GitHub fires the subsequent push webhook for Repo B containing commit `X`, the ingestion layer intercepts it.
  3. In the **Live Sync Table**, the event appears with status `SKIPPED` and skip reason `LOOP_DETECTED_SYSTEM_ECHO`. No outbound push is made, terminating the cycle.

---



## 5. Connecting Real GitHub Repositories

To mirror real GitHub repositories:

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
- **Check Access** on the source uses anonymous HTTPS first unless the pair marks that side **Private**. Destination Check Access is always authenticated write (Contents Read & write). A public source succeeds without installing your GitHub App on that repository.
- GitHub App **Subscribe to events → Push** is webhook delivery only. Git clone/push requires **Repository permissions → Contents: Read and write** (scroll above the event list), plus **Actions: Read and write** (cancel mirror-triggered workflow runs) and **Workflows: Read and write** if the source has `.github/workflows`. After changing permissions, GitHub asks you to review the installation on the destination repository.
- The destination still needs write credentials (GitHub App or PAT with Contents: Write).
- You cannot attach a webhook to a repo you do not administer. Use **Sync Now**, initial bootstrap, or a synthetic event to run the first mirror.



### Step 3: Configure Webhook in GitHub App or Repository



#### Option A: In your GitHub App (*Developer settings > GitHub Apps > [Your App]*):

- **Webhook URL (per GitHub/GHES credential)**: `https://gitmirror-webhook-worker.<your-username>.workers.dev/webhook/github/credential/<id>` or `/webhook/ghes/credential/<id>` (copy from Settings). Direct backend: `http://localhost:8080/api/v1/webhooks/github/credential/<id>`.
- **Webhook secret**: The secret stored on that credential card. Worker `WEBHOOK_SECRETS_JSON` maps credential id → secret; `WEBHOOK_SECRET` remains a legacy fallback.
- **SSL verification**: `Enable SSL verification`
- **Active**: `[x] Active`
- **Permissions**: `Contents: Read & write`, `Metadata: Read-only`, **`Actions: Read & write`** (to cancel mirror-triggered workflow runs), and **`Workflows: Read & write`** if the source contains `.github/workflows` (otherwise GitHub returns `REJECTED_OTHER_REASON` on every branch).
- **Events**: `Push`

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
- **Which events would you like to trigger this webhook?**: `Just the push event`.
- Click **Add webhook**.

---



## 6. Disaster Recovery & CI/CD Failover Procedure

In the event of a primary SCM (e.g., GitHub Primary) outage:

1. **Verify Mirror Freshness**:
  - Open the GitMirror Hub dashboard. Verify that the **Dead Letter Queue (DLQ)** count is `0` and the last sync timestamps for all critical pairs are up to date.
2. **Switch CI/CD Pipeline Remote URLs**:
  - Update the Git remote URL in your CI/CD runner configurations (Jenkinsfiles, ArgoCD Application definitions, or GitLab CI runners) to point to the backup repository (`https://github.com/my-backup-org/core-service.git`).
3. **In-Flight PR Verification**:
  - CI test runners querying `refs/pull/<PR_ID>/head` or `refs/pull/<PR_ID>/merge` can check out the mirrored refs directly on the backup repository.
4. **Post-Incident Recovery (Failback)**:
  - Once the primary SCM recovers, developers continue pushing to either repository. The bidirectional mirror automatically synchronizes newly committed changes back to the primary repository while discarding echo loops.

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
export GIT_HTTP_POST_BUFFER_BYTES=524288000  # 500 MiB http.postBuffer

# Git LFS parallel sync
export GIT_LFS_BATCH_SIZE=50
export GIT_LFS_DISCOVERY_THREADS=4           # Parallel tree walks for LFS pointer discovery
export GIT_LFS_TRANSFER_CONCURRENCY=4        # Concurrent LFS blob uploads/downloads

# GitHub GraphQL fast path (PR pages, mirror snapshot, releases — REST fallback when disabled)
export GITHUB_GRAPHQL_ENABLED=true

# PR mirror concurrency
export GIT_PR_CREATE_CONCURRENCY=6
export GIT_PR_FORK_FETCH_BATCH_SIZE=32
export GIT_PR_LIST_PAGE_SIZE=100

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

1. Click on any repository card on the **Repositories** page (`/repos`).
2. Navigate across the 4 specialized tabs:
  - **Branches & Commits**: Real-time ahead/behind commit calculation and 1-click branch sync triggers.
  - **Pull Requests Mirror**: Cross-repository PR ID mappings and synchronizations.
  - **Git Metadata, LFS & Releases**:
    - **Tags & Notes**: Searchable catalog of lightweight and annotated tags, tagger details, messages, and Git Notes.
    - **Releases**: Detailed GitHub/GitLab release changelogs, draft/pre-release badges, and downloadable binary assets.
    - **Git LFS**: OID hashes, pointer byte sizes, and tracking branch names for mirrored binary assets.
    - **CI Checks**: Live CI/CD check run status, conclusions (success/failure), elapsed execution times, and pipeline links.
  - **Storage & Settings**: Custom storage tier classification (`HOT_PERSISTENT`, `AUTO_LRU`, `EPHEMERAL_STREAM`, `NAS_MOUNT`), sync rules, and branch filters.

