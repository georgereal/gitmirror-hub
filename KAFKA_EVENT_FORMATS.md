# Kafka event formats

How Hub reads more than one JSON shape from the incremental Kafka topic. The mirror path stays on one object, `IncrementalGitEvent`. Adapters run after Hub has consumed the record. Hub does not write a converted copy back to Kafka.

Setup for the cluster, worker, and the rest of the webhook bus is in [`INSTRUCTIONS-KAFKA-WEBHOOK.md`](INSTRUCTIONS-KAFKA-WEBHOOK.md). This page is only the format layer.

## What Hub processes

The listener turns each record into `IncrementalGitEvent`, then `WebhookIncrementalService` runs the existing incremental path.

| `eventType` | What Hub does |
| :--- | :--- |
| `push`, `create`, `delete` | Mirror that ref. A non-create event whose `afterSha` is forty zeros is a delete. |
| `pull_request`, `release`, `status`, `check_run` | Metadata. The adapter must fill `rawPayload`. |
| Anything else | The record is stored as unreadable and the offset is committed. |

`push` and `pull_request` are two values of `eventType`. They are not two Kafka formats. They share one adapter when they share one JSON layout. A second adapter is only for a different layout.

The fields the git path uses are `repoUrl`, `ref`, `beforeSha`, `afterSha`, `eventType`, `deliveryId`, and `provider`.

## Config

`GIT_WEBHOOK_EVENT_FORMATS_DIR` is an environment variable on the Hub process. It is wired in `backend/src/main/resources/application.yml` as `git-utility.webhook-bus.kafka.event-formats-dir`. The default is empty.

| Value | What Hub loads |
| :--- | :--- |
| Unset or empty | Only the built-in adapter `normalized-v1`. Today's flat event. |
| A directory path | `normalized-v1`, plus one adapter for each `*.json` file in that directory. |

The directory is read once, when the Kafka listener starts (`GIT_WEBHOOK_BUS_PROVIDER=kafka`). A change applies on the next Hub restart. If the variable is set and the path is missing, is not a directory, or contains no `.json` files, startup fails.

```bash
export GIT_WEBHOOK_EVENT_FORMATS_DIR=/path/to/formats
```

Put that export in the same shell file that starts the backend (`env.pod.a`, or the pod example `env.pod-a.example`). Do not commit a directory that holds secrets. Mapping files are field pointers, not credentials.

`normalized-v1` does not need this variable. Leave it unset until a producer sends a different shape.

## How a record picks an adapter

```text
Kafka record
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

A busy pair lease or a failed attempt leaves the offset uncommitted. Kafka redelivers the original bytes. After `GIT_MAX_RETRY_ATTEMPTS` (default 3), the raw record is stored on the dead-letter list in the database and the offset is committed. Replay from Queues reads that stored body through the same adapters.

## What you see in the UI

Queues → Incremental events shows, for each processed webhook job:

- the canonical event type (`push`, `pull_request`, and the rest)
- the adapter id (`normalized-v1` or the mapping file `id`)
- the source Kafka value, behind **Kafka message**

The stored source is capped at 16,000 characters. Skipped rows and dead-letter rows show the event type, the adapter, and the payload when Hub kept one.

## What a mapping cannot do

A pointer renames and nests. It does not add a git operation. A fact with no field on `IncrementalGitEvent` is dropped. One source record becomes one canonical event. Two layouts are two files. They must not both match the same record unless the record names `schemaVersion`.
