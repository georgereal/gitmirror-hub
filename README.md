# GitMirror Hub - Bidirectional Git Mirroring Utility

A Spring Boot 3 & React-based bidirectional GitHub repository mirroring utility with queue-backed event ingestion, loop/echo prevention, Dead Letter Queue (DLQ) redrive capabilities, and a Chaos & Simulation Lab.

---

## 🌟 Key Architecture & Features

### 1. Bidirectional Mirroring & Loop Prevention
* Supports mirroring between **Repo A ↔ Repo B** across multiple SCM platforms (**GitHub Cloud**, **GitHub Enterprise Server (GHES)**, **Bitbucket Cloud**, **GitLab**, **Cursor Origin**, and **Azure DevOps / Generic Git remotes**).
* **Automated Echo / Loop Filter**: When Repo A pushes to Repo B, the target platform fires an automated webhook for Repo B. The built-in deduplication ledger recognizes that the commit SHA was pushed by this mirror utility, silently skipping it as `SKIPPED (LOOP_DETECTED_SYSTEM_ECHO)` to avoid endless push cycles.
* **Mutual Exclusion & Conflict Isolation**: Per-pair locking ensures sequential push serialization. If trunk branches diverge, the engine quarantines incoming commits onto `sync-conflict/<branch>-<timestamp>`, records a `sync_conflicts` row, and opens a destination PR. Pair policy can instead skip the trunk (`FAIL_JOB`) or force origin (`ORIGIN_WINS`). PR title/body edits use origin-owns compare-and-swap so replica edits are not clobbered.
* **Multi-pod scale**: replicas run **many pairs in parallel**. A long Sync Repo stays on **one** worker (`ARCHITECTURE.md` §3.6.1). Rabbit/CloudAMQP is for webhooks and async drain, not sharding Git across pods.

### 2. Message Queue & DLQ Resilience (CloudAMQP / RabbitMQ)
* **Async Webhook Ingestion**: Webhook endpoints validate HMAC-SHA256 signatures and respond with HTTP `202 Accepted` in <50ms.
* **Serverless Edge Ingestion (Cloudflare Workers)**: Includes a ready-to-deploy Cloudflare Worker gateway in `webhook-worker/` that captures webhooks at the global edge with **0ms cold-start** across GitHub, GHES, Bitbucket, and GitLab, verifying HMAC-SHA256 signatures and streaming them into CloudAMQP / RabbitMQ.
* **Retry Backoff & Self-Healing Circuit Breaker**: Transient errors retry with jittered exponential backoff. Sustained SCM outages trigger an automated tri-state circuit breaker (`CLOSED` ➔ `OPEN` ➔ `HALF_OPEN`) with background health probes to protect downstream rate limits.
* **Dead Letter Queue (DLQ)**: Poisoned messages or permanent failures route automatically to Dead Letter Exchange (`git.sync.dlx` ➔ `git.sync.dlq`).
* **1-Click DLQ Redrive**: Redrive / replay all or specific failed messages from the DLQ back to the main queue once the target is restored.

### 3. Simulation & Fault Testing Sandbox
* **Simulate System Downtime**: Pause the queue consumer with one click. Inbound GitHub webhooks continue to safely buffer in CloudAMQP / RabbitMQ. Resume the consumer to drain the backlog in order.
* **Simulate Outages**: Toggle simulated 500 (Destination Down), 503 (Origin Down), or 429 (Rate Limit) to test failure handling and DLQ routing.
* **Synthetic Webhook Generator**: Emit test push events with custom branches, commit SHAs, and messages directly from the UI without needing real GitHub push actions.

### 4. Real-time React Dashboard
* Visual overview of Active Mirror Pairs, 24h Sync Metrics, Queue & DLQ Status.
* Live WebSocket event stream with color-coded status badges (`QUEUED`, `SYNCING`, `SUCCESS`, `SKIPPED`, `DLQ`, `PAUSED`, `INTERRUPTED`).
* Interactive Job Log Drawer with line-by-line audit traces (also printed as `[job-{id}]` on the backend console), live object progress with elapsed/ETA, source vs destination labels, a sync pipeline stepper, and per-job REST vs Git HTTP usage.
* Resumable bootstrap push: destination WRITE preflight, default branch first, then remaining refs in batches. `REJECTED_*` aborts the rest of a full-mirror. Connection-reset retries skip heads already on the destination at matching SHA.
* **Pause / resume / skip-stage** for long mirrors; consumers pause on startup by default until operators resume from Queue Manager.
* **GitHub GraphQL fast path** for PR listing and mirror metadata snapshots (REST fallback). **Parallel LFS** discovery and transfer for large binary repos.

---

## 🚀 Quickstart Guide

