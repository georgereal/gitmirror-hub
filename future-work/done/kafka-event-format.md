# Done: Kafka event format utility

> **Status:** Complete. Hub adapts each incremental record after consume. `normalized-v1` is built in. Mapping files in `GIT_WEBHOOK_EVENT_FORMATS_DIR` are further adapters, selected by `schemaVersion`. Hub does not write a processing record back to Kafka. The Queues incremental list shows event type, adapter, and a capped source message.

Hub's Kafka listener still binds `IncrementalGitEvent`. This utility runs first. It does not generate classes. A new wire shape is a JSON mapping file.

## What closed

| Exit criterion | Result |
| :--- | :--- |
| `check` prints a canonical event per sample and fails on a missing `repoUrl`, an unknown `eventType`, an empty metadata `rawPayload`, or a sample that matches zero or two mappings | `node src/cli.js check` |
| `bridge` consumes a source topic, produces the canonical topic, and keys the record with the normalized repo URL | `node src/cli.js bridge` after `npm install` |
| Feature flag config selects `normalized-v1` or `enriched` from a JSON file, environment, or CLI | `KAFKA_EVENT_FORMAT` and `KAFKA_EVENT_FORMAT_CONFIG` |
| Several event types share one mapping when the JSON envelope is the same; a second file is only for a different envelope | `when` + `eventTypeMap`, filename order |
| Hub code stays on the existing listener | No decoder was added to the backend |

## Out of scope

A mapping cannot add a git operation or split one record into several events. Those still require a change to `IncrementalGitEvent` and `WebhookIncrementalService`.
