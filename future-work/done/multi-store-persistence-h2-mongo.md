# Done: Multi-store persistence — H2 | MongoDB (provider-selected)

> **Status:** Complete. Phases 1–5 shipped (H2 | Mongo provider, string ids, store facades, contract tests, docs, Settings store indicator).  
> **Folder:** [`future-work/done/`](../README.md)  
> **Goal:** One backend codebase runs against **exactly one** configured store — file H2 today, MongoDB for enterprise scale — selected by env at startup, with no fallbacks and no dual connections.  
> **Pattern to mirror:** the existing `messaging/` module (`MessagingProvider` / `MessagingConditions` / `MessagingEnvironmentPostProcessor` / `MessagingModule` + `GET /api/v1/messaging`).

## Problem

Today all 15 aggregates live in a single file-based H2 database (`jdbc:h2:file:./data/gitutility`) behind Spring Data JPA repositories. That is ideal for local dev, but there is no path to an enterprise-grade store: repositories are JPA interfaces injected directly into ~59 classes (services, controllers, provider adapters), the schema migrator is raw H2 JDBC DDL, and there is no seam where a second store could live.

## Locked decisions

| # | Decision | Rationale |
| :--- | :--- | :--- |
| 1 | Client library = **Spring Data MongoDB** (`spring-boot-starter-data-mongodb`) | "Mongoose" is a Node.js ODM and does not apply to this Java/Spring Boot backend; Spring Data MongoDB is its exact role-alike |
| 2 | **ObjectId-as-String ids end-to-end** | Mongo-native; removes counter collections; ids flow as strings through REST paths, AMQP payloads, WebSocket events, and the React UI |
| 3 | **Existing H2 data: fresh start** | Dev-only data; document "wipe or re-seed"; no H2→H2 or H2→Mongo migrator ships |
| 4 | **Single active store, enforced** | Symmetric autoconfiguration exclusion — the inactive stack is never instantiated or connected; no fallback code paths |
| 5 | Architecture mirrors `messaging/` | Proven in-repo pattern for pluggable providers |

**Doc-sync flag:** `ARCHITECTURE.md` §10 and `INSTRUCTIONS.md` currently recommend **PostgreSQL** as the enterprise store and explicitly say a document DB must **not** back `pair_leases`, `instance_heartbeats`, `cluster_runtime`, `sync_jobs`. This plan consciously reverses that. When this ships, §10 must be rewritten — Mongo is safe for the cluster tables because every lease/heartbeat op becomes an atomic single-document `updateOne`/`findAndModify`, and multi-document transactions (replica-set-only) are not needed (see audit).

## Current-state audit (verified in code)

| Fact | Consequence |
| :--- | :--- |
| 15 entities (`@Entity` + Lombok `@Data`), 15 Spring Data JPA repos | Shared dual-annotated domain model (`@Entity` + `@Document`); one model, two stores |
| 11 entities: `Long id` + `GenerationType.IDENTITY`; natural keys elsewhere (`InstanceHeartbeat.instanceId` String, `PairLease.mappingId`, `ClusterRuntime` fixed id=1, 4 config singletons) | ID refactor to `String`; singletons get a fixed `"singleton"` id; `findTopByOrderByIdAsc` semantics preserved on both stores |
| **No** native SQL, `@Lock`, `@Version`, `saveAndFlush`, `getById`, `Specification`, `EntityManager` usage | Small porting surface |
| ~10 JPQL `@Query`/`@Modifying` methods; everything else derived queries | Only the 10 need `MongoTemplate` reimplementations (inventory below) |
| 14 `@Transactional` sites, all single-entity getOrCreate/update patterns | Mongo works without multi-document transactions; facades implement atomic getOrCreate/upsert |
| `PairLeaseService.acquire` = read-modify-write + unique-constraint violation as race guard | Mongo: unique index + `DuplicateKeyException` catch + atomic expired-takeover `updateOne` (stricter than today) |
| `EncryptedStringConverter` (JPA `AttributeConverter`, AES-256-GCM) on ~25 secret fields | JPA converters don't run in Mongo → `@Encrypted` marker + Mongo lifecycle listener with the **same ciphertext format** |
| `@PrePersist`/`@PreUpdate` in 9 entities (timestamps, `SyncJob` clipping, `PrMapping` clamping) | Port to Mongo `onBeforeSave` lifecycle listener |
| `DatabaseSchemaMigrator` = raw H2 JDBC DDL; H2 console optional | Both become `@OnH2`-conditional |
| `ScmCredentialLegacyMigrator` runs via the service layer | Already store-agnostic |
| `DedupLedgerService` = in-memory `ConcurrentHashMap` | Zero DB impact |
| ~59 files import repositories; ~35 service classes + controllers + 6 provider adapters inject them | Facade keeps existing names/method surface → injection sites compile unchanged |

