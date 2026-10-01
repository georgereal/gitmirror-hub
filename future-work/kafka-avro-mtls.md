# Kafka Avro (Schema Registry) + mutual TLS

> **Status:** Implemented (pending Maven verify). Opt-in modes beside today’s JSON + SASL paths. Unset new env = current behavior unchanged.

Related: [`done/kafka-event-format.md`](done/kafka-event-format.md) (JSON adapters after consume), [`kafka-incremental-upstream-sync.md`](kafka-incremental-upstream-sync.md) (webhook bus), [`../KAFKA_EVENT_FORMATS.md`](../KAFKA_EVENT_FORMATS.md).

## Independence

| Layer | Default (unchanged) | Opt-in |
| :--- | :--- | :--- |
| Connection | `PLAINTEXT`, `SASL_SSL` / `SASL_PLAINTEXT` with API key + secret | `SSL` + three PEM files (mTLS) |
| Value codec | JSON string (`json`) | `avro` via Schema Registry URL |
| Event adapters | `normalized-v1` + `GIT_WEBHOOK_EVENT_FORMATS_DIR` | Same adapters after Avro→JSON |

- mTLS only → JSON + existing adapters  
- Avro only → today’s connection + Schema Registry decode + same adapters  
- both → compose  
- formats dir / `schemaVersion` work under either codec  

## Env

| Env | Role |
| :--- | :--- |
| `GIT_WEBHOOK_KAFKA_SECURITY_PROTOCOL=SSL` | Mutual TLS |
| `GIT_WEBHOOK_KAFKA_SSL_TRUSTSTORE_LOCATION` | CA PEM |
| `GIT_WEBHOOK_KAFKA_SSL_KEYSTORE_LOCATION` | Client certificate PEM, or combined cert + key PEM |
| `GIT_WEBHOOK_KAFKA_SSL_KEY_LOCATION` | Client private key PEM (optional when keystore already has the key) |
| `GIT_WEBHOOK_KAFKA_SSL_KEY_PASSWORD` | Passphrase for encrypted PKCS#8 key |
| `GIT_WEBHOOK_KAFKA_VALUE_CODEC` | `json` (default) \| `avro` |
| `GIT_WEBHOOK_KAFKA_SCHEMA_REGISTRY_URL` | Required when codec is `avro` |
| `GIT_WEBHOOK_KAFKA_SCHEMA_REGISTRY_USERNAME` / `_PASSWORD` | Optional registry basic auth |

## Implementation sketch

1. `WebhookBusEnvironmentPostProcessor` — fail-fast for SSL PEMs and avro registry URL; map PEM file contents onto `ssl.truststore.certificates`, `ssl.keystore.certificate.chain`, `ssl.keystore.key`, and `ssl.key.password` when set. Combined cert+key PEM supported. Same PEMs for Schema Registry client when SSL.
2. Consumer value as `byte[]`; `KafkaValueCodec` — json UTF-8, or Confluent Avro via Schema Registry → JSON tree.
3. `IncrementalEventDecoder` unwraps stringified `payload` then runs adapters. `sourceMessage` stores capped JSON projection.

## Exit criteria

- [x] Unset new env: Hub Kafka path identical to today (JSON + SASL/PLAINTEXT); consumer value is `byte[]` with UTF-8 json codec
- [x] `SSL` without all three PEM paths fails startup
- [x] `avro` without registry URL fails startup
- [x] Avro→JSON feeds existing adapters; `sourceMessage` stores capped JSON projection
- [x] Docs: `KAFKA_EVENT_FORMATS.md`, `INSTRUCTIONS.md`, `INSTRUCTIONS-KAFKA-WEBHOOK.md`, `ARCHITECTURE.md`, pod env examples

Maven unit tests for the post-processor and codec were added; run them when ready.
## Out of scope

PKCS12/JKS, local `.avsc` without registry, Rabbit Avro, full-mirror Kafka, partition-key changes.
