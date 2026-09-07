export type SyncStatus = 'QUEUED' | 'IN_PROGRESS' | 'SUCCESS' | 'FAILED' | 'SKIPPED' | 'DEAD_LETTERED' | 'CONFLICT_ISOLATED' | 'CANCELLED' | 'INTERRUPTED' | 'PAUSED';
export type SyncDirection = 'BIDIRECTIONAL' | 'UNIDIRECTIONAL_A_TO_B' | 'UNIDIRECTIONAL_B_TO_A';
export type TrunkConflictPolicy = 'ISOLATE' | 'FAIL_JOB' | 'ORIGIN_WINS';
export type StorageTier = 'HOT_PERSISTENT' | 'AUTO_LRU' | 'EPHEMERAL_STREAM' | 'NAS_MOUNT';
export type TriggerType = 'WEBHOOK' | 'MANUAL' | 'INITIAL_BOOTSTRAP' | 'SYNTHETIC' | 'DLQ_REDRIVE';
export type LogLevel = 'INFO' | 'WARN' | 'ERROR' | 'DEBUG';

export interface RepoMapping {
  id: number;
  name: string;
  repoAUrl: string;
  repoBUrl: string;
  tokenA?: string;
  tokenB?: string;
  tokenAMasked?: string;
  hasTokenA?: boolean;
  tokenBMasked?: string;
  hasTokenB?: boolean;
  branchPattern: string;
  webhookSecret?: string;
  webhookSecretMasked?: string;
  hasWebhookSecret?: boolean;
  syncDirection: SyncDirection;
  trunkConflictPolicy?: TrunkConflictPolicy;
  storageTier?: StorageTier;
  active: boolean;
  lastSyncAt?: string;
  lastSyncStatus?: SyncStatus;
  sourceProvider?: string;
  targetProvider?: string;
  sourceCredentialId?: number;
  targetCredentialId?: number;
  sourceVisibility?: 'UNKNOWN' | 'PUBLIC' | 'PRIVATE';
  targetVisibility?: 'UNKNOWN' | 'PUBLIC' | 'PRIVATE';
  hasCheckpoint?: boolean;
  syncCheckpointStage?: string;
  lastMirrorJobId?: number;
  lastMirrorStatsAt?: string;
  lastMirrorBranchesCount?: number;
  lastMirrorTagsCount?: number;
  lastMirrorLfsObjects?: number;
  lastMirrorBytesTransferred?: number;
  diffSnapshotAt?: string;
  sourceBranchesCount?: number;
  destBranchesCount?: number;
  inSyncBranchesCount?: number;
  pendingBranchesCount?: number;
  divergedBranchesCount?: number;
  prsTotal?: number;
  prsSynced?: number;
  lfsTotal?: number;
  lfsSynced?: number;
  lfsPending?: number;
  tagsSourceCount?: number;
  tagsTargetCount?: number;
  createdAt: string;
}

export interface SyncConflictRecord {
  id: number;
  mappingId: number;
  jobId?: number;
  kind: 'GIT_REF' | 'TAG' | 'METADATA';
  status: 'OPEN' | 'PR_OPENED' | 'RESOLVED';
  policyApplied?: TrunkConflictPolicy;
  refName?: string;
  sourceSha?: string;
  destSha?: string;
  isolatedBranch?: string;
  conflictPrNumber?: number;
  destRepo?: string;
  originTitle?: string;
  replicaTitle?: string;
  message?: string;
  createdAt: string;
  resolvedAt?: string;
}

