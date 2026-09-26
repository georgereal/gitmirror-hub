# GitMirror Hub - Repository Code Map

This document provides a comprehensive navigation guide to the **GitMirror Hub** codebase, outlining the responsibilities, dependencies, and key classes across backend, frontend, and serverless edge worker modules.

---

## 1. Directory Tree Structure

```
gitUtility/
├── ARCHITECTURE.md                     # Master system design & architecture specification
├── REPO_MAP.md                         # Codebase map and component index (this file)
├── INSTRUCTIONS.md                     # Operational guide, runbook, and failover instructions
├── INSTRUCTIONS-MULTI-POD.md           # Local multi-pod (2+ backend JVMs) runbook
├── INSTRUCTIONS-KAFKA-WEBHOOK.md       # Confluent Cloud cluster, Kafka webhook worker, and Hub bus env
├── KAFKA_PARTITION_ORDERING.md         # Why one repo stays in order across many partitions
├── SCM_PROVIDER_SETUP.md               # Create/configure SCM identities (GitHub App first; more providers later)
├── README.md                           # Quickstart summary
├── SECURITY.md                         # Localhost-only threat model & secret handling
├── LICENSE                             # MIT
├── env.pod-a.example                   # Template env for pod-a (copy → gitignored env.pod-a)
├── env.pod-b.example                   # Template env for pod-b (copy → gitignored env.pod-b)
├── future-work/                        # Deferred backlog design plans (not shipped behavior)
│   ├── README.md                       # Pending vs done index
│   ├── cache-resume-worker-affinity.md # Resume, cache, affinity (partial)
│   ├── pr-sync-parity.md               # Richer PR sync (shell shipped; A–E pending)
│   ├── fanout-concurrency.md           # In-job fan-out (LFS/PR pools shipped)
│   ├── kafka-mirroring-partitions.md   # Future broker partitioning sketch
│   ├── scm-credential-vault-and-hub-hmac.md # Vault refs; Hub HMAC on consume (parked)
│   └── done/                           # Fully shipped plans
│       ├── README.md
│       └── micrometer-internals-observability.md
│
├── .cursor/rules/                      # Cursor AI guidelines and persistence rules
│   └── documentation-and-repo-map-sync.mdc
│
├── webhook-worker/                     # Cloudflare Worker Serverless Webhook Gateway (Edge)
│   ├── package.json                    # Wrangler CLI & TypeScript dependencies
│   ├── tsconfig.json                   # TypeScript configuration
│   ├── wrangler.toml                   # Worker name and entry. CloudAMQP values stay in gitignored .env
│   ├── .env.example                    # Template for webhook-worker/.env (copy, then fill in)
│   ├── README.md                       # Wrangler login, secret setup, and deployment runbook
│   └── src/
│       └── index.ts                    # Edge router: WebCrypto HMAC validation & RabbitMQ HTTP publish
│
├── backend/                            # Spring Boot 4.1.1 Backend Application (Java 21/23)
│   ├── pom.xml                         # Maven build (preferred for local bootRun)
│   ├── build.gradle.kts                # Gradle Kotlin DSL (alternate / wrapper / pod-style runs)
│   ├── settings.gradle.kts             # Gradle root project name
│   ├── gradlew / gradlew.bat           # Gradle Wrapper scripts
│   ├── gradle/wrapper/                 # Wrapper JAR + properties (Gradle 8.10.2)
│   └── src/
│       ├── main/
│       │   ├── java/com/gitutility/
│       │   │   ├── GitUtilityApplication.java   # Spring Boot entry point & seed data runner
│       │   │   ├── config/                      # Infrastructure & Spring bean configurations
│       │   │   │   ├── AsyncConfig.java         # Thread pools (sync, LFS discover/transfer, PR create, release sync); bounded queues block when full
│       │   │   │   ├── CorsConfig.java          # Localhost-only CORS for the operator UI (override via GIT_CORS_*)
│       │   │   │   ├── DatabaseSchemaMigrator.java # Automatic schema evolution & missing column migration on startup
│       │   │   │   ├── ScmRestTemplateFactory.java # Shared RestTemplate + ProviderRateMeter interceptor
│       │   │   │   ├── RabbitMQConfig.java      # Exchanges, Queues, DLX, DLQ (conditional: messaging.provider=rabbitmq)
│       │   │   │   └── WebSocketConfig.java     # STOMP over WebSocket broker configuration
│       │   │   ├── messaging/                   # Pluggable SyncEventBus (rabbitmq | kafka | none)
│       │   │   │   ├── MessagingModule.java     # Descriptor for UI /api/v1/messaging (includes Rabbit laneMaxConcurrency)
│       │   │   │   ├── SyncEventBus.java        # Publish/republish SPI
│       │   │   │   ├── rabbit/                  # RabbitSyncEventBus + lane listeners
│       │   │   │   ├── none/                    # NoneSyncEventBus (no broker)
│       │   │   │   └── webhook/                 # GIT_WEBHOOK_BUS_PROVIDER incremental lane (kafka | rabbitmq | off)
│       │   │   │   ├── GlobalExceptionHandler.java # Central REST error mapping
│       │   │   │   ├── RootApiController.java   # Root info endpoint (/ and /api/v1)
│       │   │   │   ├── GitHubAppController.java # Legacy god-row GitLab/Bitbucket/Origin config + GitHub shim
│       │   │   │   ├── ScmCredentialController.java # GitHub/GHES credential list CRUD, picker search, webhook URLs
│       │   │   │   ├── QueueController.java     # Queue stats, DLQ redrive, main/inbound/DLQ purge
│       │   │   │   ├── RuntimeMetricsController.java # Micrometer snapshot + cluster heartbeats (/api/v1/runtime-metrics)
│       │   │   │   ├── RepoMappingController.java# Repo mapping CRUD, manual sync, bulk submit, PRs/LFS/Releases sync & diff endpoints
│       │   │   │   ├── WriteAuthorityController.java # GET/POST /api/v1/write-authority: linked or individual write/read at repo, org, or enterprise scope
│       │   │   │   ├── BulkMigrationController.java # Bulk submission query + cancel (`/api/v1/bulk`)
│       │   │   │   ├── SimulationController.java# Fault injection & synthetic webhook emitter
│       │   │   │   ├── StorageController.java   # Local & NAS mirror disk quota & LRU eviction API
│       │   │   │   ├── SyncJobController.java   # Sync job querying, stats, retry, and cancel
│       │   │   │   ├── SystemEngineConfigController.java # System engine, retries, NAS validator & circuit breaker reset
│       │   │   │   ├── FeatureFlagsController.java # Product feature toggles (public repos, optional providers)
│       │   │   │   ├── UnmappedWebhookController.java # Discarded & unmapped webhook events API
│       │   │   │   └── WebhookController.java   # GitHub push & PR webhook ingestion endpoint
│       │   │   ├── provider/                    # SCM Provider Adapter (Strategy) & Facade Architecture
│       │   │   │   ├── ScmProviderAdapter.java  # Common interface for all SCM transport & API operations
│       │   │   │   ├── ScmProviderFacade.java   # Dynamic router & registry resolving adapters by repo URL
│       │   │   │   ├── PublicReadProbe.java     # Shared anonymous HTTPS / git ls-remote public-first probe
│       │   │   │   ├── bitbucket/
│       │   │   │   │   └── BitbucketProviderService.java # Bitbucket Cloud REST 2.0 & Git credentials adapter
│       │   │   │   ├── ghes/
│       │   │   │   │   └── GitHubEnterpriseProviderService.java # GHES on-premise (/api/v3) & custom host adapter
│       │   │   │   ├── github/
│       │   │   │   │   ├── GitHubProviderService.java # GitHub.com Cloud App JWT & PAT adapter (GraphQL + REST)
│       │   │   │   │   ├── GithubGraphQlClient.java   # GitHub GraphQL executor (PR pages, mirror snapshot, releases)
│       │   │   │   │   ├── GithubPullRequestGraphQl.java # GraphQL PR page parser
│       │   │   │   │   ├── GithubMirrorSnapshotGraphQl.java # GraphQL mirror metadata snapshot parser
│       │   │   │   │   └── GithubGraphQlQueries.java # GraphQL query strings
│       │   │   │   ├── gitlab/
│       │   │   │   │   └── GitLabProviderService.java # GitLab.com & self-hosted REST v4 adapter
│       │   │   │   ├── origin/
│       │   │   │   │   └── OriginProviderService.java # Cursor Origin SCM adapter
│       │   │   │   └── generic/
│       │   │   │       └── GenericGitProviderService.java # Fallback generic HTTPS Git remote adapter
│       │   │   ├── model/                       # Entities, DTOs, and Enums
│       │   │   │   ├── dto/
│       │   │   │   │   ├── DlqMessageDto.java
│       │   │   │   │   ├── DiffInspectOptions.java
│       │   │   │   │   ├── CiCheckPage.java         # One REST page of CI check runs for a commit
│       │   │   │   │   ├── CommitStatusDetail.java  # Legacy commit status on a commit (context/state/url)
│       │   │   │   │   ├── BulkMirrorRequest.java  # Multi-pair bulk migration submission
│       │   │   │   │   ├── BulkMirrorResponse.java # Per-row CREATED_QUEUED / SKIPPED / FAILED_VALIDATION
│       │   │   │   │   ├── CreateRepoRequest.java
│       │   │   │   │   ├── GitHubAppConfigRequest.java
│       │   │   │   │   ├── GitHubPushPayload.java
│       │   │   │   │   ├── GitHubRepoOption.java    # Multi-provider repository option with provider, namespace & permissions
│       │   │   │   │   ├── InboundWebhookMessage.java
│       │   │   │   │   ├── JobStageProgress.java    # Per-stage resume progress (PR/LFS cursors)
│       │   │   │   │   ├── JobUsageResponse.java    # Ranked per-job REST/GraphQL/Git usage for Internals
│       │   │   │   │   ├── MirrorMetadataSnapshot.java # GraphQL mirror snapshot DTO
│       │   │   │   │   ├── PairDiffSnapshot.java  # Cached pair diff counts for repo detail
│       │   │   │   │   ├── PermissionCheckReport.java
│       │   │   │   │   ├── PrListPage.java          # Paginated open PR page (GraphQL cursor / REST Link)
│       │   │   │   │   ├── PullRequestSnapshot.java
│       │   │   │   │   ├── ProviderConfigResponse.java
│       │   │   │   │   ├── QueueStatusResponse.java
│       │   │   │   │   ├── ReleaseListPage.java     # Cursor-paged release page (GraphQL endCursor / REST page)
│       │   │   │   │   ├── ReleaseLookup.java       # Destination release-by-tag lookup for idempotent mirror diffs
│       │   │   │   │   ├── RepoMappingResponse.java
│       │   │   │   │   ├── RepoSearchResult.java       # Paginated multi-provider search results DTO
│       │   │   │   │   ├── RuntimeMetricsResponse.java # Internals UI Micrometer snapshot
│       │   │   │   │   ├── SimulationConfigRequest.java
│       │   │   │   │   ├── SimulationRefTip.java      # Peer tip lookup for a simulation probe
│       │   │   │   │   ├── SimulationScenarioRequest.java # Probe kind, side, and operation
│       │   │   │   │   ├── SimulationScenarioResult.java # Accepted or skipped probe outcome
│       │   │   │   │   ├── StorageStatusResponse.java # Storage usage metrics & tier breakdown
│       │   │   │   │   ├── SyncDiffReport.java      # Real-time branch ahead/behind & itemized metadata (Tags, Git Notes, Releases, LFS, CI/CD Checks)
│       │   │   │   │   ├── SyncEventMessage.java
│       │   │   │   │   ├── SyntheticWebhookRequest.java
│       │   │   │   │   ├── SystemEngineConfigRequest.java
│       │   │   │   │   ├── SystemEngineConfigResponse.java
│       │   │   │   │   └── TestConnectionRequest.java
│       │   │   │   ├── entity/
│       │   │   │   │   ├── BulkSubmission.java  # One bulk migration batch: counts, skipped-row JSON, cancelledAt
│       │   │   │   │   ├── GitHubAppConfig.java # Legacy mixed SCM row (GitLab/Bitbucket/Origin; GitHub/GHES migrated)
│       │   │   │   │   ├── ScmCredential.java   # GitHub/GHES App (multi-install ids) or PAT; bound on each pair side
│       │   │   │   │   ├── PrMapping.java       # Cross-repository PR ID & branch state tracking
│       │   │   │   │   ├── RefOrigin.java       # Branch head origination ledger (pair side, fork heads)
│       │   │   │   │   ├── RepoMapping.java     # Pair: URLs, tokens, provider, visibility, per-side credential+installationId, trunkConflictPolicy, sync checkpoints, bulkSubmissionId, destinationAutoCreate
│       │   │   │   │   ├── SyncAuditLog.java    # Line-by-line execution trace records
│       │   │   │   │   ├── SyncConflict.java    # Persisted Git-ref / tag / PR-metadata conflicts
│       │   │   │   │   ├── SyncJob.java         # Per-event sync execution record
│       │   │   │   │   ├── SystemEngineConfig.java # Persistent engine, retries & storage configuration
│       │   │   │   │   └── UnmappedWebhookEvent.java # Discarded/unmapped webhook audit record (7-day retention)
│       │   │   │   └── enums/
│       │   │   │       ├── LogLevel.java        # INFO, WARN, ERROR, DEBUG
│       │   │   │       ├── ConflictKind.java    # GIT_REF, TAG, METADATA, PR
│       │   │   │       ├── PairSide.java        # SIDE_A, SIDE_B
│       │   │   │       ├── ScmProviderType.java # GITHUB, GHES, GITLAB, BITBUCKET, ORIGIN, GENERIC
│       │   │   │       ├── SyncCheckpointStage.java # NONE, PUSH_DONE, LFS_*, GIT_SYNC_DONE
│       │   │   │       ├── StorageTier.java     # HOT_PERSISTENT, AUTO_LRU, EPHEMERAL_STREAM, NAS_MOUNT
│       │   │   │       ├── SyncDirection.java   # BIDIRECTIONAL, UNIDIRECTIONAL_A_TO_B, etc.
│       │   │   │       ├── TrunkConflictPolicy.java # ISOLATE, FAIL_JOB, ORIGIN_WINS
│       │   │   │       ├── SyncStatus.java      # QUEUED, IN_PROGRESS, SUCCESS, FAILED, DLQ, CONFLICT_ISOLATED, CANCELLED, INTERRUPTED, PAUSED
│       │   │   │       └── TriggerType.java     # WEBHOOK, MANUAL, SYNTHETIC, DLQ_REDRIVE, INITIAL_BOOTSTRAP
│       │   │   ├── persistence/                 # Pluggable persistence (h2 | mongo) — mirrors messaging/
│       │   │   │   ├── PersistenceProvider.java # enum h2 (default) | mongo; from() + fail-fast unknown
│       │   │   │   ├── PersistenceConditions.java # @OnH2 / @OnMongo bean conditions
│       │   │   │   ├── PersistenceEnvironmentPostProcessor.java # symmetric autoconfig exclusion (single active store)
│       │   │   │   ├── PersistenceModule.java   # Descriptor for UI /api/v1/persistence
│       │   │   │   ├── Ids.java                 # Shared ObjectId-hex id generator (both stores)
│       │   │   │   └── store/                   # MongoSchemaInitializer, lifecycle listener, WritePreparer, scanning configs
│       │   │   ├── repository/                  # Store facades — one plain interface per aggregate; exactly one provider store active
│       │   │   │   ├── BulkSubmissionRepository.java … WriteAuthorityRepository.java  # store facades, one H2 or Mongo implementation each
│       │   │   │   ├── h2/                      # @OnH2: XJpaRepository (Spring Data JPA) + H2XStore delegating impls
│       │   │   │   └── mongo/                   # @OnMongo: XMongoRepository + MongoXStore (MongoTemplate ports)
│       │   │   ├── security/                    # Encryption & Secret Management
│       │   │   │   ├── CryptoService.java       # AES-256-GCM encryption & key derivation
│       │   │   │   ├── EncryptedStringConverter.java # JPA column encryption converter
│       │   │   │   └── Encrypted.java           # Field marker shared by JPA converter + Mongo listener
│       │   │   └── service/                     # Core Business Logic & Orchestration
│       │   │       ├── BareRepoHousekeeping.java # Mirror directory prep before fetch/push
│       │   │       ├── BulkMirrorService.java   # POST /api/v1/mappings/bulk: probes, skip rules, one mapping + bootstrap job per row
│       │   │       ├── BulkSubmissionService.java # Submission lifecycle, bulk cancel, destination-create fail-fast
│       │   │       ├── CircuitBreakerManagerService.java # Tri-state self-healing breaker & automated SCM prober
│       │   │       ├── DedupLedgerService.java  # Shared echo_ledger (SHA, ref delete, mirrored PR) plus App-sender skip
│       │   │       ├── ReplicaRulesetService.java # Lock / unlock / swap gitmirror-replica-readonly on GitHub and GHES
│       │   │       ├── WriteAuthorityService.java # Pair, org, and enterprise read-only rulesets; enterprise slug probe
│       │   │       ├── DiffInspectionPipeline.java # Ephemeral async sync-diff inspection pipeline
│       │   │       ├── DiffInspectionProgressService.java # Live diff inspection progress broadcasts
│       │   │       ├── DlqRedriveService.java   # DLQ replay & queue depth inspection
│       │   │       ├── EnterpriseLoggingService.java # Multi-sink audit forwarder (Splunk HEC, ELK, Syslog, File)
│       │   │       ├── GitHubAuthService.java   # GitHub App/PAT authentication & token validation
│       │   │       ├── GitComparisonService.java# Branch ahead/behind calculation & diff inspection (GraphQL snapshot fast path)
│       │   │       ├── GitLfsSyncService.java   # Parallel LFS pointer scan & batched blob streaming with checkpoint resume
│       │   │       ├── GitSyncEngine.java       # JGit public-first fetch (+ per-ref fetch verification), dest WRITE preflight, resumable batched push, full-mirror FF/isolate, conflict PR open
│       │   │       ├── HubMetrics.java          # Low-cardinality Micrometer gauges/timers for Internals UI
│       │   │       ├── GitWireByteMeter.java    # JGit smart-HTTP byte counter for transfer metrics
│       │   │       ├── JobExecutionStateService.java # Per-job pipeline cursor & stage progress for pause/resume
│       │   │       ├── JobCancelledException.java / JobPausedException.java # Cooperative cancel/pause signals
│       │   │       ├── PairDiffSnapshotService.java # Cached diff counts on repo_mappings
│       │   │       ├── PairMirrorSnapshotService.java # Persist branch/tag/LFS stats after git phases
│       │   │       ├── PrMirrorSupport.java     # Shared PR mirror helpers (head prep, fork resolution)
│       │   │       ├── RefOriginService.java    # Branch origination ledger; blocks reverse-sync of fork/synthetic heads
│       │   │       ├── RefInterestPolicy.java   # Ephemeral/agent denylist, branchPattern, incremental coalesce
│       │   │       ├── StartupJobRecoveryService.java # Pause consumers on boot; mark orphan IN_PROGRESS as INTERRUPTED
│       │   │       ├── SyncCheckpointService.java # Pair-level LFS/git resume checkpoints
│       │   │       ├── SyncConflictService.java # Persist Git-ref/tag/metadata conflicts; open destination conflict PRs
│       │   │       ├── LiveGitProgressMonitor.java # Throttled JGit ProgressMonitor → JOB_PROGRESS (ETA, remote labels, pipeline, traffic)
│       │   │       ├── SyncPipelineState.java   # Outer sync recipe stages (pending/current/done/skipped/failed)
│       │   │       ├── ProviderRateMeter.java   # Job-scoped REST vs Git-smart-HTTP counters + rate-limit headers
│       │   │       ├── JobCancellationService.java # Cancel flags for in-flight JGit; never interrupt AMQP listener
│       │   │       ├── InboundWebhookConsumerService.java # Consumes edge messages from git.sync.inbound.queue
│       │   │       ├── PullRequestSyncService.java# Pull Request, review comment, and issue comment sync
│       │   │       ├── QueueConsumerService.java# Full + incremental @RabbitListener workers; skip-ACK cancelled jobs
│       │   │       ├── RepoDirLockService.java  # Shared per-mapping bare-repo mutex (sync jobs + diff refresh)
│       │   │       ├── PairLeaseService.java    # DB lease per mappingId for multi-pod safety
│       │   │       ├── ConsumerRuntimeRegistry.java # In-process unacked slots (job, thread, lane)
│       │   │       ├── QueueObservabilityService.java # Ready vs Unacked + listener thread snapshot
│       │   │       ├── QueueProducerService.java# Routes full vs webhook jobs onto separate AMQP keys
│       │   │       ├── SyncLaneRouter.java      # Full-mirror vs incremental lane selection by ref shape
│       │   │       ├── ReleaseAndStatusSyncService.java # Release mirror (cursor-paged diff, create/update, asset streaming) + CI check-run/status backfill; per-side credential+installation binding; standalone metadata sync jobs
│       │   │       ├── RepoMappingService.java  # Mapping CRUD & manual trigger helper
│       │   │       ├── RuntimeMetricsService.java # Assembles MeterRegistry snapshot for /api/v1/runtime-metrics
│       │   │       ├── PairTipEchoService.java  # Webhook echo when the other repo already has the tip or delete
│       │   │       ├── SimulationScenarioPayload.java # GitHub payload for a Simulation Lab probe
│       │   │       ├── SimulationService.java   # Pause/resume, chaos faults, synthetic push, scenario probes
│       │   │       ├── StorageTieringService.java# LRU eviction, NAS directory routing & ephemeral pruning
│       │   │       ├── SyncJobService.java      # Job history query, retry, and cancel coordinator
│       │   │       ├── SystemEngineConfigService.java # Database persistence & dynamic hot-reload coordinator
│       │   │       ├── WebhookIngestionService.java # Core payload parsing & loop evaluation
│       │   │       └── WebSocketNotificationService.java # Broadcasts JOB_UPDATE, JOB_PROGRESS, queue, and simulation events
│       │   └── resources/
│       │       └── application.yml              # Spring Boot configuration properties
│       └── test/
│           ├── java/com/gitutility/
│           │   ├── bdd/                         # Cucumber steps for the behavioral scenarios
│           │   ├── controller/
│           │   │   ├── RootApiControllerTest.java
│           │   │   └── WebhookControllerTest.java   # Ingestion & loop filter unit tests
│           │   ├── security/
│           │   │   └── CryptoServiceTest.java       # AES-256-GCM encryption tests
│           │   └── service/
│           │       ├── DedupLedgerServiceTest.java  # Ledger writes used when the engine records a push
│           │       ├── MirrorBehavior.java          # Package bridge for Cucumber (not a test)
│           │       └── SimulationServiceTest.java   # Chaos toggles & synthetic emitter tests
│           └── resources/features/              # Gherkin: trunk, echo, PR rules, simulation, queue ack
│
└── frontend/                                   # React 18 + Vite + Tailwind CSS Frontend Dashboard
    ├── package.json                            # NPM dependencies (react-router-dom, @stomp/stompjs, axios, lucide-react)
    ├── vite.config.ts                          # Vite bundler config with backend API/WS proxy
    ├── tailwind.config.js                      # Tailwind CSS theme extension
    ├── tsconfig.json                           # TypeScript configuration
    └── src/
        ├── main.tsx                            # React DOM root entry point
        ├── App.tsx                             # URL routing tree with BrowserRouter
        ├── index.css                           # Global Tailwind directives & scrollbar styling
        ├── types/
        │   └── index.ts                        # TypeScript interfaces (RepoMapping, SyncJob, QueueStatus, etc.)
        ├── services/
        │   ├── api.ts                          # Axios REST API client methods
        │   └── websocket.ts                    # STOMP/SockJS client for real-time `/topic/sync-events`
        ├── pages/                              # Isolated URL-routed page modules
        │   ├── RepositoriesPage.tsx            # Repository pairs dashboard & quick actions
        │   ├── RepoDetailPage.tsx              # Deep-dive view for specific repo pair (/repos/:id)
        │   ├── ObservabilityPage.tsx           # Activity stream (STOMP JOB_UPDATE + JOB_PROGRESS), queue metrics & unmapped webhooks
        │   ├── InternalsPage.tsx               # Process gauges, shared SCM quotas, usage-by-job ranking at /observability/internals
        │   ├── QueueManagerPage.tsx            # Job history, Ready vs Unacked consumer runtime, cancel, purge, DLQ redrive
        │   ├── SimulationPage.tsx              # Fault injection & chaos sandbox
        │   └── settings/                       # Dedicated Settings Subsystem
        │       ├── SettingsLayout.tsx          # Settings sidebar; shows active store from GET /api/v1/persistence
        │       ├── FeatureTogglesPage.tsx      # Public repos + optional provider capability switches
        │       ├── ProvidersAuthPage.tsx       # GitLab/Bitbucket/Origin/generic + GitHub/GHES credential lists
        │       ├── WriteAuthorityPage.tsx      # Linked pair or individual write/read at repo, org, or enterprise scope
        │       ├── ScmCredentialsPanel.tsx     # Multi GitHub/GHES App+PAT cards, multi-install select, preview list, webhook URL
        │       ├── SystemEnginePage.tsx        # Circuit Breaker, Jittered Retries & Concurrency Limits
        │       ├── StorageSettingsPage.tsx     # Storage Tiers, NVMe Disk Quotas & NAS/NFS Mounts
        │       └── EnterpriseLoggingPage.tsx   # Multi-Sink Logging (Splunk HEC, ELK, Syslog, File)
        ├── utils/
        │   ├── format.ts                         # Shared formatting helpers
        │   ├── jobTiming.ts                      # Elapsed/duration helpers for job UI
        │   ├── liveJobLogs.ts                    # Live log overlay merge for JobLogModal
        │   ├── mirrorTopology.ts                 # Pair direction / topology helpers
        │   ├── pairHealthFlags.ts                # Repo pair health badge logic
        │   ├── repoUrl.ts                        # SCM URL parsing & normalization
        │   └── syncDiff.ts                       # Client-side sync-diff helpers
        └── components/
            ├── Header.tsx                      # Top navigation bar & global link routing
            ├── BannerHero.tsx                  # Top banner with KPI summaries and quick actions
            ├── BulkMigrationTab.tsx            # Add-pair modal Bulk tab: multi-select sources, dest strategy, review + submit
            ├── BulkSubmissionsPanel.tsx        # Queue Manager: submission outcomes and Cancel all
            ├── BranchComparisonTable.tsx       # Paginated branch ahead/behind table with search/filter
            ├── ConsumerRuntimePanel.tsx        # Per-lane Ready/Unacked consumer thread snapshot
            ├── DiffInspectionModal.tsx         # Async sync-diff inspection progress modal
            ├── MetricsOverview.tsx             # Dashboard KPI cards (queue, jobs, pairs)
            ├── MirrorPairsView.tsx             # Alternate mirror-pairs list layout
            ├── RepoListView.tsx                # Active repositories list view & filtering
            ├── RepoDetailView.tsx              # Deep-dive view for selected repo (code diffs, PRs, itemized Tags/Releases/LFS/CI tabs, settings)
            ├── LiveSyncTable.tsx               # Real-time event activity stream; pipeline stage + live object progress + elapsed
            ├── JobProgressBar.tsx              # Shared fetch/push object progress bar with elapsed/ETA
            ├── SyncPipelineStepper.tsx         # Outer sync recipe (done/current/failed/pending); skip-stage actions
            ├── SyncRunsHistoryModal.tsx        # Job run history with pause/resume/retry actions
            ├── ProviderTrafficStrip.tsx        # Per-job REST/GraphQL/Git call totals + this-run volume chart
            ├── ClusterFleetStrip.tsx           # Live/known pods, thread chips, install API fleet table
            ├── JobExecutionSummary.tsx         # Run recap: stage timings, git/LFS bytes, artifact counts
            ├── ProviderSettingsView.tsx        # Legacy/alternate provider settings component
            ├── QueueControlPanel.tsx           # Consumer pause/resume, DLQ redrive, and bulk submission panel
            ├── SimulationLab.tsx               # Chaos sandbox, synthetic push, and scenario probes
            ├── SimulationProbeGuide.tsx        # Operator steps for each probe kind
            ├── JobLogModal.tsx                 # Audit log drawer; live overlay, pipeline, provider API, rejected refs
            ├── PairConfigModal.tsx             # Add/Edit mirror pair; Single pair | Bulk migration tabs; Check Access with Auto/Public/Private
            ├── CredentialPickModal.tsx         # Quick GitHub App/PAT picker opened when Check Access needs authentication
            ├── RepoPickerModal.tsx             # Search-first multi-provider repo explorer; optional multi-select that survives paging and search
            ├── InfoTooltip.tsx                 # Contextual parameter explanation tooltip component
            └── UnmappedWebhooksView.tsx        # Discarded & unmapped webhooks stream with 1-click configure action
```

