# GitMirror Hub — Multi-Pod Local Runbook

How to run **two (or more) backend Hub instances on one machine** to exercise competing Rabbit consumers, pair leases, cluster pause/CB, and Internals heartbeats.

For single-instance setup, CloudAMQP, and day-to-day ops, see [`INSTRUCTIONS.md`](INSTRUCTIONS.md). Architecture and **enterprise throughput model**: [`ARCHITECTURE.md`](ARCHITECTURE.md) §3.6.1.

---

## What you are testing

Multiple Hub JVMs:

- Compete as consumers on the **same** Rabbit queues (`full`, `incremental`, `inbound`)
- Share DB state: `pair_leases`, `cluster_runtime`, `instance_heartbeats`, job control flags
- Advertise distinct identities via `GIT_UTILITY_INSTANCE_ID` (or `HOSTNAME` / `POD_NAME`)

This exercises **pair-level fleet scale** (two pods can run **different** mappings at once). It does **not** shard one long Sync Repo across pods. UI Sync / Dispatch still runs `GitSyncEngine.executeSync` on **one** JVM (`workerInstanceId`); LFS/PR pools are threads **on that JVM**. `pair_leases` prevent two pods from Git-writing the same `mappingId`. Consumer pause and circuit-breaker OPEN are written to `cluster_runtime` and applied on every pod.

---

## Shared dependencies

| Dependency | Local smoke test | Production / K8s |
| :--- | :--- | :--- |
| **Database** | Default H2 file DB with `AUTO_SERVER=TRUE` (both JVMs from the **same repo-root cwd**) | PostgreSQL (required for real multi-pod) |
| **RabbitMQ / CloudAMQP** | Same broker URL on every instance | Same |
| **Bare-repo storage** | Same `GIT_WORKSPACE_DIR` / `GIT_STORAGE_*` in both env files | Shared `NAS_MOUNT` (or accept cold re-fetch) |

---

## Env files (same pattern as single-instance `env`)

Single-instance uses a gitignored root `env` file:

```bash
# Local / day-to-day (Maven)
source env && mvn -f backend/pom.xml spring-boot:run

# Alternate (Gradle wrapper)
source env && ./backend/gradlew -p backend bootRun
```

For multi-pod, use **one env file per JVM** (shared broker/workspace + distinct identity/port):

| File | Committed? | Role |
| :--- | :--- | :--- |
| `env` | No (gitignored) | Your single-instance secrets (reference) |
| `env.pod-a` / `env.pod-b` | No (gitignored) | Local multi-pod start files |
| `env.pod-a.example` / `env.pod-b.example` | Yes | Templates to copy |

### First-time setup

```bash
# From the repository root
cp env.pod-a.example env.pod-a
cp env.pod-b.example env.pod-b
```

Edit both files so they share the **same** `SPRING_RABBITMQ_ADDRESSES`, `GIT_UTILITY_ENCRYPTION_KEY`, and workspace paths (copy from your root `env`). Keep only these different:

- `env.pod-a`: `GIT_UTILITY_INSTANCE_ID=pod-a`, `SERVER_PORT=8080`
- `env.pod-b`: `GIT_UTILITY_INSTANCE_ID=pod-b`, `SERVER_PORT=8081`

---

## Prerequisites

Same as the main runbook: JDK 21+, Maven 3.8+ (or Gradle Wrapper), and a running AMQP broker (local RabbitMQ or CloudAMQP).

Optional JDK export (if not already on `PATH`):

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-23.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
```

---

## Run two backends on this machine

Always start from the **repo root** so both processes share the same H2 file (`./data/gitutility`) and the same project layout as single-instance.

### Terminal 1 — pod-a (port 8080)

```bash
# From the repository root (Maven — preferred for local)
source env.pod-a && mvn -f backend/pom.xml spring-boot:run

