# GitMirror Hub - Repository Code Map

This document provides a comprehensive navigation guide to the **GitMirror Hub** codebase, outlining the responsibilities, dependencies, and key classes across backend, frontend, and serverless edge worker modules.

---

## 1. Directory Tree Structure

```
gitUtility/
├── ARCHITECTURE.md                     # Master design (incl. §3.6.1 enterprise throughput / one-job-one-pod)
├── REPO_MAP.md                         # Codebase map and component index (this file)
├── INSTRUCTIONS.md                     # Operational guide, runbook, and failover instructions
├── INSTRUCTIONS-MULTI-POD.md           # Local multi-pod (2+ backend JVMs) runbook
├── SECURITY.md                         # Localhost-only threat model & secret handling
├── LICENSE                             # MIT
├── env.pod-a.example                   # Template env for pod-a (copy → gitignored env.pod-a)
├── env.pod-b.example                   # Template env for pod-b (copy → gitignored env.pod-b)
├── README.md                           # Quickstart summary
├── future-work/                        # Deferred backlog design plans (not shipped behavior)
│   ├── README.md                       # Pending vs done index
│   ├── cache-resume-worker-affinity.md # Resume, cache, affinity (partial)
│   ├── pr-sync-parity.md               # Richer PR sync (shell shipped; A–E pending)
│   ├── fanout-concurrency.md           # In-job fan-out (LFS/PR pools shipped)
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
│   ├── wrangler.toml                   # Cloudflare Worker deployment configuration & env vars
│   ├── README.md                       # Wrangler login, secret setup, and deployment runbook
│   └── src/
│       └── index.ts                    # Edge router: WebCrypto HMAC validation & RabbitMQ HTTP publish
│
├── backend/                            # Spring Boot 3.3.3 Backend Application (Java 21/23)
│   ├── pom.xml                         # Maven project configuration (Web, JPA, AMQP, JGit, H2)
│   └── src/
│       ├── main/
│       │   ├── java/com/gitutility/
│       │   │   ├── GitUtilityApplication.java   # Spring Boot entry point & seed data runner
│       │   │   ├── config/                      # Infrastructure & Spring bean configurations
│       │   │   │   ├── AsyncConfig.java         # Thread pool task executor configuration
│       │   │   │   ├── CorsConfig.java          # Localhost-only CORS for the operator UI (override via GIT_CORS_*)
│       │   │   │   ├── DatabaseSchemaMigrator.java # Automatic schema evolution & missing column migration on startup
│       │   │   │   ├── ScmRestTemplateFactory.java # Shared RestTemplate + ProviderRateMeter interceptor
│       │   │   │   ├── RabbitMQConfig.java      # Exchanges, Queues, DLX, DLQ, and retry advice
│       │   │   │   └── WebSocketConfig.java     # STOMP over WebSocket broker configuration
│       │   │   ├── controller/                  # REST Controllers & Webhook endpoints
│       │   │   │   ├── GlobalExceptionHandler.java # Central REST error mapping
│       │   │   │   ├── RootApiController.java   # Root info endpoint (/ and /api/v1)
│       │   │   │   ├── GitHubAppController.java # Legacy god-row GitLab/Bitbucket/Origin config + GitHub shim
│       │   │   │   ├── ScmCredentialController.java # GitHub/GHES credential list CRUD, picker search, webhook URLs
│       │   │   │   ├── QueueController.java     # Queue stats, DLQ redrive, main/inbound/DLQ purge
│       │   │   │   ├── RuntimeMetricsController.java # Micrometer snapshot for Internals UI (/api/v1/runtime-metrics)
│       │   │   │   ├── RepoMappingController.java# Repo mapping CRUD, manual sync, PRs/LFS/Releases sync & diff endpoints
│       │   │   │   ├── SimulationController.java# Fault injection & synthetic webhook emitter
│       │   │   │   ├── StorageController.java   # Local & NAS mirror disk quota & LRU eviction API
│       │   │   │   ├── SyncJobController.java   # Sync job querying, stats, retry, and cancel
│       │   │   │   ├── SystemEngineConfigController.java # System engine, retries, NAS validator & circuit breaker reset
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
│       │   │   │   │   ├── CreateRepoRequest.java
│       │   │   │   │   ├── GitHubAppConfigRequest.java
│       │   │   │   │   ├── GitHubPushPayload.java
│       │   │   │   │   ├── GitHubRepoOption.java    # Multi-provider repository option with provider, namespace & permissions
│       │   │   │   │   ├── InboundWebhookMessage.java
│       │   │   │   │   ├── JobStageProgress.java    # Per-stage resume progress (PR/LFS cursors)
│       │   │   │   │   ├── MirrorMetadataSnapshot.java # GraphQL mirror snapshot DTO
│       │   │   │   │   ├── PairDiffSnapshot.java  # Cached pair diff counts for repo detail
│       │   │   │   │   ├── PermissionCheckReport.java
│       │   │   │   │   ├── PrListPage.java          # Paginated open PR page (GraphQL cursor / REST Link)
│       │   │   │   │   ├── PullRequestSnapshot.java
│       │   │   │   │   ├── ProviderConfigResponse.java
│       │   │   │   │   ├── QueueStatusResponse.java
│       │   │   │   │   ├── RepoMappingResponse.java
│       │   │   │   │   ├── RepoSearchResult.java       # Paginated multi-provider search results DTO
│       │   │   │   │   ├── RuntimeMetricsResponse.java # Internals UI Micrometer snapshot
│       │   │   │   │   ├── SimulationConfigRequest.java
│       │   │   │   │   ├── StorageStatusResponse.java # Storage usage metrics & tier breakdown
│       │   │   │   │   ├── SyncDiffReport.java      # Real-time branch ahead/behind & itemized metadata (Tags, Git Notes, Releases, LFS, CI/CD Checks)
│       │   │   │   │   ├── SyncEventMessage.java
│       │   │   │   │   ├── SyntheticWebhookRequest.java
│       │   │   │   │   ├── SystemEngineConfigRequest.java
│       │   │   │   │   ├── SystemEngineConfigResponse.java
│       │   │   │   │   └── TestConnectionRequest.java
│       │   │   │   ├── entity/
│       │   │   │   │   ├── GitHubAppConfig.java # Legacy mixed SCM row (GitLab/Bitbucket/Origin; GitHub/GHES migrated)
│       │   │   │   │   ├── ScmCredential.java   # One GitHub/GHES App install or PAT (bound on each pair side)
│       │   │   │   │   ├── PrMapping.java       # Cross-repository PR ID & branch state tracking
│       │   │   │   │   ├── RefOrigin.java       # Branch head origination ledger (pair side, fork heads)
│       │   │   │   │   ├── RepoMapping.java     # Pair: URLs, tokens, provider, visibility, trunkConflictPolicy, sync checkpoints
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
│       │   │   ├── repository/                  # Spring Data JPA Repositories
│       │   │   │   ├── GitHubAppConfigRepository.java
│       │   │   │   ├── PrMappingRepository.java
│       │   │   │   ├── RefOriginRepository.java
│       │   │   │   ├── RepoMappingRepository.java
│       │   │   │   ├── SyncAuditLogRepository.java
│       │   │   │   ├── SyncJobRepository.java
│       │   │   │   ├── SyncConflictRepository.java
│       │   │   │   ├── SystemEngineConfigRepository.java
│       │   │   │   └── UnmappedWebhookEventRepository.java
│       │   │   ├── security/                    # Encryption & Secret Management
│       │   │   │   ├── CryptoService.java       # AES-256-GCM encryption & key derivation
│       │   │   │   └── EncryptedStringConverter.java # JPA column encryption converter
│       │   │   └── service/                     # Core Business Logic & Orchestration
│       │   │       ├── BareRepoHousekeeping.java # Mirror directory prep before fetch/push
│       │   │       ├── CircuitBreakerManagerService.java # Tri-state self-healing breaker & automated SCM prober
│       │   │       ├── DedupLedgerService.java  # Echo loop prevention & commit tracking
│       │   │       ├── DiffInspectionPipeline.java # Ephemeral async sync-diff inspection pipeline
│       │   │       ├── DiffInspectionProgressService.java # Live diff inspection progress broadcasts
│       │   │       ├── DlqRedriveService.java   # DLQ replay & queue depth inspection
│       │   │       ├── EnterpriseLoggingService.java # Multi-sink audit forwarder (Splunk HEC, ELK, Syslog, File)
│       │   │       ├── GitHubAuthService.java   # GitHub App/PAT authentication & token validation
│       │   │       ├── GitComparisonService.java# Branch ahead/behind calculation & diff inspection (GraphQL snapshot fast path)
│       │   │       ├── GitLfsSyncService.java   # Parallel LFS pointer scan & batched blob streaming with checkpoint resume
│       │   │       ├── GitSyncEngine.java       # JGit public-first fetch, dest WRITE preflight, resumable batched push, full-mirror FF/isolate, conflict PR open
│       │   │       ├── HubMetrics.java          # Low-cardinality Micrometer gauges/timers for Internals UI
│       │   │       ├── InstallApiUsageTracker.java # Process REST usage by GitHub App install / PAT key
│       │   │       ├── InstanceIdentity.java    # Pod/JVM instance id (HOSTNAME / GIT_UTILITY_INSTANCE_ID)
│       │   │       ├── InstanceHeartbeatService.java # Micrometer heartbeat writer + cluster Internals aggregate
│       │   │       ├── PairLeaseService.java    # DB lease per mappingId for multi-pod safety
│       │   │       ├── ClusterRuntimeService.java # Shared consumer pause / CB desired state
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
│       │   │       ├── ConsumerRuntimeRegistry.java # In-process unacked slots (job, thread, lane)
│       │   │       ├── QueueObservabilityService.java # Ready vs Unacked + listener thread snapshot
│       │   │       ├── QueueProducerService.java# Routes full vs webhook jobs onto separate AMQP keys
│       │   │       ├── SyncLaneRouter.java      # Full-mirror vs incremental lane selection by ref shape
│       │   │       ├── ReleaseAndStatusSyncService.java # CI Commit Status and Release asset replication
│       │   │       ├── RepoMappingService.java  # Mapping CRUD & manual trigger helper
│       │   │       ├── RuntimeMetricsService.java # Assembles MeterRegistry snapshot for /api/v1/runtime-metrics
│       │   │       ├── SimulationService.java   # Consumer pause/resume & chaos injection
│       │   │       ├── StorageTieringService.java# LRU eviction, NAS directory routing & ephemeral pruning
│       │   │       ├── SyncJobService.java      # Job history query, retry, and cancel coordinator
│       │   │       ├── SystemEngineConfigService.java # Database persistence & dynamic hot-reload coordinator
│       │   │       ├── WebhookIngestionService.java # Core payload parsing & loop evaluation
│       │   │       └── WebSocketNotificationService.java # Broadcasts JOB_UPDATE, JOB_PROGRESS, queue, and simulation events
│       │   └── resources/
│       │       └── application.yml              # Spring Boot configuration properties
│       └── test/java/com/gitutility/
│           ├── controller/
│           │   ├── RootApiControllerTest.java
│           │   └── WebhookControllerTest.java   # Ingestion & loop filter unit tests
│           ├── security/
│           │   └── CryptoServiceTest.java       # AES-256-GCM encryption tests
│           └── service/
│               ├── DedupLedgerServiceTest.java  # Echo detection & TTL expiration unit tests
│               └── SimulationServiceTest.java   # Chaos toggles & synthetic emitter tests
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
        │   ├── ObservabilityPage.tsx           # Activity stream + cluster fleet strip (pods / install API usage)
        │   ├── InternalsPage.tsx               # Cluster Micrometer (pods, threads, executors, install API) at /observability/internals
        │   ├── QueueManagerPage.tsx            # Job history, Ready vs Unacked consumer runtime, cancel, purge, DLQ redrive
        │   ├── SimulationPage.tsx              # Fault injection & chaos sandbox
        │   └── settings/                       # Dedicated Settings Subsystem
        │       ├── SettingsLayout.tsx          # Settings sidebar & sub-navigation shell
        │       ├── ProvidersAuthPage.tsx       # GitLab/Bitbucket/Origin/generic + GitHub/GHES credential lists
        │       ├── ScmCredentialsPanel.tsx     # Multi GitHub/GHES App+PAT cards, install pick, webhook URL
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
            ├── ClusterFleetStrip.tsx           # Live/known pods, thread chips, install API fleet table
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
            ├── ProviderTrafficStrip.tsx        # Per-job REST vs Git-smart-HTTP usage on log details
            ├── ProviderSettingsView.tsx        # Legacy/alternate provider settings component
            ├── QueueControlPanel.tsx           # Consumer pause/resume & 1-click DLQ Redrive panel
            ├── SimulationLab.tsx               # Chaos sandbox: outage toggles & synthetic webhook form
            ├── JobLogModal.tsx                 # Audit log drawer; live overlay, pipeline, provider API, rejected refs
            ├── PairConfigModal.tsx             # Add/Edit mirror pair configuration modal with smart URL detection
            ├── RepoPickerModal.tsx             # Lazy-loading multi-provider repository explorer with server-side search
            ├── InfoTooltip.tsx                 # Contextual parameter explanation tooltip component
            └── UnmappedWebhooksView.tsx        # Discarded & unmapped webhooks stream with 1-click configure action
```