export interface SyncJob {
  id: number;
  mappingId: number;
  pairName: string;
  sourceRepo: string;
  targetRepo: string;
  ref: string;
  branch: string;
  commitSha?: string;
  commitMessage?: string;
  author?: string;
  status: SyncStatus;
  triggerType: TriggerType;
  queueMessageId?: string;
  attemptCount: number;
  maxAttempts: number;
  durationMs?: number;
  startedAt?: string;
  completedAt?: string;
  errorMessage?: string;
  summaryMessage?: string;
  branchesCount?: number;
  tagsCount?: number;
  lfsObjectsCount?: number;
  prsSyncedCount?: number;
  releasesCount?: number;
  bytesTransferred?: number;
  objectsReceived?: number;
  lfsBytes?: number;
  sourceAccessMode?: 'PUBLIC' | 'AUTHENTICATED' | string;
  skipReason?: string;
  rejectedPushRefs?: string;
  pipelineJson?: string;
  resumeStageId?: string;
  stageProgressJson?: string;
  restCallCount?: number;
  restCallsPerMinute?: number;
  gitHttpFetchCount?: number;
  gitHttpPushBatchCount?: number;
  rateLimitRemaining?: number;
  rateLimitLimit?: number;
  rateLimit429Count?: number;
  providerTrafficProvider?: string;
  gitPushPerMinute?: number;
  gitFetchPerMinute?: number;
  gitHttpThrottleCount?: number;
  workerInstanceId?: string;
  createdAt: string;
}

export interface SyncAuditLog {
  id: number;
  jobId: number;
  level: LogLevel;
  message: string;
  timestamp: string;
}

export interface PipelineStage {
  id: string;
  label: string;
  status: 'pending' | 'current' | 'done' | 'skipped' | 'failed';
  detail?: string;
  startedAtMs?: number;
  durationMs?: number;
}

export interface SyncPipeline {
  currentStageId?: string;
  stages: PipelineStage[];
}

export interface ProviderTraffic {
  restCallCount: number;
  restCallsPerMinute: number;
  gitHttpFetchCount: number;
  gitHttpPushBatchCount: number;
  gitPushPerMinute?: number;
  gitFetchPerMinute?: number;
  gitHttpThrottleCount?: number;
  gitPushRateGuideline?: number;
  rateLimitRemaining?: number | null;
  rateLimitLimit?: number | null;
  rateLimit429Count?: number;
  provider?: string | null;
}

export interface JobProgress {
  type?: 'JOB_PROGRESS';
  jobId: number;
  mappingId?: number;
  operation?: string;
  phase?: string;
  current: number;
  total: number;
  percent: number;
  message?: string;
  etaMs?: number;
  elapsedMs?: number;
  wallElapsedMs?: number;
  remoteRole?: 'source' | 'destination' | string;
  remoteLabel?: string;
  pipeline?: SyncPipeline;
  providerTraffic?: ProviderTraffic;
}

export interface DiffInspectionProgress {
  type?: 'DIFF_PROGRESS' | 'DIFF_COMPLETE';
  mappingId: number;
  operation?: string;
  phase?: string;
  current: number;
  total: number;
  percent: number;
  message?: string;
  pipeline?: SyncPipeline;
  counts?: {
    inSync?: number;
    pending?: number;
    diverged?: number;
    destOnly?: number;
  };
}

export interface CurrentWork {
  jobId?: number;
  pairName?: string;
  ref?: string;
  threadName?: string;
  threadAlive?: boolean;
  state?: string;
  elapsedMs?: number;
}

export interface ConsumerLaneStatus {
  lane: string;
  label: string;
  listenerId: string;
  queueName: string;
  readyCount: number;
  unackedCount: number;
  brokerConsumerCount: number;
  running: boolean;
  paused: boolean;
  dead: boolean;
  configuredConcurrency: number;
  maxConcurrency: number;
  activeConsumers: number;
  idleThreads: number;
  unusedSlots: number;
  currentWork: CurrentWork[];
}

export interface QueueStatus {
  queueName: string;
  mainQueueMessageCount: number;
  mainQueueUnackedCount?: number;
  mainQueueBrokerConsumerCount?: number;
  incrementalQueueName?: string;
  incrementalQueueMessageCount?: number;
  incrementalQueueUnackedCount?: number;
  incrementalQueueBrokerConsumerCount?: number;
  inboundQueueName?: string;
  inboundQueueMessageCount?: number;
  inboundQueueUnackedCount?: number;
  inboundQueueBrokerConsumerCount?: number;
  dlqQueueName: string;
  dlqMessageCount: number;
  dlqBrokerConsumerCount?: number;
  consumerRunning: boolean;
  fullConsumerRunning?: boolean;
  incrementalConsumerRunning?: boolean;
  inboundConsumerRunning?: boolean;
  consumerPaused: boolean;
  brokerConnected: boolean;
  brokerAddress: string;
  consumers?: ConsumerLaneStatus[];
  simulationStatus: {
    consumerPaused: boolean;
    simulateTargetDown: boolean;
    simulateSourceDown: boolean;
    simulateRateLimit: boolean;
    artificialDelayMs: number;
  };
  timestamp: string;
}

