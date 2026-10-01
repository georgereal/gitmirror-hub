# Kafka event format check

Local check for mapping files Hub loads after it consumes a record. Hub does the adapt. This directory does not publish to Kafka.

`normalized-v1` is built into Hub. Each file under `GIT_WEBHOOK_EVENT_FORMATS_DIR` is another adapter. The file `id` is the `schemaVersion`. The feature write-up is [`KAFKA_EVENT_FORMATS.md`](../KAFKA_EVENT_FORMATS.md). Hub env: adapters in [`INSTRUCTIONS-KAFKA-WEBHOOK.md`](../INSTRUCTIONS-KAFKA-WEBHOOK.md) section 12; Avro codec in section 13; mutual TLS in section 14. Operator tables also live under the Kafka Hub headings in [`INSTRUCTIONS.md`](../INSTRUCTIONS.md).

```bash
node src/cli.js check --format enriched --formats examples/formats --samples examples/samples
```

The command prints one canonical event per sample and exits non-zero when a sample matches no file or two files, `repoUrl` is missing, `eventType` is outside Hub's set, or a metadata type has an empty `rawPayload`.
