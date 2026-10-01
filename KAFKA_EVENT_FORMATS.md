# Kafka event formats

How Hub turns an incremental Kafka record into `IncrementalGitEvent` after consume. Hub does not write a converted copy back to Kafka.

Three **independent** layers:

| Layer | Default | Opt-in |
| :--- | :--- | :--- |
| Connection | `PLAINTEXT` or `SASL_SSL` + API key | Mutual TLS PEMs (`SSL`) |
| Value codec | `json` (UTF-8 body) | `avro` + Schema Registry URL |
| Event adapters | `normalized-v1` | Mapping files in `GIT_WEBHOOK_EVENT_FORMATS_DIR` |

Cluster and worker setup: [`INSTRUCTIONS-KAFKA-WEBHOOK.md`](INSTRUCTIONS-KAFKA-WEBHOOK.md). Operator env tables: [`INSTRUCTIONS.md`](INSTRUCTIONS.md) (Kafka Hub sections). Partition keys: [`KAFKA_PARTITION_ORDERING.md`](KAFKA_PARTITION_ORDERING.md). Avro/mTLS tracking: [`future-work/kafka-avro-mtls.md`](future-work/kafka-avro-mtls.md).

## What Hub processes

The listener turns each record into `IncrementalGitEvent`, then `WebhookIncrementalService` runs the existing incremental path.

| `eventType` | What Hub does |
| :--- | :--- |
| `push`, `create`, `delete` | Mirror that ref. A non-create event whose `afterSha` is forty zeros is a delete. |
| `pull_request`, `release`, `status`, `check_run` | Metadata. The adapter must fill `rawPayload`. |
| Anything else | The record is stored as unreadable and the offset is committed. |

`push` and `pull_request` are two values of `eventType`. They are not two Kafka formats. They share one adapter when they share one JSON layout. A second adapter is only for a different layout.

The fields the git path uses are `repoUrl`, `ref`, `beforeSha`, `afterSha`, `eventType`, `deliveryId`, and `provider`.

## Event format adapters

`GIT_WEBHOOK_EVENT_FORMATS_DIR` is an environment variable on the Hub process. It is wired in `backend/src/main/resources/application.yml` as `git-utility.webhook-bus.kafka.event-formats-dir`. The default is empty.

| Env | Default | Role |
| :--- | :--- | :--- |
| `GIT_WEBHOOK_EVENT_FORMATS_DIR` | empty | Directory of `*.json` mapping files. Each file `id` is a `schemaVersion`. |

| Value | What Hub loads |
| :--- | :--- |
| Unset or empty | Only the built-in adapter `normalized-v1`. Today's flat event. |
| A directory path | `normalized-v1`, plus one adapter for each `*.json` file in that directory. |

The directory is read once, when the Kafka listener starts (`GIT_WEBHOOK_BUS_PROVIDER=kafka`). A change applies on the next Hub restart. If the variable is set and the path is missing, is not a directory, or contains no `.json` files, startup fails.

```bash
export GIT_WEBHOOK_EVENT_FORMATS_DIR=/path/to/formats
```

Put that export in the same shell file that starts the backend (`env.pod.a`, or the pod example `env.pod-a.example`). Do not commit a directory that holds secrets. Mapping files are field pointers, not credentials.

`normalized-v1` does not need this variable. Leave it unset until a producer sends a different shape. Runbook: [`INSTRUCTIONS-KAFKA-WEBHOOK.md`](INSTRUCTIONS-KAFKA-WEBHOOK.md) section 12.

## Value codec (`json` | `avro`)

Independent of the mapping adapters and of TLS. Wired as `git-utility.webhook-bus.kafka.value-codec`.

| Env | Default | Role |
| :--- | :--- | :--- |
| `GIT_WEBHOOK_KAFKA_VALUE_CODEC` | `json` | `json` = UTF-8 JSON value. `avro` = Confluent wire format. |
| `GIT_WEBHOOK_KAFKA_SCHEMA_REGISTRY_URL` | empty | Required when codec is `avro`. |
| `GIT_WEBHOOK_KAFKA_SCHEMA_REGISTRY_USERNAME` | empty | Optional registry basic auth. |
| `GIT_WEBHOOK_KAFKA_SCHEMA_REGISTRY_PASSWORD` | empty | Optional registry basic auth. |