## Design

```
backend/src/main/java/com/gitutility/
├── persistence/                        # NEW — mirrors messaging/
│   ├── PersistenceProvider.java        # enum H2 | MONGO; from() normalization; fail-fast unknown
│   ├── PersistenceConditions.java      # @OnH2 / @OnMongo meta-annotations
│   ├── PersistenceEnvironmentPostProcessor.java
│   │       # mongo → exclude DataSourceAutoConfiguration, JpaRepositoriesAutoConfiguration,
│   │       #        HibernateJpaAutoConfiguration, H2ConsoleAutoConfiguration
│   │       # h2    → exclude MongoAutoConfiguration / MongoRepositoriesAutoConfiguration
│   ├── PersistenceModule.java          # descriptor bean for the UI
│   └── store/
│       ├── MongoSchemaInitializer.java # collections + indexes (role of DatabaseSchemaMigrator)
│       └── Ids.java                    # ObjectId-hex generator shared by both stores
├── repository/
│   ├── RepoMappingRepository.java      # now a PLAIN facade interface (same name/methods)
│   ├── ... (15 facades)
│   ├── h2/                             # @OnH2 impls delegating to Spring Data JPA repos
│   └── mongo/                          # @OnMongo impls: Spring Data Mongo repos + MongoTemplate
│       └── EncryptedFieldMongoListener.java
└── security/
    └── Encrypted.java                  # marker annotation (@Convert stays JPA-only)
```

Key choices:

1. **Facade at repository-name level** — existing repository names become plain interfaces declaring exactly the methods used today (compiler-driven audit: any missed method is a compile error). Two conditional impls per interface; ~63 injection sites unchanged.
2. **One shared entity class** annotated `@Entity` + `@Document`; each store ignores the other's annotations. `Instant`, `@Enumerated(EnumType.STRING)`, and Lombok map natively; `@Column(columnDefinition=…)` is ignored by Mongo.
3. **IDs** — `String id` (24-char ObjectId hex) generated by `Ids.newId()` before save in both stores; no `GenerationType`, no counter collection.
4. **PairLease port** — unique index on `mappingId`; insert race → `DuplicateKeyException` (same `DataAccessException` family as today's `DataIntegrityViolationException`); renew/takeover = atomic `updateOne({_id, $or:[{expiresAt ≤ now},{owner == me}]})` returning `modifiedCount` (matches the current `int` return).
5. **Page parity** — `Page<T>` returns identical types via `PageableExecutionUtils`.
6. **Optional TTL indexes** for `instance_heartbeats` / `unmapped_webhook_events` retention, while keeping the scheduled-cleanup facade methods for parity.
7. **UI descriptor** — `GET /api/v1/persistence` mirroring `GET /api/v1/messaging`.

### Non-portable query port inventory (the real work — 10 methods)

| Current (JPQL) | Mongo equivalent |
| :--- | :--- |
| `InstanceHeartbeatRepository.deleteOlderThan` | `remove()` count by `updatedAt < cutoff` |
| `PairLeaseRepository.deleteOwned` / `renewOwned` | atomic `deleteOne` / `updateOne` + modified count |
| `RepoMappingRepository.findActiveMatchingRepo` | `$or` exact match + `$regex` contains |
| `SyncJobRepository.search` (nullable filters + lane logic + Pageable) | `MongoTemplate` Criteria builder + `with(Sort/Pageable)` + `PageableExecutionUtils` |
| `SyncJobRepository.countByStatus` / `countJobsSince` / `countSuccessJobsSince` | Criteria counts |
| `SyncJobRepository.findForUsageWindow` | `$or` criteria (`$ne null`, `$ne ''`, status `$in`) |
| `UnmappedWebhookEventRepository.deleteOlderThan` | `remove()` count by `receivedAt < cutoff` |
| `TRIM(j.ref) = ''` lane condition | `$expr` + `$trim`, or an empty-ref sentinel normalized at write time (decide in Phase 3) |

Derived queries (`Top20By…`, `FirstBy…`, `EnabledTrue…`, `deleteByJobId`, `findByMappingIdAndRefName…`) are supported by Spring Data MongoDB as-is.

### Configuration

| Env var | Values | Behavior |
| :--- | :--- | :--- |
| `GIT_PERSISTENCE_PROVIDER` | `h2` (default) \| `mongo` | Selects the single active store; unknown value fails fast at boot |
| `MONGODB_URI` | `mongodb://…` / `mongodb+srv://…` | Read only when provider=mongo |
| `MONGODB_DATABASE` | default `gitutility` | |
| h2 mode | unchanged | `spring.datasource.*`, H2 console, `ddl-auto: update` untouched |

## Phases (each leaves the build green)

### Phase 1 — Persistence module skeleton — **Shipped**

**Effort:** small (~1 day) · **Deps:** none

1. `PersistenceProvider` enum (`h2` default | `mongo`), `from()` normalization, fail-fast on unknown values (mirrors the `kafka` fail-fast). — **Shipped**
2. `PersistenceConditions` (`@OnH2` / `@OnMongo`), `PersistenceEnvironmentPostProcessor` (symmetric autoconfig exclusions), `PersistenceModule` descriptor. — **Shipped**
3. `pom.xml` + `build.gradle.kts` gain `spring-boot-starter-data-mongodb`; `application.yml` gains `git-utility.persistence.provider` + `spring.mongodb.*` keys (Boot 4.x relocated these from `spring.data.mongodb.*`; Gradle KTS kept in sync per repo convention). — **Shipped**
4. Descriptor endpoint `GET /api/v1/persistence` (mirrors `GET /api/v1/messaging`). — **Shipped**
5. Unit tests (`PersistenceProviderTest`, `PersistenceEnvironmentPostProcessorTest`). — **Shipped**

**Boot 4.1.1 discoveries (verified in jars + live boot):**

- EnvironmentPostProcessors register via `META-INF/spring.factories` under the **root-package** key `org.springframework.boot.EnvironmentPostProcessor` — the per-interface `META-INF/spring/...` file this repo previously used for messaging is **not** read for this SPI, so the messaging EPP had silently never run at boot (latent dead code; fixed in the same change).
- Exclusion lists must only name classes present on the classpath — unknown `spring.autoconfigure.exclude` entries fail startup. The `spring-boot-h2` module does not exist in Boot 4.1.1, so `H2ConsoleAutoConfiguration` is not on the classpath and must not be excluded (the `spring.h2.console.*` YAML block is inert).
- Boot 4.1.1 Mongo autoconfig FQCNs: `org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration` (+ `health`/`metrics`), `org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration`, `DataMongoRepositoriesAutoConfiguration` (+ reactive variants). Relational: `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration` (+ health), `org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration`, `org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration`.

**Exit (verified):** `h2` default boots exactly as before (297 tests green; 0 "Spring Data MongoDB" repository scan lines vs 17 before); zero Mongo beans/clients in h2 mode; `GIT_PERSISTENCE_PROVIDER=postgres` fails startup with `Unknown git-utility.persistence.provider 'postgres' (supported: h2, mongo)`.

### Phase 2A — ObjectId-string IDs end-to-end (breaking) — **Shipped**

**Effort:** medium–large (~3–5 days) · **Deps:** Phase 1

1. 11 entities: `Long id` → `String id`; all FK-like columns → `String` (`mappingId`, `jobId`, `sourceCredentialId`/`targetCredentialId`, `bulkSubmissionId`, `lastMirrorJobId`, `PairLease.mappingId` PK). Fixed `"singleton"` id for the 4 config singletons. — **Shipped**
2. Shared `Ids.newId()` (ObjectId hex) generated in `@PrePersist` (now `prepareForWrite()`); reused verbatim by the Mongo store. — **Shipped**
3. DTOs (`model/dto/`), controllers (`@PathVariable`/query params), `SyncEventMessage` + queue consumers/producers, WebSocket broadcast payloads. — **Shipped**
4. Frontend (React 19 + TS): models `id: number → string`; `Number()`/`parseInt` id casts removed; id sorts → `localeCompare` (ObjectId hex ≈ chronological). — **Shipped**

**Corrections made during implementation (keep for posterity):**
- `PullRequestSyncService.materializeForkPrForDr` returns a **GitHub PR number**, not an entity id — kept `Long` (one over-eager sed was reverted).
- GitHub wire ids (`GitHubPushPayload` inner ids, `SyncDiffReport` release/asset/check-run ids, PR numbers, `Set<Long> prSeenOpenSourceNumbers`) stay numeric.
- Test-suite lesson: `mvn test` with unchanged test sources does NOT recompile them against changed main signatures → phantom `NoSuchMethodError` "failures". Always `mvn clean test` after signature refactors.

**Ops note:** pre-upgrade queued AMQP messages carry numeric ids → dead-letter on the new build. **Drain queues before deploying.** Existing H2 file databases have BIGINT id columns — **delete `./data/` (fresh start, locked decision)**.

**Exit (verified):** full suite green (297/297); h2 boot smoke: String-id schema + mappings/jobs APIs working; frontend `tsc --noEmit` = 0 errors and production build succeeds.

### Phase 2B — Facade extraction (no Mongo yet) — **Shipped**

**Effort:** medium (~2–3 days) · **Deps:** Phase 2A

1. 15 repository names → plain facade interfaces with today's exact method surface (uniform CRUD + customs); Spring Data JPA interfaces moved to `repository/h2/` as `XJpaRepository`; 15 `@OnH2` delegating stores `H2XStore`. — **Shipped**
2. Compiler-driven method audit — any missed facade method is a compile error, nothing dropped silently. — **Shipped** (uniform CRUD surface: save/saveAll/findById/existsById/findAll/count/delete/deleteById/deleteAll)

**Exit (verified):** all 297 tests green on clean build; ~63 injection sites untouched; h2 boot smoke passes; H2 stores are the active facade beans.

### Phase 3 — Mongo store — **Shipped**

**Effort:** large (~3–5 days) · **Deps:** Phase 2B

1. Entities gain `@Document(collection = …)` + `@Encrypted` (JPA keeps `@Convert`); `MongoEntityLifecycleListener` prepares `WritePreparer` entities on save (same id/timestamp/clamping as JPA) and encrypts/decrypts `@Encrypted` fields with the same AES-256-GCM `enc:v1:` ciphertext format. — **Shipped**
2. 15 `@OnMongo` stores in `repository/mongo/` + 15 `XMongoRepository` interfaces: derived queries delegated; the 10 JPQL ports reimplemented via `MongoTemplate` (dynamic job search incl. `$expr`/`$trim` blank-ref semantics, lease `renewOwned`/`deleteOwned` as atomic `updateOne`/`deleteOne` returning modified counts, URL/`LIKE` → `$or`+regex, two retention deletes; `countByStatus`/`countJobsSince`/`countSuccessJobsSince`/`findForUsageWindow` as derived Mongo queries). — **Shipped**
3. `MongoSchemaInitializer` (@OnMongo): **ping fail-fast** + unique indexes (`repo_mappings.name`, `sync_jobs.queueMessageId`, `ref_origins.mappingId+refName`), compound indexes, 7-day TTL on unmapped webhook events. — **Shipped**
4. Lease port: unique-key insert race → `DuplicateKeyException` catch + atomic expired-takeover `updateOne`. — **Shipped**
5. `DatabaseSchemaMigrator` + `ScmCredentialLegacyMigrator` → `@OnH2`; **`H2RepositoriesConfig` / `MongoRepositoriesConfig`** scope each store's repository scanning (required: dual-annotated entities confuse Boot's multi-store auto-scan — the JPA autoconfig otherwise claims the Mongo repos and fails with "No property 'insert' found"). — **Shipped**
6. `GET /api/v1/persistence` descriptor (shipped in Phase 1).

