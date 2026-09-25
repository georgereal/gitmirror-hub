# Kafka incremental sync from upstream git events

> **Status:** Core shipped. Hub consumes the incremental lane; `webhook-worker-kafka` is the optional Cloudflare publisher.  
> **Folder:** [`future-work/`](README.md)  
> **Live system today:** Operator mirrors run on `GIT_MESSAGING_PROVIDER=none` (in-process) or `rabbitmq`. Continuous webhook sync today is the Rabbit inbound lane plus `git.sync.incremental.queue`. `GIT_MESSAGING_PROVIDER=kafka` fails fast at startup.  
> **Related:** [`kafka-mirroring-partitions.md`](kafka-mirroring-partitions.md) stays the later design for putting **full and incremental** execution on Kafka together. This plan does not implement that cutover.

Hub keeps selected-repo **full mirrors** on the existing messaging provider (`none` for this work). Continuous bidirectional sync is a **separate bus** that consumes **one incremental lane**. The lane name is not fixed: each organization sets the Kafka topic or the Rabbit queue, the consumer group, and the connection settings. Defaults below are examples so a fresh checkout runs; production and other orgs override them.

In corporate use, an upstream system already accepts SCM webhooks and publishes **normalized git events** onto that lane (Kafka or Rabbit). Hub reads those records and runs the incremental mirror. Hub is not on the webhook hot path.

For local testing, a **separate loader module** can publish the same records to whichever bus is configured. It is not part of the Hub process, not the existing Cloudflare `webhook-worker`, and it stays off unless someone starts it. Day-to-day tests use a local broker (see §9).

---

## Decision summary

| Decision | Choice |
| :--- | :--- |
| Operator / full mirror | Unchanged. `GIT_MESSAGING_PROVIDER=none` for selected-repo mirrors, bulk mirror, resume, overwrite |
| Continuous sync bus | `GIT_WEBHOOK_BUS_PROVIDER=kafka\|rabbitmq\|off`. Default `off` leaves today’s behavior alone |
| What the bus carries | **One incremental lane only.** Full-mirror jobs stay on `GIT_MESSAGING_PROVIDER` |
| Lane name | Configurable. Kafka default topic `git.sync.incremental`. Rabbit default queue `git.webhook.incremental.queue` so it does not collide with the full-mirror execution queue |
| Full-sync topic | Out of scope. No `git.sync.full`, no co-partition assignor |
| Who publishes in corporate | That org’s upstream system. Hub is a consumer |
| Who publishes in test | Optional Cloudflare Worker [`webhook-worker-kafka/`](../webhook-worker-kafka/README.md). Undeployed or `ENABLED=false` until you want it. The Rabbit worker is a different script |
| Record key | Canonical **repo URL** (Kafka record key; Rabbit routing stays on the configured routing key, repo URL is inside the body) |
| Payload | Same normalized git event on both providers. Hub resolves `mappingId` |
| Consumer identity | Kafka `group.id` and Rabbit consumer concurrency are env. Two orgs on one cluster use different groups and different lane names. Every pod **of one org** uses the **same** group and topic |
| Multi-pod | Kafka assigns partitions inside that group. Cross-pod safety for one pair is the shared DB (`pair_leases`, `echo_ledger`), not the partition (see §5) |
| Offset commit | After the incremental outcome: mirrored, echo skipped, unmapped, conflict isolated, or dead-lettered |
| Lease busy | Do not pause the partition. Commit and produce the same key again at the tail |
| Echo | Existing `echo_ledger` / `DedupLedgerService` (`LOOP_DETECTED_SYSTEM_ECHO`) |
| Conflicts | Existing `GitSyncEngine` incremental path: trunk policy, tag conflicts, PR metadata compare-and-swap, `sync_conflicts` |
| Test cluster | Local single-node Kafka (Docker) for $0. Hosted options in §9: Confluent Cloud Basic, CloudKarafka free shared cluster, Redpanda Serverless trial |
| `GIT_MESSAGING_PROVIDER=kafka` | Stays fail-fast until a later plan moves operator mirrors onto Kafka |

---

## 1. Two planes