| Codec | Behavior |
| :--- | :--- |
| unset / `json` | Record value is UTF-8 JSON. Same path as today. |
| `avro` | Hub asks the Schema Registry for the schema, turns the Avro record into JSON, then runs the same adapters. |

```bash
export GIT_WEBHOOK_KAFKA_VALUE_CODEC=avro
export GIT_WEBHOOK_KAFKA_SCHEMA_REGISTRY_URL=https://schema-registry.example:8081
# export GIT_WEBHOOK_KAFKA_SCHEMA_REGISTRY_USERNAME=
# export GIT_WEBHOOK_KAFKA_SCHEMA_REGISTRY_PASSWORD=
```

Startup fails when codec is `avro` and the registry URL is empty. When the broker uses mutual TLS PEMs, the same certificates are applied to the Schema Registry client.

`sourceMessage` on the Queues page stores the JSON projection (capped at 16,000 characters), not raw Avro bytes. Runbook: [`INSTRUCTIONS-KAFKA-WEBHOOK.md`](INSTRUCTIONS-KAFKA-WEBHOOK.md) section 13.

## Mutual TLS (connection)

Independent of the value codec and of the formats directory. Today’s Confluent Cloud path stays `SASL_SSL` + API key + secret.

| Env | Default | Role |
| :--- | :--- | :--- |
| `GIT_WEBHOOK_KAFKA_SECURITY_PROTOCOL` | `PLAINTEXT` | Set to `SSL` for mutual TLS. |
| `GIT_WEBHOOK_KAFKA_SSL_TRUSTSTORE_LOCATION` | empty | CA / trust PEM. Required for `SSL`. |
| `GIT_WEBHOOK_KAFKA_SSL_KEYSTORE_LOCATION` | empty | Client cert PEM, or combined cert + key PEM. Required for `SSL`. |
| `GIT_WEBHOOK_KAFKA_SSL_KEY_LOCATION` | empty | Private key PEM. Optional when the keystore file already contains the key. |
| `GIT_WEBHOOK_KAFKA_SSL_KEY_PASSWORD` | empty | Passphrase for an encrypted private key. Required when the key is encrypted. |

```bash
export GIT_WEBHOOK_KAFKA_SECURITY_PROTOCOL=SSL
export GIT_WEBHOOK_KAFKA_SSL_TRUSTSTORE_LOCATION=/path/ca.pem
export GIT_WEBHOOK_KAFKA_SSL_KEYSTORE_LOCATION=/path/client.pem
export GIT_WEBHOOK_KAFKA_SSL_KEY_PASSWORD='<pem passphrase>'
```

Hub splits a combined keystore PEM into certificate chain + key, then sets Kafka’s PEM properties (including `ssl.key.password` when set). Partition keys and adapters are unchanged. Runbook: [`INSTRUCTIONS-KAFKA-WEBHOOK.md`](INSTRUCTIONS-KAFKA-WEBHOOK.md) section 14.

## Stringified `payload` envelopes

Some producers (for example `source=GitWebhookService`) wrap a GitHub webhook body as a **JSON string** under `payload`. Before adapters run, Hub parses that string in place so pointers like `/payload/ref` work. The same unwrap runs in `kafka-event-format` check/bridge.

Worked adapter: `kafka-event-format/examples/formats/30-git-webhook-service-v1.json` with sample `examples/samples/git-webhook-service-push.json`. Point `GIT_WEBHOOK_EVENT_FORMATS_DIR` at a directory that includes that file (or a copy).

## How a record picks an adapter