> **Tests:** `backend/src/test/java/com/gitutility/` contains ~73 JUnit classes covering sync resume, GraphQL parsers, queue skip-ACK, checkpoints, LFS, conflicts, and controller integration. The tree above lists representative tests only. Cucumber scenarios in `backend/src/test/resources/features/` run in the same Gradle/Maven test task (46 scenarios: trunk divergence, release echo, pull-request mirror rules, simulation lab, queue acknowledgement, webhook echo). Persistence facades are additionally proven by the store contract suite in `persistence/contract/` (`H2StoreContractTest` runs on every build; `MongoStoreContractTest` runs against a real MongoDB supplied via `MONGO_CONTRACT_URI` and skips when unset — no Docker/Testcontainers; see `INSTRUCTIONS.md`).

---

## 2. Component Responsibility Matrix

| Module | Component | Primary Responsibility |
| :--- | :--- | :--- |
| **Edge Gateway** | `webhook-worker/src/index.ts` | Edge serverless gateway (Cloudflare Workers) that verifies HMAC signatures, packages inbound webhook envelopes, and pushes to RabbitMQ via HTTP Management API in <15ms. |
| **Backend** | `WebhookIngestionService` | Core logic for parsing push payloads, applying `RefInterestPolicy` (ephemeral/pattern/coalesce), verifying loop echo signatures in `DedupLedgerService`, and enqueuing jobs. |
| **Backend** | `RefInterestPolicy` | Agentic churn gate: ephemeral prefixes, `branchPattern`, non-trunk coalesce window. |
| **Backend** | `InboundWebhookConsumerService` | `@RabbitListener` consuming from `git.sync.inbound.queue` pushed by Cloudflare Worker or external gateways. |
| **Backend** | `DatabaseSchemaMigrator` | Automatically evolves persistent H2 database schemas on startup (`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`) without manual database wipes. |
| **Backend** | `RepoDirLockService` | Shared per-mapping `ReentrantLock` registry guarding the bare repo directory: sync jobs (`QueueConsumerService`) and `GitComparisonService` refresh-mode diffs serialize on the same lock so two JGit operations never race loose-ref writes (`LOCK_FAILURE`). |
| **Backend** | `GitComparisonService` | Live JGit & SCM introspection calculating branch ahead/behind diffs, tag notes, LFS pointer trees, release assets, and CI/CD check runs. |
| **Backend** | `GitSyncEngine` | Executes JGit bare repository operations with destination WRITE preflight, public-first source fetch, skip re-fetch when packs exist, destination credential refresh per push batch, resumable ref-batched push (`completed_push_refs` only for OK/UP_TO_DATE), abort remaining full-mirror batches on first `REJECTED_*`, dual-write audit, pack/LFS volume metrics, and conflict-isolated push (full-mirror and incremental). Option-1 bulk rows create the destination repo at job start when `destinationAutoCreate` is set. |
| **Backend** | `BulkMirrorService` | `POST /api/v1/mappings/bulk`: skip and probe rules, then one mapping plus a bootstrap full-mirror job per surviving row, recorded on `bulk_submissions`. |
| **Backend** | `BulkSubmissionService` | Lists a submission's outcomes, cancels its queued and in-progress jobs, and fail-fast-cancels sibling queued jobs when destination auto-create hits an access error. |
| **Backend** | `SyncConflictService` | Persists split-brain / tag / PR-metadata conflicts and opens destination PRs from `sync-conflict/*` isolation branches. |
| **Backend** | `LiveGitProgressMonitor` | Throttled JGit `ProgressMonitor` (~400ms). Phase changes are INFO audit rows; ticks are DEBUG-only and emit `JOB_PROGRESS` (ETA, source/destination labels, pipeline, provider traffic) over STOMP. |
| **Backend** | `ProviderRateMeter` | ThreadLocal job-scoped REST vs GraphQL vs Git counters and a sampled call-volume series. WARN audit on REST 429 or remaining &lt; 20%. Remaining quota is installation-wide; job attribution uses call counts. |
| **Backend** | `SyncPipelineState` | Outer mirror stages persisted on `sync_jobs.pipeline_json` and live on `JOB_PROGRESS`. |
| **Backend** | `PublicReadProbe` | Shared anonymous HTTPS / `git ls-remote` probe used by all SCM adapters so public read is the primary Check Access path. |
| **Backend** | `PairTipEchoService` | Webhook echo check: skip a push only when the other repository already advertises that tip, and a delete only when that ref is already gone. An unknown peer is not an echo. |
| **Backend** | `DedupLedgerService` | Still records tip SHAs, ref deletes, and `pr:<number>` when the engine writes. The inbound webhook skip uses `PairTipEchoService`, not this row. |
| **Backend** | `QueueProducerService` | Builds `SyncEventMessage` and publishes via pluggable `SyncEventBus` (Rabbit or inline). |
| **Backend** | `MessagingModule` / `SyncEventBus` | `GIT_MESSAGING_PROVIDER=rabbitmq\|kafka\|none`; descriptor at `GET /api/v1/messaging`. |
| **Backend** | `WebhookIncrementalService` | `GIT_WEBHOOK_BUS_PROVIDER=kafka\|rabbitmq\|off`. One incremental lane. `GET /api/v1/webhook-bus`, `POST /api/v1/webhook-bus/redrive`. |
| **Edge** | `webhook-worker-kafka/` | Optional Cloudflare script. HMAC then Confluent REST produce of a normalized git event. |
| **Backend** | `SyncLaneRouter` | Shared full vs incremental routing rule used by producer, engine, DLQ redrive, and consumers. |
| **Backend** | `QueueConsumerService` | Shared execution path; Rabbit lane listeners live in `messaging.rabbit.RabbitLaneConsumers` when provider=`rabbit`. |
| **Backend** | `ConsumerRuntimeRegistry` | Tracks the AMQP thread currently holding an unacked message per lane (job, thread liveness, elapsed). |
| **Backend** | `QueueObservabilityService` | Builds Ready vs Unacked plus idle/unused/dead listener thread stats for `/api/v1/queue/status`. |
| **Backend** | `DlqRedriveService` | Inspects queue depths, replays `git.sync.dlq` onto the original execution lane by ref shape. |
| **Backend** | `CryptoService` | Envelope AES-256-GCM encryption for stored PAT tokens and webhook secrets. Requires `GIT_UTILITY_ENCRYPTION_KEY` (32+ chars); boot fails if unset. |
| **Backend** | `SimulationService` | Consumer pause/resume, injected 500/503/429 faults, synthetic push, `GET /api/v1/simulation/ref-tip`, and `POST /api/v1/simulation/emit-scenario` probes (branch, tag, note, pull request, release, status, check run). |
| **Backend** | `JobExecutionStateService` | Persists per-job pipeline cursor and stage progress; drives pause/resume/skip-stage decisions. |
| **Backend** | `SyncCheckpointService` | Pair-level LFS/git resume checkpoints on `repo_mappings`. |
| **Backend** | `StartupJobRecoveryService` | Pauses consumers on boot; marks orphan `IN_PROGRESS` jobs as `INTERRUPTED`. |
| **Backend** | `GithubGraphQlClient` | GitHub/GHES GraphQL fast path for PR pages, mirror snapshots, and releases (REST fallback). |
| **Backend** | `RefOriginService` | Tracks branch head origination; prevents reverse-sync of fork/synthetic heads. |
| **Backend** | `GitWireByteMeter` | Counts JGit smart-HTTP wire bytes for accurate transfer metrics. |
| **Backend** | `WebSocketNotificationService` | Emits real-time STOMP notifications on `/topic/sync-events` for `JOB_UPDATE`, `JOB_PROGRESS`, queue counts, and simulation changes. |
| **Frontend** | `App.tsx` | Master application state, WebSocket subscription management, and main tab navigation. |
| **Frontend** | `RepoListView` / `RepoDetailView` | Visual pair exploration, branch filter inspections, and SCM configuration. |
| **Frontend** | `ProviderSettingsView` | SCM Provider credentials (GitHub App, GitLab, Bitbucket) and webhook secrets. |
| **Frontend** | `QueueControlPanel` & `SimulationLab` | Chaos engineering sandbox, pause/resume downtime controls, and DLQ redrive tools. |
| **Frontend** | `SyncRunsHistoryModal` / `SyncPipelineStepper` | Job history with pause/resume/retry; per-stage skip controls. |
| **Frontend** | `Settings Pages` | Dedicated MPA views for Providers, System Engine, Storage Tiers, and Enterprise Logging. |