> **Tests:** `backend/src/test/java/com/gitutility/` contains ~40 test classes covering sync resume, GraphQL parsers, queue skip-ACK, checkpoints, LFS, conflicts, and controller integration. The tree above lists representative tests only.

---

## 2. Component Responsibility Matrix

| Module | Component | Primary Responsibility |
| :--- | :--- | :--- |
| **Edge Gateway** | `webhook-worker/src/index.ts` | Edge serverless gateway (Cloudflare Workers) that verifies HMAC signatures, packages inbound webhook envelopes, and pushes to RabbitMQ via HTTP Management API in <15ms. |
| **Backend** | `WebhookIngestionService` | Core logic for parsing push payloads, applying `RefInterestPolicy` (ephemeral/pattern/coalesce), verifying loop echo signatures in `DedupLedgerService`, and enqueuing jobs. |
| **Backend** | `RefInterestPolicy` | Agentic churn gate: ephemeral prefixes, `branchPattern`, non-trunk coalesce window. |
| **Backend** | `InboundWebhookConsumerService` | `@RabbitListener` consuming from `git.sync.inbound.queue` pushed by Cloudflare Worker or external gateways. |
| **Backend** | `DatabaseSchemaMigrator` | Automatically evolves persistent H2 database schemas on startup (`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`) without manual database wipes. |
| **Backend** | `GitComparisonService` | Live JGit & SCM introspection calculating branch ahead/behind diffs, tag notes, LFS pointer trees, release assets, and CI/CD check runs. |
| **Backend** | `GitSyncEngine` | Executes JGit bare repository operations with destination WRITE preflight, public-first source fetch, skip re-fetch when packs exist, destination credential refresh per push batch, resumable ref-batched push (`completed_push_refs` only for OK/UP_TO_DATE), abort remaining full-mirror batches on first `REJECTED_*`, dual-write audit, pack/LFS volume metrics, and conflict-isolated push (full-mirror and incremental). |
| **Backend** | `SyncConflictService` | Persists split-brain / tag / PR-metadata conflicts and opens destination PRs from `sync-conflict/*` isolation branches. |
| **Backend** | `LiveGitProgressMonitor` | Throttled JGit `ProgressMonitor` (~400ms). Phase changes are INFO audit rows; ticks are DEBUG-only and emit `JOB_PROGRESS` (ETA, source/destination labels, pipeline, provider traffic) over STOMP. |
| **Backend** | `ProviderRateMeter` | ThreadLocal job-scoped REST interceptor plus Git smart HTTP counters. WARN audit on 429 or remaining &lt; 20%. |
| **Backend** | `SyncPipelineState` | Outer mirror stages persisted on `sync_jobs.pipeline_json` and live on `JOB_PROGRESS`. |
| **Backend** | `PublicReadProbe` | Shared anonymous HTTPS / `git ls-remote` probe used by all SCM adapters so public read is the primary Check Access path. |
| **Backend** | `DedupLedgerService` | In-memory deduplication ledger tracking recent commits pushed by this utility to eliminate bidirectional webhook ping-pong loops. |
| **Backend** | `QueueProducerService` | Publishes `SyncEventMessage` to `git.sync.exchange`. Specific refs use `git.sync.incremental.key`; null/`*` refs use `git.sync.key`. |
| **Backend** | `SyncLaneRouter` | Shared full vs incremental routing rule used by producer, engine, DLQ redrive, and consumers. |
| **Backend** | `QueueConsumerService` | Two `@RabbitListener` workers (`gitSyncFullConsumer`, `gitSyncIncrementalConsumer`) sharing per-pair locks. Skip-ACK cancelled jobs without throwing. |
| **Backend** | `ConsumerRuntimeRegistry` | Tracks the AMQP thread currently holding an unacked message per lane (job, thread liveness, elapsed). |
| **Backend** | `QueueObservabilityService` | Builds Ready vs Unacked plus idle/unused/dead listener thread stats for `/api/v1/queue/status`. |
| **Backend** | `DlqRedriveService` | Inspects queue depths, replays `git.sync.dlq` onto the original execution lane by ref shape. |
| **Backend** | `CryptoService` | Envelope AES-256-GCM encryption for stored PAT tokens and webhook secrets. Requires `GIT_UTILITY_ENCRYPTION_KEY` (32+ chars); boot fails if unset. |
| **Backend** | `SimulationService` | Controls consumer pause/resume via `RabbitListenerEndpointRegistry`, injects synthetic HTTP errors (500, 503, 429), and generates synthetic push events. |
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
* `/queues`: Queue manager — job history ledger, AMQP health (full + webhook lanes), cancel/purge, inbound buffer, and DLQ redrive.
* `/simulation`: Outage simulation sandbox, consumer pausing, and synthetic push generator.
* `/settings/providers`: SCM Provider configurations (GitHub App, GitLab, Bitbucket, Origin, Azure DevOps).
* `/settings/system-engine`: Self-healing circuit breaker, jittered retry strategy, and concurrency limits.
* `/settings/storage`: Storage tier quotas (HOT, LRU, EPHEMERAL, NAS), manual LRU eviction, and mount tests.
* `/settings/logging`: Enterprise multi-sink logging setup (Console, Splunk HEC, Logstash, Syslog, Rolling File).