```text
Kafka record
    │
    ▼
value codec (json | avro) → JSON tree
    │
    ▼
unwrap stringified /payload (if it is JSON text)
    │
    ▼
schemaVersion header, or schema-version header, or JSON field /schemaVersion
    │
    ├── set ──────────────► that adapter id
    │
    └── absent
            │
            ▼
        adapters whose when-rule matches
            │
            ├── exactly one ──► that adapter
            ├── none ─────────► stored as unreadable, offset committed
            └── more than one ► stored as unreadable until the producer sets schemaVersion
```

`normalized-v1` matches a record that already has a top-level `repoUrl` and an `eventType` Hub knows. A mapping file matches when the value at `when.pointer` is one of `when.in`.

Use the header when both shapes can land on the same topic. A flat event and an enriched event that both satisfy a rule will otherwise match two adapters.

```text
schemaVersion: enriched-git-v1
```

The header value is the file's `id`, or `normalized-v1`.

## Mapping file

One file per shape. The file name order is not the version. The `id` is.

```json
{
  "id": "enriched-git-v1",
  "when": { "pointer": "/kind", "in": ["ref.updated", "ref.created", "ref.deleted"] },
  "fields": {
    "repoUrl": "/repository/cloneUrl",
    "ref": "/git/ref",
    "beforeSha": "/git/before",
    "afterSha": "/git/after",
    "deliveryId": "/deliveryId",
    "provider": "/provider",
    "eventType": "/kind"
  },
  "eventTypeMap": {
    "ref.updated": "push",
    "ref.created": "create",
    "ref.deleted": "delete"
  }
}
```

| Field | Meaning |
| :--- | :--- |
| `id` | Schema version. This is what `schemaVersion` must equal. |
| `when.pointer` | JSON Pointer. Records without a version use this to choose the file. |
| `when.in` | Raw values that select this file. Match is exact after trim. |
| `fields` | JSON Pointers copied onto `IncrementalGitEvent`. A pointer that is missing is left empty. |
| `eventTypeMap` | Renames the value at the `eventType` pointer onto Hub's type names. |

`rawPayload` may point at an object. Hub stores that object as a JSON string. Metadata types need it.

A second file in the same directory covers a metadata body whose JSON is different. If metadata uses the same envelope as git, add its kinds to the first file's `eventTypeMap` instead of adding a file.

Worked files and samples: `kafka-event-format/examples/formats/` and `kafka-event-format/examples/samples/`.

## Check a file before restart

`kafka-event-format/` does not connect to Kafka and does not run inside Hub. It prints the canonical event a sample would become.

```bash
cd kafka-event-format
node src/cli.js check --format enriched --formats ./formats --samples ./samples
```

The command exits non-zero when a sample matches no file or two files, `repoUrl` is missing, `eventType` is outside the set above, or a metadata type has an empty `rawPayload`. Fix the mapping and rerun. Then set `GIT_WEBHOOK_EVENT_FORMATS_DIR` to that directory and restart Hub.

## After the adapter

Hub mirrors from the canonical event. It does not publish that event back to the topic.

A busy pair lease or a failed attempt leaves the offset uncommitted. Kafka redelivers the original bytes. After `GIT_MAX_RETRY_ATTEMPTS` (default 3), the raw record is stored on the dead-letter list in the database and the offset is committed. Replay from Queues reads that stored body through the same adapters (JSON path after the first failure stores a JSON projection).

## What you see in the UI

Queues → Incremental events is one list, newest first. A row is either a mirror job or a skipped topic record.

- **Repository** includes the branch and a short commit
- **Event** includes the canonical type and the adapter id (`normalized-v1` or the mapping file `id`)
- **Outcome** is the job status or the skip reason
- the braces icon opens the source Kafka value
- the chevron opens the job id, trigger, full time, and Logs, Cancel, or Resume

From 768px wide the list is a table that stays inside the page. Narrower screens use a stacked card for each row. The stored source is capped at 16,000 characters. Dead-letter rows use the same columns.

## What a mapping cannot do

A pointer renames and nests. It does not add a git operation. A fact with no field on `IncrementalGitEvent` is dropped. One source record becomes one canonical event. Two layouts are two files. They must not both match the same record unless the record names `schemaVersion`.