```mermaid
flowchart LR
  ui[Operator Sync Repo / bulk mirror] --> noneBus["GIT_MESSAGING_PROVIDER=none"]
  noneBus --> fullGit[GitSyncEngine full mirror]
  upstream[Org upstream webhook processor] --> lane["Incremental lane — topic or queue from env"]
  loader[webhook-worker-kafka optional] --> lane
  lane --> hub[Hub consumer kafka or rabbitmq]
  hub --> incrGit[GitSyncEngine incremental]
```

| Plane | Config | Work |
| :--- | :--- | :--- |
| Selected-repo mirror | `GIT_MESSAGING_PROVIDER=none` (or `rabbitmq` for orgs that already queue full mirrors) | Full clone, Sync Repo, resume, overwrite |
| Continuous bidirectional sync | `GIT_WEBHOOK_BUS_PROVIDER=kafka` or `rabbitmq` | One incremental lane, name set by env |

`off` means Hub does not subscribe to this bus. Operator mirrors still run. The legacy Cloudflare Worker → Rabbit inbound envelope path stays as it is today only while this flag is `off`.

When the bus is `kafka` or `rabbitmq`, that consumer is the incremental ingress. It reads the normalized git event from the configured topic or queue. It does not also run the legacy inbound envelope consumer, so the same push is not mirrored twice.

The incremental job runs **inside that consumer**. It does not hop onto the `none` thread pool. A crash after an early ack would drop the event. Kafka offset commit and Rabbit ack happen only after echo handling and the Git outcome.

`pair_leases` plus `RepoDirLockService` still allow one Git writer per mapping. An in-process full mirror and a webhook incremental for the same pair serialize on that lease.

If `GIT_MESSAGING_PROVIDER=rabbitmq` and `GIT_WEBHOOK_BUS_PROVIDER=rabbitmq` share a broker, the full-mirror queue and the webhook queue must be different names. Startup fails when they are set to the same queue.

---

## 2. Corporate path

```text
SCM webhook → org upstream → configured incremental topic or queue → Hub consumer
```

The upstream owns HMAC validation, provider parsing, and the decision to emit a git event. Hub does not receive the raw GitHub body on this path.

Hub, per record:

1. Parse the normalized git event.
2. Resolve the active pair from the canonical repo URL (either side of the mapping).
3. Unknown or inactive pair: persist an unmapped-webhook row (same 7-day retention as today) and commit the offset.
4. Echo check via `DedupLedgerService`. A hit is `LOOP_DETECTED_SYSTEM_ECHO`: no job, commit the offset.
5. Otherwise run one incremental sync through `GitSyncEngine` for that ref (push or ref delete).
6. On success, the engine writes `echo_ledger` (repo + SHA, or ref-delete token) **before** the offset commit, so the reverse event from the other side is dropped.
7. Conflicts stay in the engine: `conflict_check`, trunk policy (`ISOLATE`, `ORIGIN_WINS`, `FAIL_JOB`), tag SHA mismatch, PR metadata compare-and-swap. Open rows remain `sync_conflicts`. Isolated `sync-conflict/*` heads stay out of reverse sync.
8. Poison after bounded retries → the configured dead-letter topic or queue, then commit or ack the source. Redrive publishes the same body back onto the incremental lane (Kafka: same record key).

Pull-request events are in scope only when the upstream emits them as git events with `eventType=pull_request`. The consumer then calls the existing PR webhook path (`PullRequestSyncService` echo + metadata sync). Push and ref-delete are the required first slice.

---

## 3. Test path

An organization that already has an upstream does not deploy a publisher. Hub only reads the configured topic or queue.

For a test feed, use a **second Cloudflare Worker**, [`webhook-worker-kafka/`](../webhook-worker-kafka/README.md). It is not the Rabbit [`webhook-worker/`](../webhook-worker/README.md), and Hub does not start it. Deploy it when you want GitHub webhooks turned into Kafka records. Leave it undeployed, or deploy with `ENABLED=false`, when you do not.

```text
webhook-worker-kafka (Cloudflare, optional) → same topic, same record → Hub consumer
```

The Worker accepts the same `/webhook/github|gitlab|bitbucket` paths, checks HMAC, and produces the normalized git event with the Confluent REST API. The record key is the canonical repo URL. Stop and start steps are in that README and in [`INSTRUCTIONS.md`](../INSTRUCTIONS.md).