### Backend REST API Highlights
* `GET /api/v1/mappings/:id/sync-diff`: Live JGit & SCM difference report (`refresh`, `metadata`, branch pagination/search params). Itemized tags, releases, LFS pointers, and CI checks.
* `POST /api/v1/mappings/:id/sync-prs` / `sync-lfs` / `sync-releases`: Standalone metadata sync actions.
* `GET /api/v1/mappings/:id/conflicts`: Open and historical Git-ref / tag / PR-metadata conflicts for a pair.
* `POST /api/v1/mappings/:id/conflicts/:conflictId/resolve`: Acknowledge a conflict.
* `POST /api/v1/mappings/:id/conflicts/:conflictId/open-pr`: Retry opening a destination PR from an isolated conflict branch.
* `POST /api/v1/system-engine/circuit-breaker/probe-and-reset`: Admin force-reset and connectivity probe for tripped circuit breakers.
* `GET /api/v1/storage/status`: Real-time disk usage, cache counts, and storage tier breakdown.
* `POST /api/v1/storage/evict-now`: Triggers LRU eviction on stale bare Git caches.
* `GET /api/v1/github-app/search-repositories`: Debounced multi-provider repository explorer with pagination.
* `POST /api/v1/github-app/create-repo`: On-demand remote repository creation via provider adapter.
* `GET /api/v1/unmapped-webhooks`: Discarded webhook audit log with 7-day retention.
* `GET /api/v1/jobs`: Paginated job history (`status`, `mappingId`, `triggerType`, `lane=FULL|INCREMENTAL`).
* `POST /api/v1/jobs/:id/pause` / `resume` / `skip-stage`: Cooperative job control for long-running mirrors.
* `POST /api/v1/jobs/dispatch`: Manually dispatch a queued/resumable job after startup consumer pause.
* `POST /api/v1/jobs/:id/cancel`: Cancel a queued or in-progress job (in-flight JGit abort via ProgressMonitor; AMQP ACK on pickup).
* `POST /api/v1/jobs/cancel-queued`: Cancel all queued jobs, optionally filtered by `mappingId`.
* `POST /api/v1/queue/purge`: Purge waiting full + incremental queue messages and mark remaining queued jobs cancelled.
* `POST /api/v1/queue/inbound/purge`: Purge buffered inbound webhook queue.
* `GET /api/v1/runtime-metrics`: Process Micrometer snapshot for Internals (JVM, executors, lanes, CB, job outcomes).
* `GET /api/v1/runtime-metrics/cluster`: Aggregated sibling heartbeats — live/known pods, JVM threads, executors, fleet `apiUsageByInstall` by GitHub App install key.

