# Kafka webhook worker (Cloudflare)

Confluent Cloud cluster, topics, API key, Worker start/stop, and Hub env are in [`INSTRUCTIONS-KAFKA-WEBHOOK.md`](../INSTRUCTIONS-KAFKA-WEBHOOK.md).

Separate Cloudflare Worker from [`webhook-worker/`](../webhook-worker/README.md). That one publishes raw envelopes to RabbitMQ. This one accepts the same SCM webhook paths, checks HMAC, and produces a **normalized git event** to Kafka through the Confluent REST records API.

Hub does not start this Worker. Leave it undeployed until you want Kafka ingest. Corporate upstreams can publish the same JSON themselves and never deploy this Worker.

```text
GitHub / GitLab / Bitbucket
        │ POST /webhook/github
        ▼
gitmirror-webhook-worker-kafka   (this Worker, only when deployed and ENABLED=true)
        │ HTTPS produce, key = repo URL
        ▼
Kafka topic from KAFKA_TOPIC (default git.sync.incremental)
        ▼
Hub  GIT_WEBHOOK_BUS_PROVIDER=kafka
```

Cloudflare cannot open a Kafka broker port. Produce goes to the cluster **REST endpoint** (the `https://pkc-….confluent.cloud` host on the cluster overview), which is the same HTTP style as the Rabbit worker’s management API.

## Record

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

A delete uses `eventType: "delete"` and an all-zero `afterSha`. The record key is the repo URL with the scheme and `.git` stripped, so one repo stays on one partition.

## One-time setup

```bash
cd webhook-worker-kafka
npm install
npx wrangler login
npx wrangler whoami
```

Copy the gitignored env file and fill in the Confluent REST endpoint, cluster id, topic, API key, and webhook HMAC. `wrangler.toml` does not hold those values.

```bash
cp .env.example .env
```

Point Hub at the same cluster:

```bash
export GIT_MESSAGING_PROVIDER=none
export GIT_WEBHOOK_BUS_PROVIDER=kafka
export GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS="pkc-xxxxx.region.aws.confluent.cloud:9092"
export GIT_WEBHOOK_KAFKA_SECURITY_PROTOCOL=SASL_SSL
export GIT_WEBHOOK_KAFKA_SASL_USERNAME="<same api key>"
export GIT_WEBHOOK_KAFKA_SASL_PASSWORD="<same api secret>"
export GIT_WEBHOOK_KAFKA_INCREMENTAL_TOPIC=git.sync.incremental
```

The topic name in `KAFKA_TOPIC` and `GIT_WEBHOOK_KAFKA_INCREMENTAL_TOPIC` must match.

## Start and stop

Local `wrangler dev` and the deployed Worker are different. Stopping the local process does not stop the URL on Cloudflare.

### Local preview

```bash
cd webhook-worker-kafka
npm run dev
```

Stop it with Ctrl+C in that terminal. Health: `curl http://localhost:8787/health`.

### Deployed Worker — start

```bash
cd webhook-worker-kafka
# .env has ENABLED=true. wrangler.toml has workers_dev = true
npm run deploy
curl https://gitmirror-webhook-worker-kafka.<account>.workers.dev/health
```

Logs: `npx wrangler tail`.

Webhook URL for GitHub: `https://gitmirror-webhook-worker-kafka.<account>.workers.dev/webhook/github`.

### Deployed Worker — stop, keep secrets

Use this when you want the Worker off but the next start should not ask for the API key again.

1. In `.env` set `ENABLED=false`.
2. `npm run deploy`.

`GET /health` returns `"status": "stopped"`. `POST /webhook/...` returns **503** and does not produce. GitHub can keep the webhook URL configured.

Start again: set `ENABLED=true` in `.env` and `npm run deploy`.

To take the public URL down as well, set `workers_dev = false` and deploy. Secrets remain. Set `workers_dev = true` and deploy to open the URL again.

### Deployed Worker — remove it

```bash
cd webhook-worker-kafka
npx wrangler delete
```

Confirm the name `gitmirror-webhook-worker-kafka`. This deletes the script **and its secrets**. The next `npm run deploy` uploads `.env` again.

The Rabbit worker in `webhook-worker/` is stopped the same way (`ENABLED` is not on that worker; use `workers_dev = false` plus deploy, or `npx wrangler delete` from that directory).