---

## 4. Lane contract

One incremental lane per Hub deployment. The **record shape is fixed**. The **topic, queue, group, and connection are not** — another organization points the same Hub build at its own broker by changing env.

Shared:

| Item | Default | Env |
| :--- | :--- | :--- |
| Bus | `off` | `GIT_WEBHOOK_BUS_PROVIDER` = `kafka` \| `rabbitmq` \| `off` |
| Retries | existing jittered backoff | `GIT_MAX_RETRY_ATTEMPTS`, `GIT_RETRY_INITIAL_INTERVAL_MS`, `GIT_RETRY_MULTIPLIER`, `GIT_RETRY_MAX_INTERVAL_MS` |

### Kafka (`GIT_WEBHOOK_BUS_PROVIDER=kafka`)

| Item | Default | Env |
| :--- | :--- | :--- |
| Bootstrap servers | required | `GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS` |
| Security protocol | `PLAINTEXT` | `GIT_WEBHOOK_KAFKA_SECURITY_PROTOCOL` (`SASL_SSL` for Confluent Cloud) |
| SASL mechanism | `PLAIN` | `GIT_WEBHOOK_KAFKA_SASL_MECHANISM` |
| API key / secret | empty; required when protocol is `SASL_SSL` | `GIT_WEBHOOK_KAFKA_SASL_USERNAME`, `GIT_WEBHOOK_KAFKA_SASL_PASSWORD` |
| Client id | `git-mirror-hub` | `GIT_WEBHOOK_KAFKA_CLIENT_ID` |
| Consumer group | `git-mirror-hub` | `GIT_WEBHOOK_KAFKA_GROUP_ID` |
| Auto offset reset | `earliest` | `GIT_WEBHOOK_KAFKA_AUTO_OFFSET_RESET` |
| Topic | `git.sync.incremental` | `GIT_WEBHOOK_KAFKA_INCREMENTAL_TOPIC` |
| Poison records | `unmapped_webhook_events` (`KAFKA_POISON`) | none. Kafka has no dead-letter topic on this lane |
| Partitions to create when Hub provisions topics | 32 | `GIT_WEBHOOK_KAFKA_INCREMENTAL_PARTITIONS` |
| Max poll interval | `600000` (10 min) | `GIT_WEBHOOK_KAFKA_MAX_POLL_INTERVAL_MS` |
| Session timeout | `45000` | `GIT_WEBHOOK_KAFKA_SESSION_TIMEOUT_MS` |
| Record key | canonical repo URL | fixed in the producer; consumers do not re-key |

Give each environment its own `GIT_WEBHOOK_KAFKA_GROUP_ID` when several Hubs read one cluster. Sharing a group splits partitions across those deployments.

### RabbitMQ (`GIT_WEBHOOK_BUS_PROVIDER=rabbitmq`)

| Item | Default | Env |
| :--- | :--- | :--- |
| Broker URL | `SPRING_RABBITMQ_ADDRESSES` when that is already set | `GIT_WEBHOOK_RABBITMQ_ADDRESSES` |
| Username / password | Spring Rabbit user when the URL has none | `GIT_WEBHOOK_RABBITMQ_USERNAME`, `GIT_WEBHOOK_RABBITMQ_PASSWORD` |
| Exchange | `git.webhook.exchange` | `GIT_WEBHOOK_RABBITMQ_EXCHANGE` |
| Queue | `git.webhook.incremental.queue` | `GIT_WEBHOOK_RABBITMQ_QUEUE` |
| Routing key | `git.webhook.incremental` | `GIT_WEBHOOK_RABBITMQ_ROUTING_KEY` |
| Dead letter exchange / queue | `git.sync.dlx` / `git.sync.incremental.dlq` | `GIT_WEBHOOK_RABBITMQ_DLX`, `GIT_WEBHOOK_RABBITMQ_DLQ` |
| Prefetch | 1 | `GIT_WEBHOOK_RABBITMQ_PREFETCH` |
| Consumer concurrency | 1 | `GIT_WEBHOOK_RABBITMQ_CONCURRENCY` |