export interface InstallApiUsage {
  installKey: string;
  provider?: string;
  label?: string;
  restCallCount: number;
  restCallsPerMinute: number;
  rateLimitRemaining?: number | null;
  rateLimitLimit?: number | null;
  rateLimit429Count: number;
  lastSeenAt?: string | null;
  reportingInstances?: string[];
}

export interface RuntimeMetrics {
  instanceId?: string;
  capturedAt: string;
  jvm: {
    heapUsedBytes: number;
    heapMaxBytes: number;
    heapUsedPercent: number;
    nonHeapUsedBytes: number;
  };
  threads?: {
    live: number;
    daemon: number;
    peak: number;
    started: number;
  };
  circuitBreaker: {
    state: string;
    consecutiveFailures: number;
    lastProbeMessage?: string;
    lastProbeSuccess: boolean;
  };
  consumerPaused: boolean;
  executors: {
    name: string;
    active: number;
    queued: number;
    poolSize: number;
    completed: number;
    maxPoolSize?: number | null;
  }[];
  lanes: {
    lane: string;
    unacked: number;
    configuredConsumers: number;
    activeConsumers: number;
    running: boolean;
  }[];
  jobOutcomes: {
    lane: string;
    status: string;
    count: number;
    meanDurationMs?: number | null;
  }[];
  apiUsageByInstall?: InstallApiUsage[];
  actionsCancelsTotal: number;
}

export interface ClusterRuntimeMetrics {
  capturedAt: string;
  instanceCount: number;
  liveInstanceCount: number;
  instances: {
    instanceId: string;
    stale: boolean;
    updatedAt?: string;
    metrics?: RuntimeMetrics | null;
  }[];
  totals: {
    executorActive: number;
    executorQueued: number;
    laneUnacked: number;
    actionsCancelsTotal: number;
    pausedInstances: number;
    openCircuitInstances: number;
    threadsLive?: number;
    threadsDaemon?: number;
    threadsPeak?: number;
  };
  apiUsageByInstall?: InstallApiUsage[];
}

export interface ScmQuotas {
  capturedAt: string;
  installations: {
    provider: string;
    installationKey: string;
    restRemaining?: number | null;
    restLimit?: number | null;
    restResetAt?: string | null;
    restCalls: number;
    rest429Count: number;
    graphqlRemaining?: number | null;
    graphqlLimit?: number | null;
    graphqlCalls: number;
    graphqlPointsUsed: number;
    graphql429Count: number;
    updatedAt?: string | null;
    series?: {
      at: string;
      restRemaining?: number | null;
      restLimit?: number | null;
      appRestCalls: number;
      graphqlRemaining?: number | null;
      appGraphqlCalls: number;
    }[];
    githubRestConsumed?: number;
    appRestConsumed?: number;
    externalRestSuspect?: number;
  }[];
  hottestRepos: {
    provider: string;
    repoFullName: string;
    gitFetches: number;
    gitPushes: number;
    gitThrottles: number;
    graphqlCalls: number;
    graphqlPointsUsed: number;
    graphql429Count: number;
    heatScore: number;
    updatedAt?: string | null;
  }[];

}

export interface DashboardStats {
  activePairs: number;
  totalSyncs24h: number;
  successSyncs24h: number;
  successRate: number;
  queuedCount: number;
  inProgressCount: number;
  failedCount: number;
  deadLetterCount: number;
  cancelledCount?: number;
  interruptedCount?: number;
}

export interface ProviderConfig {
  id?: number;
  // GitHub
  authType: 'GITHUB_APP' | 'PERSONAL_ACCESS_TOKEN';
  appId?: string;
  clientId?: string;
  clientSecret?: string;
  clientSecretMasked?: string;
  hasClientSecret?: boolean;

  privateKeyPem?: string;
  privateKeyPemMasked?: string;
  hasPrivateKey?: boolean;

  installationId?: string;