---

## 3. Frontend Route & Backend Endpoint Catalog

### Frontend MPA Route Structure
* `/` or `/repos`: Active repository mirror pairs, creation modal, and instant synchronization.
* `/repos/:id`: Deep-dive inspection matrix (`Branches & Commits`, `Pull Requests Mirror`, `Git Metadata, LFS & Releases`, `Storage & Settings`).
* `/observability`: Live sync event stream, queue depth telemetries, and discarded/unmapped webhook inspector.
* `/queues`: Queue Manager (`rabbitmq`) or Execution (`none`) — job history, pause/resume, cancel, bulk submission outcomes; broker ops when `messaging.provider=rabbitmq`.
* `/kafka`: Incremental Kafka consumer group (pending, committed, stored failures). Nav link only when `GIT_WEBHOOK_BUS_PROVIDER=kafka`.
* `GET /api/v1/messaging`: Active messaging module descriptor for the operator UI.
* `/simulation`: Outage simulation sandbox, consumer pausing, and synthetic push generator.
* `/settings/providers`: SCM Provider configurations (GitHub App, GitLab, Bitbucket, Origin, Azure DevOps).
* `/settings/providers`: SCM provider authentication (GitHub/GHES always; GitLab/Bitbucket/Origin/Generic when enabled).
* `/settings/dr`: Disaster recovery by provider pair. Organizations and enterprises are the lock scopes; repositories are a detail list inside the lane.
* `/settings/write-authority`: Replica rulesets. Linked pair or individual write/read, at repo, org, or enterprise scope.
* `/settings/feature-toggles`: Product capability switches (public repos, optional providers). Saved in DB; env `FEATURE_*` seeds first row only.
* `/settings/metadata`: Global metadata sync switches (pull requests, releases, CI checks, Git LFS). `GET`/`PUT /api/v1/metadata-sync-settings`. Off skips that stage, webhook, and pair-page action. Git ref push stays on.
* `/settings/system-engine`: Self-healing circuit breaker, jittered retry strategy, and concurrency limits.
* `/settings/storage`: Storage tier quotas (HOT, LRU, EPHEMERAL, NAS), manual LRU eviction, and mount tests.
* `/settings/logging`: Enterprise multi-sink logging setup (Console, Splunk HEC, Logstash, Syslog, Rolling File).