Rabbit has no consumer group. Competing consumers on `GIT_WEBHOOK_RABBITMQ_QUEUE` are the multi-pod equivalent: same queue name, prefetch 1. A second organization uses a different queue name on a shared broker.

### Record

```json
{
  "provider": "github",
  "repoUrl": "https://github.com/org/repo",
  "ref": "refs/heads/main",
  "beforeSha": "abc…",
  "afterSha": "def…",
  "deliveryId": "upstream-delivery-id",
  "eventType": "push",
  "receivedAt": "2026-09-24T12:00:00Z"
}
```

`eventType` is `push`, `delete`, or `pull_request`. Delete uses a zero `afterSha`. `deliveryId` is the idempotency token from the upstream (or from the loader).

On Kafka, the same repo URL always hashes to the same partition, so one repository stays ordered. The other side of a bidirectional pair is a different URL and can land on a different partition and a different pod (§5). Other repos on other partitions run in parallel. On Rabbit, ordering is per queue; concurrency above 1 can interleave refs for one repo, so leave `GIT_WEBHOOK_RABBITMQ_CONCURRENCY` at 1 unless the org accepts that. Git push concurrency stays capped by `GIT_THROTTLE_MAX_CONCURRENT_PUSHES` on both providers.

### Busy lease

A Kafka partition holds many repos. Pausing it because one pair’s lease is held stalls every other repo on that shard. On Rabbit, do not stop the consumer for one busy pair; ack and republish to the tail of the same queue.

On each record, before Git:

1. Echo or unmapped → skip, commit.
2. Destination already contains `afterSha`, or a newer job for the same ref superseded this SHA → skip, commit (`STALE_SUPERSEDED` / existing DAG fast-path).
3. Lease held by other work for this mapping that this event still needs → mark the job queued, **commit**, **produce the same payload and key** onto the tail. The partition continues with the next record.
4. Lease free → acquire `pair_leases`, take `RepoDirLockService`, run incremental `GitSyncEngine`, release, commit.

---

## 5. Multi-pod Kafka

Kafka decides **which pod reads a partition**. The shared database decides **which pod may Git-write a pair**, and **which events are echoes**. Pod lifecycle stays in `instance_heartbeats` (`GIT_UTILITY_INSTANCE_ID`). Kafka does not register pods.

Every Hub replica in one deployment uses the same `GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS`, the same `GIT_WEBHOOK_KAFKA_INCREMENTAL_TOPIC`, and the same `GIT_WEBHOOK_KAFKA_GROUP_ID`. A pod that joins with a different group reads the topic again and mirrors duplicates. Distinct `GIT_UTILITY_INSTANCE_ID` per pod. Shared database (local multi-pod: one H2 file with `AUTO_SERVER=TRUE`; enterprise: Postgres). Bare repos on `NAS_MOUNT` when pods should share the pack cache; otherwise the pod that draws the partition fetches cold.

`GIT_MESSAGING_PROVIDER=none` still runs a full mirror on the pod that accepted the HTTP call. That is safe across pods only because the full mirror and the Kafka consumer both take `pair_leases` for the same `mappingId`. Do not expect `none` to move a full mirror onto another pod.

```mermaid
flowchart TB
  topic[Incremental topic]
  topic --> p0[partition 0]
  topic --> p1[partition 1]
  p0 --> podA[pod A group git-mirror-hub]
  p1 --> podB[pod B same group]
  podA --> lease[pair_leases]
  podB --> lease
  podA --> echo[echo_ledger]
  podB --> echo
```

### What one group guarantees

| Situation | What happens |
| :--- | :--- |
| Two repos, two partitions | Pod A and pod B mirror them at the same time |
| Two events, same repo URL | Same partition, one pod, in order, until a rebalance |
| Pod joins or leaves | Cooperative sticky assignor moves only the revoked partitions. Other pods keep their partitions |
| Pod crash before commit | The group redelivers uncommitted records on that partition to the new owner |
| Two orgs | Different `GIT_WEBHOOK_KAFKA_GROUP_ID` and different topic names. They do not share assignment |

Assignment uses the cooperative sticky assignor so a scale-out does not stop every pod.

### Both sides of one pair

The upstream keys by repo URL. Side A and side B of one `RepoMapping` hash independently, so they often sit on different partitions and different pods. Kafka will not pin a pair to one process.