### Prerequisites
* **Java 21+** (JDK 21 or JDK 23)
* **Maven 3.8+**
* **Node.js 18+** & **npm**
* **AMQP Broker**: Any free cloud broker like [CloudAMQP](https://www.cloudamqp.com/) (Free "Little Lemur" plan) or a local RabbitMQ instance (`brew install rabbitmq` or `docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:3-management`).

---

### Step 1: Configure Cloud Queue (Optional)
By default, the application connects to `amqp://guest:guest@localhost:5672`.
To use a free cloud broker (e.g. CloudAMQP), set the environment variable:

```bash
export SPRING_RABBITMQ_ADDRESSES="amqps://<user>:<password>@<host>/<vhost>"
```

---

### Step 2: Start the Backend (Spring Boot)

```bash
cd backend
# Optional: point JAVA_HOME at JDK 21+ if it is not already on your PATH
# export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # macOS example
# Required: unique key used to encrypt PATs and App private keys at rest
export GIT_UTILITY_ENCRYPTION_KEY="$(openssl rand -hex 32)"
# Optional for large public mirrors (avoid /tmp; raise Git HTTP timeout)
# export GIT_WORKSPACE_DIR="$HOME/git-utility-mirrors"
mvn spring-boot:run
```
* **REST API**: `http://localhost:8080/api/v1`
* **H2 Database Console** (off by default): `export GIT_H2_CONSOLE_ENABLED=true` then open `http://localhost:8080/h2-console` (JDBC URL: `jdbc:h2:file:./data/gitutility`, User: `sa`, Password: empty)
* **WebSocket Endpoint**: `http://localhost:8080/ws`

This process is a **localhost operator console**. There is no login. Do not bind it to a public interface. Queue purge, DLQ redrive, simulation, and stored credentials are reachable by anyone who can hit port 8080. See [SECURITY.md](SECURITY.md).

---

### Step 3: Start the Frontend (React + Vite)

```bash
cd frontend
npm install
npm run dev
```
* **Dashboard UI**: `http://localhost:3000`

---

### Step 4: (Optional) Deploy Edge Serverless Webhook Worker (Cloudflare Workers)

To decouple ingestion and ensure **100% webhook uptime with 0ms cold-start**:

```bash
cd webhook-worker
npm install
# Set your RabbitMQ / CloudAMQP credentials in wrangler.toml and Cloudflare secrets
npx wrangler secret put RABBITMQ_USER
npx wrangler secret put RABBITMQ_PASSWORD
npx wrangler secret put WEBHOOK_SECRET            # GitHub HMAC secret
npx wrangler secret put BITBUCKET_WEBHOOK_SECRET  # Bitbucket HMAC secret (optional)
npm run deploy
```

See [`webhook-worker/README.md`](webhook-worker/README.md) for detailed configuration.

---

## 🧪 Testing Failure Scenarios & DLQ

### Scenario A: Testing System Downtime & Queue Buffering
1. Navigate to the **Queue & DLQ** tab or **Simulation Lab** in the dashboard.
2. Click **"Pause Consumer"**.
3. In the **Simulation Lab**, use the **Synthetic Webhook Generator** to emit 3 test push events.
4. Notice the **Main Sync Queue** count increases to 3 and messages buffer safely.
5. Click **"Resume Consumer"** and watch the worker sequentially process and drain the queue to `SUCCESS`.

### Scenario B: Testing Outage & DLQ Recovery
1. In the **Simulation Lab**, check **"Simulate Destination Repo Down (500 Connection Refused)"**.
2. Emit a synthetic webhook.
3. The sync worker attempts the job, fails, retries with backoff, and routes the message to the **Dead Letter Queue (DLQ)** with status `DEAD_LETTERED`.
4. Inspect the error in the **Job Logs** drawer.
5. In the Simulation Lab, uncheck **"Simulate Destination Repo Down"**.
6. In the **Queue & DLQ** tab, click **"Redrive All DLQ"**.
7. The failed event is re-injected into the main queue and completes with status `SUCCESS`.

---

## 📡 Webhook Integration Across Providers

### 1. GitHub Cloud / GitHub Enterprise Server
In your GitHub App or Repository settings (*Settings > Webhooks > Add webhook*):
* **Payload URL**: `https://gitmirror-webhook-worker.<your-username>.workers.dev/webhook/github` (or `/webhook/ghes`)
* **Content type**: `application/json`
* **Secret**: The secret configured in `WEBHOOK_SECRET`
* **Events**: Push events, Pull requests, Commit statuses

### 2. Bitbucket Cloud
In your Bitbucket repository settings (*Repository settings at bottom of sidebar > Webhooks > Add webhook*):
* **URL**: `https://gitmirror-webhook-worker.<your-username>.workers.dev/webhook/bitbucket`
* **Secret**: The secret configured in `BITBUCKET_WEBHOOK_SECRET`
* **Triggers**: Repository Push, Pull request Created/Updated/Fulfilled/Declined/Comment created

---

## License

MIT. See [LICENSE](LICENSE).

## Security

Localhost-only operator console (no login). See [SECURITY.md](SECURITY.md).