# Or Gradle wrapper:
# source env.pod-a && ./backend/gradlew -p backend bootRun
```

- API: `http://localhost:8080/api/v1`
- Local metrics: `GET http://localhost:8080/api/v1/runtime-metrics`
- Cluster metrics: `GET http://localhost:8080/api/v1/runtime-metrics/cluster`

### Terminal 2 — pod-b (port 8081)

```bash
# From the repository root (Maven — preferred for local)
source env.pod-b && mvn -f backend/pom.xml spring-boot:run

# Or Gradle wrapper:
# source env.pod-b && ./backend/gradlew -p backend bootRun
```

- API: `http://localhost:8081/api/v1`
- Local metrics: `GET http://localhost:8081/api/v1/runtime-metrics`
- Cluster metrics: `GET http://localhost:8081/api/v1/runtime-metrics/cluster`

### Frontend (optional, one UI)

Point the Vite proxy / API base at **either** backend (e.g. `:8080`). Cluster Internals aggregates sibling heartbeats from the shared DB, so either pod’s cluster endpoint shows both.

```bash
cd frontend
npm run dev
```

Open Internals: `http://localhost:3000/observability/internals` — expect **Pods live** to list `pod-a` and `pod-b`.

---

## Operator checklist after boot

By default, consumers start **paused** (`git-utility.queue.pause-consumers-on-startup: true`).

1. Open **Queue Manager** (UI against either pod) → **Resume Consumer**.
2. Confirm both instance IDs appear under **Observability** / **Internals** (cluster fleet strip: live/known pods + per-pod thread counts).
3. Confirm **External API usage by install** shows configured GitHub App install keys (`github:<id>`, `ghes:<id>`, or `*:pat`) and rolls up REST RPM / quota across pods.
4. Enqueue work (Dispatch, synthetic webhook, or real webhook) and watch jobs pick up on either pod.
5. Same mapping should not run concurrently on both pods (`pair_leases`).
6. In-flight jobs expose `workerInstanceId` on Queues.

**Stuck lease recovery:** if a pod dies mid-sync before lease TTL expiry (default 90s) and work is blocked, delete the `pair_leases` row for that `mapping_id`.

---

## Kubernetes / production notes

For `replicas > 1` in a real cluster:

| Item | Guidance |
| :--- | :--- |
| DB | Use **PostgreSQL**, not file H2 |
| Identity | Set `GIT_UTILITY_INSTANCE_ID`, or rely on `HOSTNAME` / `POD_NAME` |
| Storage | Prefer shared `NAS_MOUNT` for bare repos across nodes |
| Pause / CB | Fleet-wide via `cluster_runtime` |
| Affinity | Warm-cache stickiness deferred — see `future-work/cache-resume-worker-affinity.md` |

---

## Related env vars

| Variable | Purpose | Default / notes |
| :--- | :--- | :--- |
| `GIT_UTILITY_INSTANCE_ID` | Stable pod/JVM id for leases & heartbeats | Set in `env.pod-a` / `env.pod-b` |
| `SERVER_PORT` | HTTP listen port | `8080` / `8081` in the pod env files |
| `SPRING_RABBITMQ_ADDRESSES` | Shared AMQP URL | Must match on every pod |
| `GIT_UTILITY_ENCRYPTION_KEY` | AES key for credential fields | Must match on every pod |
| `GIT_WORKSPACE_DIR` / `GIT_STORAGE_*` | Shared bare-repo paths | Prefer identical on every pod |
| `GIT_CLUSTER_LEASE_TTL_SEC` | Pair lease TTL | `90` |
| `GIT_CLUSTER_HEARTBEAT_MS` | Heartbeat write interval | `3000` |
| `GIT_CLUSTER_HEARTBEAT_STALE_SEC` | Stale pod threshold for Internals | `15` |
| `GIT_CLUSTER_CONTROL_POLL_MS` | How often pods poll `cluster_runtime` | `2000` |
| `GIT_QUEUE_PAUSE_ON_STARTUP` | Pause consumers on boot | `true` |