Cross-pod interaction for that pair is the database:

1. Pod A consumes a push on side A, resolves `mappingId`, acquires `pair_leases`, pushes to side B, writes `echo_ledger` for the destination SHA, commits the offset, releases the lease.
2. The upstream then emits the side B event. Pod B (other partition) consumes it, finds the echo row, records `LOOP_DETECTED_SYSTEM_ECHO`, commits. No second push.
3. If the side B event arrives while pod A still holds the lease, pod B does not pause its partition. It commits and produces the same key to the tail (§4). After pod A releases, a later poll on pod B runs the echo check and drops it.
4. A non-fast-forward on either side records `sync_conflicts` in the shared table. The pair UI on any pod reads that row.

`RepoDirLockService` is in-process only. It serializes Git on one JVM. `pair_leases` is what stops pod B from writing `mappingId` 42 while pod A holds it. Lease TTL defaults to 90 seconds (`git-utility.cluster.lease-ttl-seconds`) and the owner renews for the duration of `executeSync`.

### Full mirror on one pod, Kafka on another

An operator sync on pod A (`GIT_MESSAGING_PROVIDER=none`) holds `pair_leases` for that mapping. A webhook record that hashes to pod B sees the lease, commits, and re-produces to the tail. Pod B keeps draining other repos on its partitions. When the full mirror finishes, the re-produced incremental hits the DAG fast-path if the full mirror already contains that SHA, or runs if it does not.

### Rebalance while Git is running

The listener handles one record synchronously on the consumer thread, then commits. Spring Kafka completes a revocation after that handler returns, so a normal rebalance waits for the in-flight incremental to finish and commit. The new owner does not see that record.

If the handler runs longer than `max.poll.interval.ms`, the broker removes the member and assigns the partition elsewhere while the old thread may still be inside `GitSyncEngine`. The new owner then sees an uncommitted record and a live lease, and takes the busy-lease path (commit and re-produce) instead of starting a second push. Set `GIT_WEBHOOK_KAFKA_MAX_POLL_INTERVAL_MS` above the longest incremental you expect (default `600000`, ten minutes). Incremental ref syncs are the workload; full mirrors are not on this consumer.

Session timeout `GIT_WEBHOOK_KAFKA_SESSION_TIMEOUT_MS` (default `45000`) is how fast the group notices a dead pod. Heartbeats are client heartbeats, separate from `instance_heartbeats`.

### Pause and circuit breaker

`cluster_runtime` is already fleet-wide. Consumer pause and circuit-breaker OPEN pause the Kafka listener on **every** pod. Records stay on the topic. Resume starts polling from the last committed offset on each partition. One pod pausing its listener by itself is not the operator control; the shared row is.

### Fleet env

```bash
# Same on every pod
export GIT_WEBHOOK_BUS_PROVIDER=kafka
export GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export GIT_WEBHOOK_KAFKA_GROUP_ID=git-mirror-hub
export GIT_WEBHOOK_KAFKA_INCREMENTAL_TOPIC=git.sync.incremental
export GIT_WEBHOOK_KAFKA_MAX_POLL_INTERVAL_MS=600000
export GIT_WEBHOOK_KAFKA_SESSION_TIMEOUT_MS=45000

# Different on every pod
export GIT_UTILITY_INSTANCE_ID=pod-a
export SERVER_PORT=8080
```

Rabbit on this bus is the same fleet shape with competing consumers: every pod listens on `GIT_WEBHOOK_RABBITMQ_QUEUE`, prefetch 1, and the database rules above are unchanged. Any pod may receive the next delivery. There is no partition owner.

### Multi-pod exit check

1. Two pods, one group, two partitions, two mapped repos. Each pod mirrors a different repo at the same time.
2. Push side A through pod A. The reverse event is consumed on the other pod and skipped from `echo_ledger`.
3. Start a full mirror on pod A for a mapping whose webhook partition is owned by pod B. Pod B keeps consuming other keys; that mapping waits on the lease, then fast-paths or runs once.
4. Stop pod A mid-incremental. Pod B receives the uncommitted record and finishes it once. `sync_jobs.workerInstanceId` shows which pod ran it.

---

## 6. Echo and conflicts