  webhookSecret?: string;
  webhookSecretMasked?: string;
  hasWebhookSecret?: boolean;

  defaultPatToken?: string;
  defaultPatTokenMasked?: string;
  hasDefaultPatToken?: boolean;

  // GitHub Enterprise Server (GHES)
  ghesHostUrl?: string;
  ghesAuthType?: 'GITHUB_APP' | 'PERSONAL_ACCESS_TOKEN';
  ghesPatToken?: string;
  ghesPatTokenMasked?: string;
  hasGhesPatToken?: boolean;
  ghesAppId?: string;
  ghesClientId?: string;
  ghesClientSecret?: string;
  ghesClientSecretMasked?: string;
  hasGhesClientSecret?: boolean;
  ghesPrivateKeyPem?: string;
  ghesPrivateKeyPemMasked?: string;
  hasGhesPrivateKey?: boolean;
  ghesInstallationId?: string;
  ghesWebhookSecret?: string;
  ghesWebhookSecretMasked?: string;
  hasGhesWebhookSecret?: boolean;

  // GitLab
  gitlabHostUrl?: string;
  gitlabAccessToken?: string;
  gitlabAccessTokenMasked?: string;
  hasGitlabAccessToken?: boolean;
  gitlabWebhookSecret?: string;
  gitlabWebhookSecretMasked?: string;
  hasGitlabWebhookSecret?: boolean;

  // Bitbucket
  bitbucketWorkspace?: string;
  bitbucketAuthType?: 'OAUTH2' | 'APP_PASSWORD' | 'ACCESS_TOKEN';
  bitbucketUsername?: string;
  bitbucketAccessToken?: string;
  bitbucketAccessTokenMasked?: string;
  hasBitbucketAccessToken?: boolean;
  bitbucketWebhookSecret?: string;
  bitbucketWebhookSecretMasked?: string;
  hasBitbucketWebhookSecret?: boolean;

  // Cursor Origin
  originHostUrl?: string;
  originAccessToken?: string;
  originAccessTokenMasked?: string;
  hasOriginAccessToken?: boolean;
  originWebhookSecret?: string;
  originWebhookSecretMasked?: string;
  hasOriginWebhookSecret?: boolean;

  // Generic / Azure DevOps
  genericUsername?: string;
  genericAccessToken?: string;
  genericAccessTokenMasked?: string;
  hasGenericAccessToken?: boolean;

  configured: boolean;
  updatedAt?: string;
}

export type GitHubAppConfig = ProviderConfig;

export interface ScmCredential {
  id: number;
  label: string;
  provider: 'GITHUB' | 'GITHUB_ENTERPRISE' | string;
  hostUrl: string;
  authMode: 'GITHUB_APP' | 'PERSONAL_ACCESS_TOKEN';
  appId?: string;
  clientId?: string;
  installationId?: string;
  accountLogin?: string;
  accountType?: string;
  repositorySelection?: string;
  appSlug?: string;
  botLogin?: string;
  hasPrivateKey?: boolean;
  hasPatToken?: boolean;
  hasWebhookSecret?: boolean;
  hasClientSecret?: boolean;
  clientSecretMasked?: string;
  webhookSecretMasked?: string;
  webhookPath?: string;
  enabled: boolean;
  updatedAt?: string;
}

export interface ScmInstallationOption {
  installationId: string;
  accountLogin?: string;
  accountType?: string;
  repositorySelection?: string;
  htmlUrl?: string;
}

export interface PermissionCheckReport {
  valid: boolean;
  repoFullName?: string;
  defaultBranch?: string;
  isPrivate?: boolean;
  httpStatusCode?: number;
  message: string;
  accessMode?: 'PUBLIC' | 'AUTHENTICATED' | string;
  permissions?: {
    contentsRead: boolean;
    contentsWrite: boolean;
    pullRequests: boolean;
    commitStatuses: boolean;
    webhooks: boolean;
    admin: boolean;
  };
  passedChecks: string[];
  warnings: string[];
  errors: string[];
}

