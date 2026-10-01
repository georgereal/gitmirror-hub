# Kafka webhook setup (Confluent Cloud)

This is the runbook for continuous incremental sync on Kafka. Full mirrors stay on `GIT_MESSAGING_PROVIDER` (`none` or `rabbitmq`). The webhook bus is a second switch, `GIT_WEBHOOK_BUS_PROVIDER=kafka`.

Two processes talk to the same Confluent Cloud cluster:

| Process | How it reaches Kafka | What it does |
| :--- | :--- | :--- |
| `webhook-worker-kafka` (Cloudflare) | HTTPS to the cluster REST endpoint | Checks the SCM HMAC and produces one normalized git event |
| Hub | Kafka protocol: `SASL_SSL` + API key (default), or `SSL` + PEM mTLS | Consumes that topic (JSON or Avro codec) and runs incremental sync |

Cloudflare cannot open the broker port (`:9092`). The Worker uses the Confluent REST records API. Hub uses the bootstrap server. Both use the same cluster API key.

If an upstream system already writes this JSON (or Avro) to the topic, skip the Worker (sections 4–6) and start at section 7. Optional Hub modes: event adapters (section 12), Avro codec (section 13), mutual TLS (section 14).

The Rabbit Cloudflare worker (`webhook-worker/`) and this Kafka worker are two ways to accept the same Git webhooks. Run one of them at a time. Section 8 is the switch: stop the worker you are leaving, start the one you are entering, then point the Git webhook at the one that is running.

Design notes live in [`future-work/kafka-incremental-upstream-sync.md`](future-work/kafka-incremental-upstream-sync.md). Worker commands also live in [`webhook-worker-kafka/README.md`](webhook-worker-kafka/README.md). Event adapters, value codec, and mutual TLS: [`KAFKA_EVENT_FORMATS.md`](KAFKA_EVENT_FORMATS.md). Partition keys: [`KAFKA_PARTITION_ORDERING.md`](KAFKA_PARTITION_ORDERING.md). Avro + mTLS tracking: [`future-work/kafka-avro-mtls.md`](future-work/kafka-avro-mtls.md).

---

## 1. What you need first

- A Confluent Cloud account. Basic is the cluster type this guide uses. It is not an unlimited free tier: the first eCKU is included, and ingress and storage are still metered.
- Node 22+ for Wrangler (`nvm use 22`).
- A mapped mirror pair in Hub for the repository you will push. An event for a repo with no active pair is stored as `UNMAPPED_REPOSITORY` and acknowledged. It does not mirror.
- `GIT_MESSAGING_PROVIDER` left at `none` or `rabbitmq`. `GIT_MESSAGING_PROVIDER=kafka` fails startup. That value is reserved for a later full-mirror cutover.

---

## 2. Create the Confluent Cloud cluster