These stay in the existing services. The Kafka listener or Rabbit consumer only delivers the record and decides when to commit or ack.

| Concern | Where it lives |
| :--- | :--- |
| Echo write on successful push | `GitSyncEngine` / `PullRequestSyncService` → `DedupLedgerService` → `echo_ledger` |
| Echo drop on the way in | `DedupLedgerService` before a job is created (`LOOP_DETECTED_SYSTEM_ECHO`) |
| Trunk / tag / metadata conflicts | `GitSyncEngine` `conflict_check`, `SyncConflictService`, `sync_conflicts` |
| App-sender skip | Existing ingestion rule, applied when `eventType` and sender identify a mirror push this Hub made |

The Kafka offset commit or Rabbit ack happens after the echo row is durable. A reverse event that arrives next is then dropped instead of mirrored back.

---

## 7. Hub configuration

```bash
# Selected-repo mirrors. none = in-process. rabbitmq = existing full-mirror queues.
export GIT_MESSAGING_PROVIDER=none

# Incremental git-event bus. off | kafka | rabbitmq
export GIT_WEBHOOK_BUS_PROVIDER=kafka
```

Kafka example (local defaults, override topic and group per org):

```bash
export GIT_WEBHOOK_BUS_PROVIDER=kafka
export GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export GIT_WEBHOOK_KAFKA_SECURITY_PROTOCOL=PLAINTEXT
export GIT_WEBHOOK_KAFKA_CLIENT_ID=git-mirror-hub
export GIT_WEBHOOK_KAFKA_GROUP_ID=git-mirror-hub
export GIT_WEBHOOK_KAFKA_INCREMENTAL_TOPIC=git.sync.incremental
export GIT_WEBHOOK_KAFKA_INCREMENTAL_PARTITIONS=32
export GIT_WEBHOOK_KAFKA_AUTO_OFFSET_RESET=earliest
```

Another organization on the same cluster changes the group and the topic, for example `GIT_WEBHOOK_KAFKA_GROUP_ID=acme-hub` and `GIT_WEBHOOK_KAFKA_INCREMENTAL_TOPIC=acme.git.incremental`.

Rabbit example (CloudAMQP or local Rabbit; queue name is theirs):

```bash
export GIT_WEBHOOK_BUS_PROVIDER=rabbitmq
export GIT_WEBHOOK_RABBITMQ_ADDRESSES=amqps://USER:PASS@HOST/VHOST
export GIT_WEBHOOK_RABBITMQ_EXCHANGE=acme.sync.exchange
export GIT_WEBHOOK_RABBITMQ_QUEUE=acme.git.incremental
export GIT_WEBHOOK_RABBITMQ_ROUTING_KEY=acme.git.incremental
export GIT_WEBHOOK_RABBITMQ_DLX=acme.sync.dlx
export GIT_WEBHOOK_RABBITMQ_DLQ=acme.git.incremental.dlq
export GIT_WEBHOOK_RABBITMQ_PREFETCH=1
export GIT_WEBHOOK_RABBITMQ_CONCURRENCY=1
```

Startup fails fast when `kafka` is selected without bootstrap servers, or when `rabbitmq` is selected without an address. `GIT_MESSAGING_PROVIDER=kafka` remains the reserved full-broker switch and still fails fast.

Spring beans for the Kafka listener load only when the webhook bus is `kafka`. Rabbit webhook beans load only when the webhook bus is `rabbitmq`. Rabbit auto-config stays excluded while messaging is `none` and the webhook bus is not `rabbitmq`.

---

## 8. `webhook-worker-kafka`

| Rule | Detail |
| :--- | :--- |
| Location | `webhook-worker-kafka/` |
| Runtime | Its own Cloudflare script, `gitmirror-webhook-worker-kafka` |
| Default | Not deployed. Hub has no flag that launches it |
| Produce | Normalized git event (§4) to `KAFKA_TOPIC` via Confluent REST. Key = repo URL |
| Inputs | `POST /webhook/github`, `/webhook/gitlab`, `/webhook/bitbucket` (and the `/api/v1/webhooks/...` aliases) |
| Rabbit worker | [`webhook-worker/`](../webhook-worker/README.md) stays the Rabbit publisher. Do not point both scripts at the same GitHub webhook unless you want two copies of each event |