export interface GitHubRepoOption {
  id: string;
  name: string;
  fullName: string;
  cloneUrl: string;
  defaultBranch: string;
  isPrivate: boolean;
  canPush: boolean;
  canPull: boolean;
  isAdmin: boolean;
  provider?: 'GITHUB' | 'GITLAB' | 'BITBUCKET' | 'ORIGIN' | 'GENERIC' | string;
  namespace?: string;
  description?: string;
  credentialId?: number;
  hasWriteAccess?: boolean;
}

export interface RepoSearchResult {
  items: GitHubRepoOption[];
  page: number;
  limit: number;
  totalCount: number;
  hasMore: boolean;
  provider: string;
  query: string;
}

export interface BranchDiffDetail {
  branchName: string;
  sourceSha?: string;
  sourceShortSha?: string;
  targetSha?: string;
  targetShortSha?: string;
  status: 'IN_SYNC' | 'AHEAD' | 'BEHIND' | 'DIVERGED' | 'TARGET_MISSING' | 'SOURCE_MISSING' | 'PENDING_SYNC';
  aheadCount: number;
  behindCount: number;
  lastCommitMessage?: string;
  lastCommitAuthor?: string;
  forkPrHead?: boolean;
}

export interface PrSyncDetail {
  sourcePrNumber: number;
  targetPrNumber?: number;
  title: string;
  state: string;
  headBranch?: string;
  baseBranch?: string;
  isSynced: boolean;
  syncStatus?: 'MIRRORED' | 'PENDING' | 'SKIPPED' | 'FAILED' | string;
  reason?: string;
  isFork?: boolean;
  authorLogin?: string;
  sourcePrUrl?: string;
  body?: string;
  commentsCount?: number;
  reviewCommentsCount?: number;
  isDraft?: boolean;
}

export interface LfsSyncSummary {
  totalDiscovered: number;
  syncedCount: number;
  pendingCount: number;
  inSync: boolean;
  destVerified?: boolean;
}

export interface TagSyncSummary {
  sourceTagsCount: number;
  targetTagsCount: number;
  inSync: boolean;
}

export interface ReleaseSyncSummary {
  sourceReleasesCount: number;
  targetReleasesCount: number;
  inSync: boolean;
  latestReleaseTag?: string;
  totalAssetsCount?: number;
}

export interface CiStatusSummary {
  replicatedStatusesCount: number;
  inSync: boolean;
  latestStatusState?: string;
  latestContext?: string;
}

export interface TagDetail {
  tagName: string;
  refName: string;
  targetSha?: string;
  targetShortSha?: string;
  isAnnotated: boolean;
  message?: string;
  taggerName?: string;
  taggerDate?: string;
}

export interface ReleaseAssetDetail {
  id: number;
  name: string;
  sizeBytes: number;
  formattedSize: string;
  downloadUrl: string;
  contentType: string;
  downloadCount: number;
}

export interface ReleaseDetail {
  id: number;
  name: string;
  tagName: string;
  body?: string;
  publishedAt?: string;
  author?: string;
  isDraft: boolean;
  isPrerelease: boolean;
  htmlUrl?: string;
  assets?: ReleaseAssetDetail[];
}

export interface LfsPointerDetail {
  filePath: string;
  oid: string;
  shortOid: string;
  sizeBytes: number;
  formattedSize: string;
  headBranch?: string;
}

export interface CiCheckRunDetail {
  id: number;
  name: string;
  status: string; // completed, in_progress, queued
  conclusion?: string; // success, failure, neutral, cancelled, timed_out, action_required
  startedAt?: string;
  completedAt?: string;
  htmlUrl?: string;
  appName?: string;
  headSha?: string;
}