**Exit (verified):** full suite green (297/297); h2 mode boots with **0** Mongo beans/log lines; mongo mode registers 15 Mongo repositories, has **0** Hibernate/DataSource beans, and **fails startup** on an unreachable MongoDB (ping fail-fast) with no fallback.

### Phase 4 — Contract tests, both stores — ✅ COMPLETE

**Effort:** medium (~2–3 days) · **Deps:** Phase 3

1. Abstract contract-test suite per facade interface (`RepoMappingStoreContract`, `SyncJobStoreContract`, `PairLeaseStoreContract`, `AuxStoreContract`, `ConfigStoreContract`) executed twice: `H2StoreContractTest` (in-memory H2) and `MongoStoreContractTest` (real MongoDB). — **Shipped**
2. Mongo fixture runtime: **no Docker / no Testcontainers** (explicit project decision). The mongo suite runs against a real MongoDB supplied via `MONGO_CONTRACT_URI` (env var or `-DMONGO_CONTRACT_URI=...`); `MongoContractDb` publishes it as `MONGODB_URI` + `spring.mongodb.uri` system properties (the Boot 4.x key; `spring.data.mongodb.uri` is unbound since 4.0.0). When unset, `@EnabledIf(StoreContractEnvironment#mongoContractAvailable)` skips the suite cleanly, keeping plain `mvn test` green everywhere. The target should be a replica set (single node is fine) for the `MongoTransactionManager` used by `@Transactional` service methods. — **Shipped**
3. All existing tests stay green on h2. — **Verified**