Local test:

1. Hub: `GIT_MESSAGING_PROVIDER=none` and `GIT_WEBHOOK_BUS_PROVIDER=kafka`, topic matching `KAFKA_TOPIC`.
2. `cd webhook-worker-kafka && npm run deploy` with `ENABLED = "true"`.
3. Send a push to the Worker URL for a mapped repo. Hub consumes, mirrors the ref, writes `echo_ledger`.
4. Send the reverse event (same SHA, other side’s repo URL). Hub drops it as an echo and commits.
5. Send a non-fast-forward trunk update. Hub records a `sync_conflicts` row under the pair’s trunk policy.
6. Stop the Worker (`ENABLED = "false"` and deploy, or `npx wrangler delete`). Hub keeps consuming whatever is already on the topic.

---

## 9. Test brokers

Corporate production uses the upstream team’s Kafka. The topic name and record shape stay the same; only bootstrap and security env change. Git events are small JSON, so any of the options below can carry a pair’s webhook tests.

Default for day-to-day work is a **local broker**. It costs nothing, needs no account, and matches the client Hub will use (`PLAINTEXT` on localhost).

| Option | Cost | Fit for this plan |
| :--- | :--- | :--- |
| **Local Kafka or Redpanda in Docker** | $0, no signup | Preferred Kafka test. Single node, `localhost:9092`, `PLAINTEXT`. Create whatever `GIT_WEBHOOK_KAFKA_INCREMENTAL_TOPIC` and dead-letter topic you set. One partition is enough locally |
| **Local RabbitMQ** | $0, no signup | Preferred Rabbit test. Set `GIT_WEBHOOK_BUS_PROVIDER=rabbitmq` and `GIT_WEBHOOK_RABBITMQ_ADDRESSES`. Queue name is the env value, not a hardcoded incremental queue |
| **Confluent Cloud Basic** | First eCKU $0, then $0.14/eCKU-hour. New org gets **$400** credit. Ingress/egress **$0.05/GB**, storage **$0.08/GB-month** after credit. Payment method required once the promotion ends | Closest hosted stand-in for a real cluster (`SASL_SSL` + API key). See [pricing](https://www.confluent.io/confluent-cloud/pricing/) and [billing](https://docs.confluent.io/cloud/current/billing/overview.html) (checked 2026-09-24) |
| **CloudKarafka free** | $0 shared cluster. Max **5 topics**, **10 MB per topic**, **28-day** retention. Certificate auth, not SASL/PLAIN | Enough for a few git-event smoke tests. Shared with other tenants, so it is a poor soak or multi-pod test. [Plans](https://www.cloudkarafka.com/plans.html) |
| **Redpanda Serverless** | Kafka-compatible API. **14-day** trial and **$100** credit, no card to start. Not an ongoing free tier | Useful if you want a hosted endpoint without Confluent. Same consumer code |

Upstash Kafka is not an option; their Kafka docs now 404 and the pricing page is Redis-only.

Local env:

```bash
export GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export GIT_WEBHOOK_KAFKA_SECURITY_PROTOCOL=PLAINTEXT
```

Confluent Cloud, when you want a hosted check:

1. Environment + **Basic** cluster in a nearby region.
2. One topic, named by `GIT_WEBHOOK_KAFKA_INCREMENTAL_TOPIC`. Failures stay in the database, not a second topic.
3. API key with produce and consume.
4. Bootstrap host and API key in the §7 env block. Do not commit the secret.

---

## 10. Build phases

### Phase 1 — Contract and config

- `GIT_WEBHOOK_BUS_PROVIDER` normalized to `kafka`, `rabbitmq`, or `off`.
- Env in §4 and §7 wired through `application.yml`. Topic, queue, group, and connection values are required to come from that config, not from Java constants.
- DTO for the normalized git event, shared by both providers.
- Fail-fast when `kafka` lacks bootstrap servers, when `rabbitmq` lacks an address, and when the webhook queue equals the messaging full-mirror queue.
- `GIT_MESSAGING_PROVIDER=kafka` still fails fast.

**Exit:** Hub boots with `none` + `off` exactly as today, and boots with `none` + `kafka` against local Docker Kafka using a non-default topic name (listener connected, no records yet). The same boot with `rabbitmq` and a non-default queue name connects and idles.

### Phase 2 — Consumer

- Listener on the configured Kafka topic and group, or the configured Rabbit queue. Kafka uses one thread per assigned partition.
- Resolve pair, echo filter, incremental `GitSyncEngine`, offset commit rules in §2 and §4.
- Poison records stored in `unmapped_webhook_events` and replayed onto the same topic.
- Busy-lease tail re-produce.

**Exit:** A record posted to the topic mirrors one ref. A second record for the echo SHA is skipped. A conflict lands in `sync_conflicts`. A crash before commit redelivers.

### Phase 3 — Test loader

- `webhook-worker-kafka/` Cloudflare script: HMAC, normalized git event, Confluent REST produce. Start and stop in its README.
- Documented env example. Not started by Hub.

**Exit:** The five-step test in §8 passes with `webhook-worker-kafka` deployed, and Hub still consumes after that Worker is stopped. The four-step fleet check in §5 passes with two pods in one consumer group.

### Phase 4 — Ops surface

- Messaging descriptor reports webhook-bus provider, the configured topic or queue, consumer group (Kafka), and lag.
- Queue view shows lag per partition and dead-letter depth. Redrive uses the same key.

**Exit:** An operator can see the group lag and redrive one poison record without a Rabbit Queue Manager.

---

## 11. Touchpoints

| Area | Files |
| :--- | :--- |
| Messaging switch | `MessagingEnvironmentPostProcessor`, `MessagingModule`, `application.yml` |
| New bus | `WebhookEventBus` beside `SyncEventBus`; Kafka listener under `messaging/kafka`; Rabbit listener under `messaging/rabbit` gated by the webhook provider, not by `GIT_MESSAGING_PROVIDER` |
| Ingest | New entry next to `WebhookIngestionService` / `InboundWebhookConsumerService` that accepts the normalized event instead of a raw SCM body |
| Echo | `DedupLedgerService`, `echo_ledger` |
| Conflicts | `GitSyncEngine`, `SyncConflictService`, `SyncPipelineState.CONFLICT_CHECK` |
| Exclusivity | `PairLeaseService`, `RepoDirLockService` |
| Fleet | `cluster_runtime` pause on every pod, `instance_heartbeats`, `GIT_UTILITY_INSTANCE_ID` |
| Execution today | `QueueConsumerService` incremental branch — reuse, do not fork a second mirror engine |
| Edge Rabbit worker | `webhook-worker/` unchanged except `ENABLED` stop switch |
| Kafka edge worker | `webhook-worker-kafka/` |

---

## 12. Non-goals

- Moving full mirrors, bulk mirror, or resume onto Kafka.
- Implementing [`kafka-mirroring-partitions.md`](kafka-mirroring-partitions.md) (`git.sync.full`, co-partition assignor, inbound topic `git.sync.inbound`).
- Replacing the Cloudflare `webhook-worker`. It stays for current setups while `GIT_WEBHOOK_BUS_PROVIDER=off`.
- Embedding a publisher in Hub. Organizations that already publish to the lane never deploy `webhook-worker-kafka`.
- A second echo or conflict implementation inside the Kafka or Rabbit client.
- Hardcoded topic, queue, or consumer-group names in code. Defaults live in `application.yml` and are overridden by env.
- Using Confluent Cloud as the corporate production broker. It is the test cluster for this plan.

---

## 13. How this relates to the other Kafka note

[`kafka-mirroring-partitions.md`](kafka-mirroring-partitions.md) is the design for a future fleet where **full and incremental** jobs both live on Kafka, with a co-partition assignor and an inbound topic the edge publishes to.

This plan is the slice we will build first:

- consume **one incremental lane**, Kafka topic or Rabbit queue, both names from env
- `GIT_WEBHOOK_BUS_PROVIDER` is `kafka` or `rabbitmq` (or `off`)
- publisher is that organization’s upstream (`webhook-worker-kafka` stands in for tests)
- full sync stays on `GIT_MESSAGING_PROVIDER`

If operator mirrors later move to Kafka, that work follows `kafka-mirroring-partitions.md` and must not redo this consumer contract.