### Backend REST API Highlights
* `GET /api/v1/mappings/:id/sync-diff`: Live JGit & SCM difference report (`refresh`, `metadata`, branch pagination/search params). Itemized tags, releases, LFS pointers, and CI checks.
* `POST /api/v1/mappings/:id/sync-prs` / `sync-lfs`: Standalone metadata sync actions.
* `POST /api/v1/mappings/:id/sync-releases` / `sync-ci-checks`: Launch a full release mirror / CI check backfill as a visible `MANUAL` job (`202` + `jobId`); progress streams to Queue Manager, the pipeline stepper, and the audit log.
* `GET /api/v1/mappings/:id/conflicts`: Open and historical Git-ref / tag / PR-metadata conflicts for a pair.
* `POST /api/v1/mappings/:id/conflicts/:conflictId/resolve`: Acknowledge a conflict.
* `POST /api/v1/mappings/:id/conflicts/:conflictId/open-pr`: Retry opening a destination PR from an isolated conflict branch.
* `POST /api/v1/mappings/:id/replica-ruleset`: Lock, unlock, or swap the GitHub/GHES replica ruleset (`action`: `lock` | `unlock` | `swap`, optional `primarySide`: `A` | `B`). Bypass actor is the credential's GitHub App id.
* `GET /api/v1/dr-lanes`, `POST /api/v1/dr-lanes/activate`, `POST …/fail-back`, `POST …/probe`: Provider-to-provider disaster recovery. Organizations and enterprises are the lock scopes; repositories are nested detail.
* `GET /api/v1/mappings/:id/peer-status`, `POST …/peer-heartbeat`, `POST …/activate-dr`, `POST …/fail-back`: Per-pair reachability used by the lane. The Settings page is the operator control.
* `GET /api/v1/write-authority`: Pairs, selected org installations, and enterprise rows, including which scope chips are enabled.
* `POST /api/v1/write-authority`: Apply a linked or independent pair, or set one org or enterprise write/read. `confirmAllRepos` must be `ALL REPOS` when a read-only placement covers every repository.
* `POST /api/v1/mappings/bulk`: Submit a bulk migration. One mapping and one bootstrap full-mirror job per source. Response rows are `CREATED_QUEUED`, `SKIPPED`, or `FAILED_VALIDATION`, plus `submissionId` and counts.
* `GET /api/v1/bulk`: List bulk submissions (counts and skipped-row JSON).
* `GET /api/v1/bulk/:id`: One submission, including rows that never became jobs.
* `POST /api/v1/bulk/:id/cancel`: Cancel queued and in-progress jobs created by that submission.
* `POST /api/v1/system-engine/circuit-breaker/probe-and-reset`: Admin force-reset and connectivity probe for tripped circuit breakers.
* `GET /api/v1/storage/status`: Real-time disk usage, cache counts, and storage tier breakdown.
* `POST /api/v1/storage/evict-now`: Triggers LRU eviction on stale bare Git caches.
* `GET /api/v1/github-app/search-repositories`: Debounced multi-provider repository explorer with pagination.
* `POST /api/v1/github-app/create-repo`: On-demand remote repository creation via provider adapter.
* `GET /api/v1/unmapped-webhooks`: Discarded webhook audit log with 7-day retention.
* `GET /api/v1/jobs`: Paginated job history (`status`, `mappingId`, `triggerType`, `lane=FULL|INCREMENTAL`).
* `GET /api/v1/jobs/usage`: Rank jobs by attributable REST + GraphQL + Git call volume (`since`, `limit`) for Internals rate-limit triage.
* `POST /api/v1/jobs/:id/pause` / `resume` / `skip-stage`: Cooperative job control for long-running mirrors.
* `POST /api/v1/jobs/dispatch`: Manually dispatch a queued/resumable job after startup consumer pause.
* `POST /api/v1/jobs/:id/cancel`: Cancel a queued or in-progress job (in-flight JGit abort via ProgressMonitor; AMQP ACK on pickup).
* `POST /api/v1/jobs/cancel-queued`: Cancel all queued jobs, optionally filtered by `mappingId`.
* `POST /api/v1/queue/purge`: Purge waiting full + incremental queue messages and mark remaining queued jobs cancelled.
* `POST /api/v1/queue/inbound/purge`: Purge buffered inbound webhook queue.
* `GET /api/v1/runtime-metrics`: Process Micrometer snapshot for Internals (JVM, executors, lanes, CB, job outcomes).