export interface SyncDiffReport {
  mappingId: number;
  pairName: string;
  sourceRepo: string;
  targetRepo: string;
  overallStatus: 'IN_SYNC' | 'PENDING_SYNC' | 'DIVERGED' | 'UNKNOWN';
  totalBranchesCount: number;
  inSyncBranchesCount: number;
  pendingBranchesCount: number;
  divergedBranchesCount?: number;
  destOnlyBranchesCount?: number;
  sourceBranchesCount?: number;
  destBranchesCount?: number;
  branches: BranchDiffDetail[];
  pullRequests: PrSyncDetail[];
  lfs?: LfsSyncSummary;
  tags?: TagSyncSummary;
  releases?: ReleaseSyncSummary;
  ciStatuses?: CiStatusSummary;
  tagItems?: TagDetail[];
  releaseItems?: ReleaseDetail[];
  lfsItems?: LfsPointerDetail[];
  ciCheckRuns?: CiCheckRunDetail[];
  inspectionError?: string | null;
  inspectionMode?: 'quick' | 'full' | string;
  branchesTruncated?: boolean;
  branchesPageOffset?: number;
  branchesPageSize?: number;
  branchesFilteredCount?: number;
  metadataDeferred?: boolean;
  fromPersistedSnapshot?: boolean;
  persistedSnapshotAt?: string;
  persistedSnapshotSource?: string;
  persistedPrsTotal?: number;
  persistedPrsSynced?: number;
  totalOpenPrsCount?: number;
  pullRequestsTruncated?: boolean;
  mirroredPrsCount?: number;
  destOpenPrsCount?: number;
}

export interface UnmappedWebhookEvent {
  id: number;
  provider: string;
  repoFullName?: string;
  repoUrl?: string;
  eventType: string;
  sender?: string;
  branch?: string;
  commitSha?: string;
  commitMessage?: string;
  discardReason: 'UNMAPPED_REPOSITORY' | 'INACTIVE_MAPPING' | 'NON_BRANCH_REF' | 'UNSUPPORTED_EVENT' | 'DIRECTION_IGNORED' | string;
  details?: string;
  receivedAt: string;
}

export interface StorageStatusResponse {
  localDirectory: string;
  localUsedBytes: number;
  localUsedFormatted: string;
  nasDirectory?: string;
  nasUsedBytes: number;
  nasUsedFormatted: string;
  totalCachedRepos: number;
  hotReposCount: number;
  autoLruReposCount: number;
  ephemeralReposCount: number;
  nasReposCount: number;
  maxDiskQuotaBytes: number;
  maxDiskQuotaFormatted: string;
  quotaUsedPercent: number;
}

export interface SystemEngineConfig {
  id?: number;
  localDir: string;
  nasDir: string;
  maxDiskQuotaMb: number;
  maxCachedRepos: number;
  retentionHours: number;

  maxConcurrentPushes: number;
  metadataSyncIntervalSeconds: number;

  maxRetryAttempts: number;
  retryInitialIntervalMs: number;
  retryMultiplier: number;
  retryMaxIntervalMs: number;

  circuitBreakerFailureThreshold: number;
  circuitBreakerResetTimeoutSeconds: number;

  /** Cancel Actions runs triggered by the mirror App after sync writes (default true). */
  suppressMirrorActionsTriggers?: boolean;

  // Enterprise Multi-Sink Logging
  loggingSink: 'CONSOLE' | 'SPLUNK_HEC' | 'LOGSTASH_ELK' | 'SYSLOG' | 'ROLLING_FILE' | 'DUAL_CONSOLE_SPLUNK' | string;
  loggingLevel: 'DEBUG' | 'INFO' | 'WARN' | 'ERROR' | string;
  splunkHecUrl?: string;
  splunkHecToken?: string;
  splunkHecTokenMasked?: string;
  hasSplunkHecToken?: boolean;
  splunkIndex?: string;
  splunkSourceType?: string;
  logstashHost?: string;
  syslogHost?: string;
  rollingFilePath?: string;
  jsonStructuredEnabled?: boolean;

  // Runtime telemetry
  circuitBreakerState?: 'CLOSED' | 'OPEN' | 'HALF_OPEN' | string;
  currentConsecutiveFailures?: number;
  lastStateTransitionAt?: string;
  lastProbeMessage?: string;
  lastProbeSuccess?: boolean;
  updatedAt?: string;
}

export interface LoggingSinkTestResult {
  success: boolean;
  statusCode?: number;
  message: string;
  latencyMs?: number;
}

export interface NasPathTestResult {
  valid: boolean;
  path: string;
  readable?: boolean;
  writable?: boolean;
  freeSpaceMb?: number;
  totalSpaceMb?: number;
  message: string;
}

export interface CircuitBreakerResetResult {
  success: boolean;
  message: string;
  circuitState: 'CLOSED' | 'OPEN' | 'HALF_OPEN' | string;
  currentFailures: number;
}