**Exit (verified):** H2 contract suite 33/33 green; mongo suite skips cleanly without `MONGO_CONTRACT_URI`; every facade method proven on H2, and provable on Mongo by pointing `MONGO_CONTRACT_URI` at a real instance.

### Phase 5 — Docs sync + polish

**Effort:** small (~1–2 days) · **Deps:** Phase 4

1. Rewrite `ARCHITECTURE.md` §10 storage roadmap (replaces the PostgreSQL recommendation consciously), `INSTRUCTIONS.md` env table + "wipe or re-seed" note, `REPO_MAP.md` new packages. — **Shipped**
2. UI indicator of the active persistence provider on Settings (mirrors the messaging descriptor: `GET /api/v1/persistence` display name + description). — **Shipped**

**Exit:** docs reflect shipped behavior (per project doc-sync rules).

**Total:** ~3–4 weeks.

## Acceptance criteria

1. `GIT_PERSISTENCE_PROVIDER=h2` (default): app behaves exactly as today; **no Mongo beans, clients, or connections** exist.
2. `GIT_PERSISTENCE_PROVIDER=mongo` + `MONGODB_URI`: full parity; **no DataSource/JPA/H2 beans** exist; unreachable Mongo fails startup.
3. Same REST/WS/AMQP contracts on both providers (only the id type changes, once, in Phase 2A).
4. Secrets encrypted at rest identically in both stores; ciphertext from either store decrypts in both.
5. Contract tests prove every facade method across both stores.

## Risks & mitigations

| Risk | Mitigation |
| :--- | :--- |
| 10 JPQL ports (esp. dynamic job search + `TRIM` lane logic) | Contract tests; `PageableExecutionUtils` page parity |
| Lease race semantics | Atomic `updateOne` filters are stricter than today's read-modify-write; covered by tests |
| Breaking id change (AMQP + frontend) | Queue-drain deploy note; TS types guard the frontend refactor |
| Multi-doc transactions need a replica set | All 14 `@Transactional` sites are single-entity patterns; facades implement atomic getOrCreate/upsert; run local mongod as a single-node replica set anyway |
| Boot 4.1 modular autoconfig FQCNs for exclusions | Verified during the Phase 1 spike |
| Mongo contract fixtures need a reachable server | No Docker/Testcontainers (project decision): suite runs against a real MongoDB via `MONGO_CONTRACT_URI` and skips cleanly when unset |

## Non-goals

- PostgreSQL provider (revisit separately)
- Reactive Mongo (`spring-data-mongodb-reactive`)
- Classpath-level store isolation via Maven profiles (one artifact; runtime exclusions only)
- H2→H2 or H2→Mongo data migration tooling (fresh-start decision)
- Changing Git engine, messaging providers, webhook worker, or frontend behavior beyond id typing

## Touchpoints

`model/entity/*` (15) · `repository/*` (15 → facades + `h2/` + `mongo/`) · `security/EncryptedStringConverter` + new `Encrypted` · `config/DatabaseSchemaMigrator` · `config/ScmCredentialLegacyMigrator` (indirect) · `service/PairLeaseService`, `ClusterRuntimeService`, `FeatureFlagsService`, `SystemEngineConfigService`, `InstanceHeartbeatService` · `messaging/rabbit/SyncEventMessage` + consumers/producers · WebSocket notification payloads · `model/dto/*` · controllers (`@PathVariable` ids) · frontend TS models · `pom.xml` + `build.gradle.kts` · `application.yml` · `GET /api/v1/persistence` (new) · `ARCHITECTURE.md` §10 / `INSTRUCTIONS.md` / `REPO_MAP.md` (Phase 5).

## Open items

- ~~Docker available for Testcontainers, or mandate a local mongod replica set for contract tests?~~ **Resolved:** no Docker/Testcontainers — mongo contracts run against a real MongoDB via `MONGO_CONTRACT_URI` and skip when unset.
- ~~UI: show the active persistence provider in Settings (mirror the messaging descriptor display)?~~ **Shipped** (`SettingsLayout` store line).
- ~~Confirm Boot 4.1 modular autoconfig class names for exclusions during the Phase 1 spike.~~ **Resolved** (verified; see `PersistenceEnvironmentPostProcessor`).