1. Sign in at [https://confluent.cloud](https://confluent.cloud).
2. Open your environment (the default environment is enough).
3. Create a cluster. Choose **Basic** and a region close to the machine that will run Hub.
4. When the cluster is ready, open **Cluster settings** (or **Cluster overview**) and copy these four values. You will paste them into the Worker and into Hub.

| Confluent label | Example | Used by |
| :--- | :--- | :--- |
| Bootstrap server | `pkc-xxxxx.us-east-1.aws.confluent.cloud:9092` | Hub `GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS` |
| REST endpoint | `https://pkc-xxxxx.us-east-1.aws.confluent.cloud` | Worker `KAFKA_REST_ENDPOINT` (no path, no trailing slash) |
| Cluster ID | `lkc-xxxxx` | Worker `KAFKA_CLUSTER_ID` |
| Environment / cluster name | anything you chose | not read by Hub |

The REST host and the bootstrap host are the same name. REST is `https://` with no port. Bootstrap is that host plus `:9092`.

---

## 3. API key and topics

### API key

On the cluster: **API keys → Add key**.

For a first setup, create one key with access to this cluster (Global access on the cluster is the simple choice). Download the key and secret once. Confluent shows the secret only at creation.

That one key is used in two places:

- Worker secrets `KAFKA_API_KEY` and `KAFKA_API_SECRET` (HTTP Basic auth on the REST produce call).
- Hub `GIT_WEBHOOK_KAFKA_SASL_USERNAME` and `GIT_WEBHOOK_KAFKA_SASL_PASSWORD`.

### Topic

Create one topic in the Confluent UI (**Topics → Add topic**) before you start Hub. Confluent Cloud sets replication to 3. You do not choose it.

The name is yours. `git.sync.incremental` is only the default when `KAFKA_TOPIC` and `GIT_WEBHOOK_KAFKA_INCREMENTAL_TOPIC` are unset. Both of those must be the same string. Hub does not create a second Kafka topic for failures. A record that cannot be applied is stored in `unmapped_webhook_events` (`discardReason` `KAFKA_POISON`).

`6` partitions is enough for a small test. `32` matches the Hub default. Set `GIT_WEBHOOK_KAFKA_INCREMENTAL_PARTITIONS` to the count you created so Hub does not ask the broker to grow the topic on startup.

Also set `GIT_WEBHOOK_KAFKA_REPLICATION_FACTOR=3`. If a topic is missing, Hub tries to create it, and Confluent Cloud rejects a replication factor of 1.

---

## 4. Point the Worker at the cluster

```bash
cd webhook-worker-kafka
nvm use 22
npm install
npx wrangler login
npx wrangler whoami
```

`wrangler.toml` only names the script. The cluster values go in a gitignored env file:

```bash
cp .env.example .env
```

```text
ENABLED=true
KAFKA_REST_ENDPOINT=https://pkc-xxxxx.us-east-1.aws.confluent.cloud
KAFKA_CLUSTER_ID=lkc-xxxxx
KAFKA_TOPIC=git.sync.incremental
KAFKA_API_KEY=<cluster api key>
KAFKA_API_SECRET=<cluster api secret>
WEBHOOK_SECRET=<same string you will set on the GitHub webhook>
```

`KAFKA_TOPIC` must equal Hub `GIT_WEBHOOK_KAFKA_INCREMENTAL_TOPIC`. Optional lines, left commented in `.env.example`, are `WEBHOOK_SECRETS_JSON` and `BITBUCKET_WEBHOOK_SECRET`.

`workers_dev` stays in `wrangler.toml`. Leave it `false` until you want a public `*.workers.dev` URL. The first `npm run deploy` with `workers_dev = true` publishes that URL. `npm run deploy` uploads every non-empty `.env` line as a Cloudflare secret.

---

## 5. Start the Worker

Local preview and the deployed Worker are different. Stopping the local process does not stop the URL on Cloudflare.

### Local preview

`npm run dev` reads `webhook-worker-kafka/.env`. Do not also keep a `.dev.vars` file in that directory; Wrangler would use it instead of `.env`.

```bash
cd webhook-worker-kafka
npm run dev
curl http://localhost:8787/health
```

Stop it with Ctrl+C in that terminal.

A healthy local response looks like:

```json
{
  "status": "healthy",
  "service": "gitmirror-webhook-worker-kafka",
  "enabled": true,
  "topic": "git.sync.incremental",
  "kafkaConfigured": true
}
```

`kafkaConfigured` is false when the REST endpoint, cluster id, or API key is missing.

### Deployed Worker

```bash
cd webhook-worker-kafka
npm run deploy
npx wrangler tail
```

`npm run deploy` prints the public host once, at the end, in the triggers block:

```text
Deployed gitmirror-webhook-worker-kafka triggers
  https://gitmirror-webhook-worker-kafka.<subdomain>.workers.dev
```

That host does not change on later deploys. It is always:

```text
https://<name in wrangler.toml>.<subdomain>.workers.dev
```

`<subdomain>` is your Cloudflare `workers.dev` subdomain, not the account id from `wrangler whoami`. `workers_dev` in `wrangler.toml` must be `true` or there is no public host.

| Worker | `name` in `wrangler.toml` | GitHub payload URL |
| :--- | :--- | :--- |
| Kafka | `gitmirror-webhook-worker-kafka` | `https://gitmirror-webhook-worker-kafka.<subdomain>.workers.dev/webhook/github` |
| Rabbit | `gitmirror-webhook-worker` | `https://gitmirror-webhook-worker.<subdomain>.workers.dev/webhook/github` |

To read the host again after the Worker is already running, open **Workers & Pages** in the Cloudflare dashboard and select the Worker. The `*.workers.dev` host is on that page. The same host is what `npm run deploy` printed the first time.

`npm run dev` is only `http://localhost:8787`. GitHub cannot call that.

### Status of a Worker that is already deployed

Health is the Worker itself. From any machine:

```bash
curl https://gitmirror-webhook-worker-kafka.<subdomain>.workers.dev/health
```

| Health JSON | Meaning |
| :--- | :--- |
| `"status": "healthy"`, `"enabled": true` | Accepting webhooks |
| `"status": "stopped"`, `"enabled": false` | Still deployed. `POST` returns 503 and does not publish. `ENABLED=false` in `.env` |
| `"kafkaConfigured": true` | REST endpoint, cluster id, and API key are set |
| `"kafkaConfigured": false` | One of those three is empty in `.env` |
| Connection fails | `workers_dev` is `false`, or the script was deleted |

The Rabbit worker’s `/health` uses the same `status` and `enabled` fields. It reports `rabbitmqConfigured` instead of `kafkaConfigured`.

From the worker directory, this shows that Cloudflare still has a deployment, including when `ENABLED=false`:

```bash
cd webhook-worker-kafka
npx wrangler deployments status
```

`npx wrangler tail` in that directory streams live requests. Use the Rabbit directory for the other Worker.

The same script also accepts:

| SCM | Path |
| :--- | :--- |
| GitHub | `/webhook/github` or `/api/v1/webhooks/github` |
| GitHub Enterprise Server | `/webhook/ghes` or `/api/v1/webhooks/ghes` |
| GitLab | `/webhook/gitlab` or `/api/v1/webhooks/gitlab` |
| Bitbucket | `/webhook/bitbucket` or `/api/v1/webhooks/bitbucket` |

A path segment `/credential/{id}` selects the matching entry in `WEBHOOK_SECRETS_JSON`. Otherwise the Worker uses `WEBHOOK_SECRET`.

GitHub `ping` returns `200`. Anything other than `push` returns `200` with `ignored` and is not produced. A `push` that passes HMAC returns `202` and one Kafka record.

---

## 6. Stop the Worker

### Soft stop (secrets stay)

1. In `webhook-worker-kafka/.env` set `ENABLED=false`.
2. `npm run deploy`.

`GET /health` returns `"status": "stopped"`. `POST /webhook/...` returns **503** and does not produce. The GitHub webhook URL can stay configured.

Start again: set `ENABLED=true` in `.env` and `npm run deploy`.

### Hide the public URL (secrets stay)

Set `workers_dev = false` and `npm run deploy`. Set it back to `true` and deploy to open the URL.

### Delete the script (secrets are removed)

```bash
cd webhook-worker-kafka
npx wrangler delete
```

Confirm the name `gitmirror-webhook-worker-kafka`. The next deploy uploads `.env` again.

---

## 7. Configure Hub

Export these in the shell that starts the backend. With `GIT_MESSAGING_PROVIDER=none`, full mirrors stay in-process and the Kafka listener is the only incremental consumer.

```bash
export GIT_MESSAGING_PROVIDER=none
export GIT_WEBHOOK_BUS_PROVIDER=kafka

export GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS="pkc-xxxxx.us-east-1.aws.confluent.cloud:9092"
export GIT_WEBHOOK_KAFKA_SECURITY_PROTOCOL=SASL_SSL
export GIT_WEBHOOK_KAFKA_SASL_MECHANISM=PLAIN
export GIT_WEBHOOK_KAFKA_SASL_USERNAME="<api key>"
export GIT_WEBHOOK_KAFKA_SASL_PASSWORD="<api secret>"

export GIT_WEBHOOK_KAFKA_INCREMENTAL_TOPIC=git.sync.incremental
export GIT_WEBHOOK_KAFKA_GROUP_ID=git-mirror-hub
export GIT_WEBHOOK_KAFKA_INCREMENTAL_PARTITIONS=6
export GIT_WEBHOOK_KAFKA_REPLICATION_FACTOR=3
```

Optional Hub modes (formats directory, Avro codec, mutual TLS) are in sections 12–14. Leave them unset for JSON values over this SASL path.

`GIT_WEBHOOK_KAFKA_INCREMENTAL_PARTITIONS` must match the topic you created in section 3.

Startup fails when the bus is `kafka` and the bootstrap servers are empty, or when the protocol is `SASL_SSL` and the API key is empty.

Start Hub the usual way (`INSTRUCTIONS.md`). Then:

```bash
curl -s http://localhost:8080/api/v1/webhook-bus
```

`provider` is `kafka`, `destination` is `git.sync.incremental`, and `paused` is false. The Queue page shows the same strip when the bus is not `off`.

`GIT_QUEUE_PAUSE_ON_STARTUP` defaults to off when messaging is `none`. If the Queue page shows consumers paused, resume them. A paused listener stays in the consumer group and does not poll.

### Core Hub settings (SASL / topic)

| Env | Confluent value | Default if unset |
| :--- | :--- | :--- |
| `GIT_WEBHOOK_BUS_PROVIDER` | `kafka` | `off` (bus does not start) |
| `GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS` | bootstrap server, including `:9092` | required |
| `GIT_WEBHOOK_KAFKA_SECURITY_PROTOCOL` | `SASL_SSL` | `PLAINTEXT` (local broker only) |
| `GIT_WEBHOOK_KAFKA_SASL_MECHANISM` | `PLAIN` | `PLAIN` |
| `GIT_WEBHOOK_KAFKA_SASL_USERNAME` | API key | required for `SASL_SSL` |
| `GIT_WEBHOOK_KAFKA_SASL_PASSWORD` | API secret | required for `SASL_SSL` |
| `GIT_WEBHOOK_KAFKA_INCREMENTAL_TOPIC` | topic you created. Worker `KAFKA_TOPIC` must match | `git.sync.incremental` |
| `GIT_WEBHOOK_KAFKA_GROUP_ID` | one group for this Hub deployment | `git-mirror-hub` |
| `GIT_WEBHOOK_KAFKA_CLIENT_ID` | optional | `git-mirror-hub` |
| `GIT_WEBHOOK_KAFKA_AUTO_OFFSET_RESET` | `earliest` on a new group | `earliest` |
| `GIT_WEBHOOK_KAFKA_INCREMENTAL_PARTITIONS` | same count as the Confluent topics | `32` |
| `GIT_WEBHOOK_KAFKA_REPLICATION_FACTOR` | `3` on Confluent Cloud | `1` (local Docker) |
| `GIT_WEBHOOK_KAFKA_LISTENER_CONCURRENCY` | threads per pod, up to the partition count | `4` |
| `GIT_WEBHOOK_KAFKA_MAX_POLL_INTERVAL_MS` | longer than the longest incremental | `600000` |
| `GIT_WEBHOOK_KAFKA_SESSION_TIMEOUT_MS` | how fast the group drops a dead pod | `45000` |

Give each environment its own `GIT_WEBHOOK_KAFKA_GROUP_ID` when several Hub deployments read one cluster. Sharing a group splits the partitions across those deployments.

---

## 8. Switch the Git webhook from one worker to the other

Edit the webhook that already exists. Do not add a second webhook for the same events. GitHub delivers each webhook only to the payload URL saved on it. Stopping the worker you are leaving means that URL returns 503 if something still posts to it, and that worker does not publish into the other bus.

Do this order: create the secret, put it in the worker you are starting, stop the other worker, deploy the one you are starting, then save the Git webhook. The ping on save must hit a worker that already has that secret.

### Create the webhook secret

Use a new secret when you switch. The string in Git must be the same as `WEBHOOK_SECRET` in the `.env` of the worker that is running.

Mac or Linux:

```bash
openssl rand -hex 32
```

Windows PowerShell:

```powershell
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
-join ($bytes | ForEach-Object { $_.ToString("x2") })
```

Windows Git Bash uses the same `openssl rand -hex 32` command as Mac.

### Start Kafka, stop the Rabbit worker

1. Put the new secret in `webhook-worker-kafka/.env` as `WEBHOOK_SECRET`, with the Confluent values from section 4. `ENABLED=true`.
2. Stop the Rabbit worker:

```bash
cd webhook-worker
```

In `webhook-worker/.env` set `ENABLED=false`, then:

```bash
npm run deploy
curl https://gitmirror-webhook-worker.<account>.workers.dev/health
```

Health shows `"status": "stopped"`. `POST /webhook/...` returns **503** and does not publish to RabbitMQ.

3. Start the Kafka worker:

```bash
cd webhook-worker-kafka
npm run deploy
curl https://gitmirror-webhook-worker-kafka.<account>.workers.dev/health
```

Health shows `"enabled": true` and `"kafkaConfigured": true`.

4. Restart Hub with the Kafka bus env from section 7. `GIT_MESSAGING_PROVIDER` stays `none` or `rabbitmq`. `GIT_WEBHOOK_BUS_PROVIDER=kafka`. The topic in `GIT_WEBHOOK_KAFKA_INCREMENTAL_TOPIC` matches `KAFKA_TOPIC`.

5. Update Git (below) to the Kafka worker URL and the new secret.

### Start the Rabbit worker, stop Kafka

1. Put the secret you will save in Git into `webhook-worker/.env` as `WEBHOOK_SECRET`. `ENABLED=true`. The CloudAMQP host, vhost, user, and password stay in that file.
2. Stop the Kafka worker (section 6): `ENABLED=false` in `webhook-worker-kafka/.env`, then `npm run deploy` from `webhook-worker-kafka/`.
3. Start the Rabbit worker:

```bash
cd webhook-worker
npm run deploy
curl https://gitmirror-webhook-worker.<account>.workers.dev/health
```

4. Restart Hub with `GIT_WEBHOOK_BUS_PROVIDER=off` so it does not also consume the Kafka topic. The Rabbit worker publishes to the inbound queue, and Hub reads that queue only when `GIT_MESSAGING_PROVIDER=rabbitmq`.

5. Update Git (below) to the Rabbit worker URL and the secret in `webhook-worker/.env`.

### Env for each side

Kafka worker `webhook-worker-kafka/.env`:

| Name | Value |
| :--- | :--- |
| `ENABLED` | `true` while this worker should publish. `false` to stop it |
| `KAFKA_REST_ENDPOINT` | Confluent REST endpoint, `https://`, no path |
| `KAFKA_CLUSTER_ID` | `lkc-...` |
| `KAFKA_TOPIC` | same string as Hub `GIT_WEBHOOK_KAFKA_INCREMENTAL_TOPIC` |
| `KAFKA_API_KEY` | cluster API key |
| `KAFKA_API_SECRET` | cluster API secret |
| `WEBHOOK_SECRET` | the secret you paste into Git |

Hub process env while the Kafka worker is the one Git calls (full list in section 7):

| Name | Value |
| :--- | :--- |
| `GIT_MESSAGING_PROVIDER` | `none` or `rabbitmq`. Not `kafka` |
| `GIT_WEBHOOK_BUS_PROVIDER` | `kafka` |
| `GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS` | bootstrap host with `:9092` |
| `GIT_WEBHOOK_KAFKA_SECURITY_PROTOCOL` | `SASL_SSL` |
| `GIT_WEBHOOK_KAFKA_SASL_MECHANISM` | `PLAIN` |
| `GIT_WEBHOOK_KAFKA_SASL_USERNAME` | same API key as `KAFKA_API_KEY` |
| `GIT_WEBHOOK_KAFKA_SASL_PASSWORD` | same API secret as `KAFKA_API_SECRET` |
| `GIT_WEBHOOK_KAFKA_INCREMENTAL_TOPIC` | same string as `KAFKA_TOPIC` |
| `GIT_WEBHOOK_KAFKA_GROUP_ID` | `git-mirror-hub` unless this deployment must not share a group |
| `GIT_WEBHOOK_KAFKA_INCREMENTAL_PARTITIONS` | partition count of that topic |
| `GIT_WEBHOOK_KAFKA_REPLICATION_FACTOR` | `3` on Confluent Cloud |

Hub process env while the Rabbit worker is the one Git calls:

| Name | Value |
| :--- | :--- |
| `GIT_WEBHOOK_BUS_PROVIDER` | `off` |
| `GIT_MESSAGING_PROVIDER` | `rabbitmq` |

Rabbit worker `webhook-worker/.env` keeps `RABBITMQ_HTTP_URL`, `RABBITMQ_VHOST`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `RABBITMQ_EXCHANGE`, `RABBITMQ_ROUTING_KEY`, and `WEBHOOK_SECRET`.

### Update the webhook in Git

On a repository webhook: **Settings → Webhooks →** the existing webhook → **Edit**. On a GitHub App: **Developer settings → GitHub Apps →** your app → **Edit** the webhook URL and secret.

| Field | Kafka worker | Rabbit worker |
| :--- | :--- | :--- |
| Payload URL | `https://gitmirror-webhook-worker-kafka.<account>.workers.dev/webhook/github` | `https://gitmirror-webhook-worker.<account>.workers.dev/webhook/github` |
| Content type | `application/json` | `application/json` |
| Secret | `WEBHOOK_SECRET` from `webhook-worker-kafka/.env` | `WEBHOOK_SECRET` from `webhook-worker/.env` |
| SSL verification | Enable | Enable |
| Events | Just the push event | The events that worker already handled |

Save. The ping delivery should be `200`. A following push delivery should be `202` from the Kafka worker.

GitLab sends the same secret in the `X-Gitlab-Token` header. Bitbucket uses `BITBUCKET_WEBHOOK_SECRET` in that worker's `.env` when the line is set, otherwise `WEBHOOK_SECRET`. A path segment `/credential/{id}` selects the matching entry in `WEBHOOK_SECRETS_JSON`.

---

## 9. Check one push

1. Push a commit to a branch the pair is configured to follow.
2. `npx wrangler tail` shows a produce, or the GitHub webhook delivery shows `202`.
3. In Confluent, the topic you named has a new record. The key is the repo URL with the scheme and `.git` removed, lowercased (`github.com/org/repo`). The value is:

```json
{
  "provider": "github",
  "repoUrl": "https://github.com/org/repo.git",
  "ref": "refs/heads/main",
  "beforeSha": "abc",
  "afterSha": "def",
  "deliveryId": "github-delivery-id",
  "eventType": "push",
  "receivedAt": "2026-09-24T12:00:00.000Z"
}
```

A branch delete uses `eventType: "delete"` and an all-zero `afterSha`.

4. Hub logs an incremental sync for that pair, or a discard such as `LOOP_DETECTED_SYSTEM_ECHO` when the commit was one Hub itself pushed. `GET /api/v1/webhook-bus` lag drops back after the record is handled.

A record Hub cannot apply is stored in `unmapped_webhook_events` with `discardReason` `KAFKA_POISON`, including the original JSON. Replay those rows from the Queue page, or:

```bash
curl -X POST "http://localhost:8080/api/v1/webhook-bus/redrive?limit=20"
```

To re-read records still on the topic (not only DB dead-letters), seek the consumer group (section 15).

---

## 10. More than one Hub pod

Every pod in one deployment uses the same bootstrap, topic, and `GIT_WEBHOOK_KAFKA_GROUP_ID`. Kafka assigns partitions. Set a distinct `GIT_UTILITY_INSTANCE_ID` on each pod. The two sides of one pair can land on different pods; `pair_leases` and `echo_ledger` in the shared database are what keep that pair safe. See [`INSTRUCTIONS-MULTI-POD.md`](INSTRUCTIONS-MULTI-POD.md) for the local two-JVM layout.

---

## 11. Common failures

| What you see | Fix |
| :--- | :--- |
| Hub exits: bootstrap servers required | `GIT_WEBHOOK_BUS_PROVIDER=kafka` without `GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS` and without an enabled sources-file entry |
| Hub exits: sources file exactly one | Zero or more than one `enabled: true` in `GIT_WEBHOOK_KAFKA_SOURCES_FILE` |
| Offset seek 409 / alter rejected | Another Hub pod still in the same consumer group; stop siblings, then seek again |
| Hub exits: SASL requires username | `SASL_SSL` without the API key and secret |
| Hub exits: Schema Registry URL | `GIT_WEBHOOK_KAFKA_VALUE_CODEC=avro` without `GIT_WEBHOOK_KAFKA_SCHEMA_REGISTRY_URL` |
| Hub exits: SSL truststore location | `SECURITY_PROTOCOL=SSL` without trust/keystore PEM paths, or a path that is not a readable file |
| Hub exits: SSL_KEY_PASSWORD | Keystore/key PEM has `BEGIN ENCRYPTED PRIVATE KEY` but `GIT_WEBHOOK_KAFKA_SSL_KEY_PASSWORD` is empty |
| Hub exits: `GIT_MESSAGING_PROVIDER=kafka` | Use `none` or `rabbitmq` for full mirrors. Kafka on the webhook bus is `GIT_WEBHOOK_BUS_PROVIDER` |
| Topic create rejected, replication factor | Create the topic in the UI and set `GIT_WEBHOOK_KAFKA_REPLICATION_FACTOR=3` |
| Both workers publish the same push | A second Git webhook still points at the worker you meant to stop, or that worker's `ENABLED` is still `true`. Stop it with section 8 and keep a single webhook |
| Worker `401` | `WEBHOOK_SECRET` does not match the GitHub webhook secret |
| Worker `500` and REST `401` or `403` | API key cannot produce to that topic, or the key is a Cloud control-plane key rather than a cluster API key |
| Worker `kafkaConfigured: false` | `KAFKA_REST_ENDPOINT`, `KAFKA_CLUSTER_ID`, or `KAFKA_API_KEY` is empty in `.env` |
| Record sits on the topic | Consumers paused on the Queue page, or Hub is not running with `GIT_WEBHOOK_BUS_PROVIDER=kafka` |
| Event discarded `UNMAPPED_REPOSITORY` | The `repoUrl` does not match an active mirror pair |
| Second Hub deployment duplicates work | It joined the same `GIT_WEBHOOK_KAFKA_GROUP_ID`. Use a different group per deployment |

---

## 12. Event format adapters

Full write-up: [`KAFKA_EVENT_FORMATS.md`](KAFKA_EVENT_FORMATS.md). Operator summary in [`INSTRUCTIONS.md`](INSTRUCTIONS.md) (Kafka Hub: event format adapters).

Hub consumes the incremental topic and adapts each record after it arrives. The mirror path only sees `IncrementalGitEvent`. Hub does not publish a converted copy back to Kafka. A busy lease or a failed attempt leaves the offset uncommitted so the broker redelivers the original bytes. After the retry budget, the raw record is stored on the dead-letter list in the database.

`normalized-v1` is always available. It is today's flat event (`push`, `create`, `delete`, `pull_request`, `release`, `status`, `check_run`).

| Env | Default | Role |
| :--- | :--- | :--- |
| `GIT_WEBHOOK_EVENT_FORMATS_DIR` | empty | Directory of `*.json` mapping files. Unset keeps only `normalized-v1`. |

Another shape is a JSON file in that directory. The file `id` is the schema version. `when` selects records that omit a version. `fields` are JSON Pointers onto the canonical event. `eventTypeMap` renames the producer kind onto Hub's type names. Worked files are in `kafka-event-format/examples/formats/`.

Put the version on the record when both shapes share the topic:

```text
schemaVersion: enriched-git-v1
```

That can be a Kafka header (`schemaVersion` or `schema-version`) or a top-level JSON field. If the header is absent, Hub uses the adapter whose `when` matches. A record that matches two adapters is stored as unreadable until the producer sets `schemaVersion`.

Check a sample before restart:

```bash
cd kafka-event-format
node src/cli.js check --format enriched --formats ./formats --samples ./samples
```

Then point Hub at that directory and restart:

```bash
export GIT_WEBHOOK_EVENT_FORMATS_DIR=/path/to/formats
```

On Queues → Incremental events, each row shows the event type, the adapter id, and the source Kafka message (capped at 16,000 characters) for records Hub processed. Skipped and dead-letter rows show the same when the payload was kept.

A pointer file renames and nests fields. It does not add a git operation Hub does not already run.

---

## 13. Value codec (`json` | `avro`)

Independent of section 12 and of section 14. Default is UTF-8 JSON (today's path). Full write-up: [`KAFKA_EVENT_FORMATS.md`](KAFKA_EVENT_FORMATS.md). Operator summary in [`INSTRUCTIONS.md`](INSTRUCTIONS.md) (Kafka Hub: value codec).

| Env | Default | Role |
| :--- | :--- | :--- |
| `GIT_WEBHOOK_KAFKA_VALUE_CODEC` | `json` | `json` or `avro` |
| `GIT_WEBHOOK_KAFKA_SCHEMA_REGISTRY_URL` | empty | Required when codec is `avro` |
| `GIT_WEBHOOK_KAFKA_SCHEMA_REGISTRY_USERNAME` | empty | Optional registry basic auth |
| `GIT_WEBHOOK_KAFKA_SCHEMA_REGISTRY_PASSWORD` | empty | Optional registry basic auth |

```bash
export GIT_WEBHOOK_KAFKA_VALUE_CODEC=avro
export GIT_WEBHOOK_KAFKA_SCHEMA_REGISTRY_URL=https://schema-registry.example:8081
# export GIT_WEBHOOK_KAFKA_SCHEMA_REGISTRY_USERNAME=
# export GIT_WEBHOOK_KAFKA_SCHEMA_REGISTRY_PASSWORD=
```

Hub deserializes Confluent-framed Avro through the Schema Registry, converts the record to JSON, then runs the same adapters as section 12. `sourceMessage` on Queues is that JSON projection (capped). Startup fails when codec is `avro` and the registry URL is empty.

Compose with SASL (section 7) or with mutual TLS (section 14). When TLS PEMs are set, Hub applies the same certificates to the Schema Registry client.

---

## 14. Mutual TLS (PEM)

Independent of section 12 and of section 13. Confluent Cloud usually stays on `SASL_SSL` + API key (section 7). Use this section for brokers that require client certificates. Operator summary in [`INSTRUCTIONS.md`](INSTRUCTIONS.md) (Kafka Hub: mutual TLS).

| Env | Default | Role |
| :--- | :--- | :--- |
| `GIT_WEBHOOK_KAFKA_SECURITY_PROTOCOL` | `PLAINTEXT` | Set to `SSL` for mutual TLS |
| `GIT_WEBHOOK_KAFKA_SSL_TRUSTSTORE_LOCATION` | empty | CA / trust PEM. Required for `SSL` |
| `GIT_WEBHOOK_KAFKA_SSL_KEYSTORE_LOCATION` | empty | Client cert PEM, or combined cert + key PEM. Required for `SSL` |
| `GIT_WEBHOOK_KAFKA_SSL_KEY_LOCATION` | empty | Private key PEM. Optional when the keystore already contains the key |
| `GIT_WEBHOOK_KAFKA_SSL_KEY_PASSWORD` | empty | Passphrase for an encrypted private key. Required when the key is encrypted |

```bash
export GIT_WEBHOOK_KAFKA_SECURITY_PROTOCOL=SSL
export GIT_WEBHOOK_KAFKA_SSL_TRUSTSTORE_LOCATION=/path/ca.pem
export GIT_WEBHOOK_KAFKA_SSL_KEYSTORE_LOCATION=/path/client.pem
export GIT_WEBHOOK_KAFKA_SSL_KEY_PASSWORD='<pem passphrase>'
```

Truststore and keystore must be readable at Hub startup. A combined client PEM (certificate block + `BEGIN ENCRYPTED PRIVATE KEY`) is supported; set the passphrase via `GIT_WEBHOOK_KAFKA_SSL_KEY_PASSWORD`. Hub loads the PEM contents into Kafka's PEM truststore / keystore properties (and `ssl.key.password`). Partition keys and adapters are unchanged; only the TCP/TLS handshake changes.

For `GitWebhookService` envelopes (`payload` as a JSON string), use the adapter in `kafka-event-format/examples/formats/30-git-webhook-service-v1.json` and set `GIT_WEBHOOK_EVENT_FORMATS_DIR` (section 12). Hub unwraps stringified `payload` before adapters run.

Tracking doc: [`future-work/kafka-avro-mtls.md`](future-work/kafka-avro-mtls.md).

---

## 15. Offset seek / replay

Moves the Hub consumer group’s committed offsets so the incremental listener re-reads topic records. Use this when the topic is quiet and you need to inspect formats again. Distinct from section 9 dead-letter redrive (DB rows only).

Queues → Kafka panel: **Rewind**, **To earliest**, **To latest**. Or:

```bash
# Re-read the last ~50 committed records per partition
curl -X POST "http://localhost:8080/api/v1/webhook-bus/offsets/seek" \
  -H "Content-Type: application/json" \
  -d '{"mode":"rewind","rewindBy":50}'

# Optional: one partition only
curl -X POST "http://localhost:8080/api/v1/webhook-bus/offsets/seek" \
  -H "Content-Type: application/json" \
  -d '{"mode":"earliest","partition":0}'
```

| `mode` | Effect |
| :--- | :--- |
| `rewind` | Each partition → `max(log-start, committed - rewindBy)` (`rewindBy` default 50, max 10000) |
| `earliest` | Each partition → log-start |
| `latest` | Each partition → log-end (skips pending backlog for this group) |

Hub stops the incremental listener, calls `alterConsumerGroupOffsets`, then starts the listener again. If other pods share `GIT_WEBHOOK_KAFKA_GROUP_ID`, stop them first or the broker rejects the alter.

---

## 16. Named sources (single active)

Declare several clusters in one file. Exactly one source must have `enabled: true`. Hub loads that source at startup onto the flat Kafka settings. Flip by editing the file and restarting Hub. Example: [`env.kafka-sources.example.yml`](env.kafka-sources.example.yml).

| Env | Default | Role |
| :--- | :--- | :--- |
| `GIT_WEBHOOK_KAFKA_SOURCES_FILE` | empty | Path to JSON or YAML sources file |
| `GIT_WEBHOOK_KAFKA_SOURCE_ID` | empty | Optional label when not using a sources file |

```bash
export GIT_WEBHOOK_KAFKA_SOURCES_FILE=/path/to/kafka-sources.yml
```

Per-source fields override flat env for bootstrap, topic, group, security protocol, SASL, SSL paths, and value codec / schema registry when set. Formats directory (`GIT_WEBHOOK_EVENT_FORMATS_DIR`) stays process-wide. Status and the Queues Kafka panel show `sourceId` and bootstrap for the active source. Concurrent multi-broker consumers in one JVM are out of scope.
