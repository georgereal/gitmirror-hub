import axios from 'axios';
import {
  RepoMapping,
  SyncJob,
  SyncAuditLog,
  QueueStatus,
  MessagingModuleInfo,
  PersistenceModuleInfo,
  RuntimeMetrics,
  ClusterRuntimeMetrics,
  ScmQuotas,
  DashboardStats,
  JobUsageResponse,
  GitHubAppConfig,
  PermissionCheckReport,
  GitHubRepoOption,
  RepoSearchResult,
  SyncDiffReport,
  UnmappedWebhookEvent,
  StorageStatusResponse,
  SystemEngineConfig,
  NasPathTestResult,
  CircuitBreakerResetResult,
  LoggingSinkTestResult,
  SyncConflictRecord
} from '../types';

const api = axios.create({
  baseURL: '/api/v1',
  headers: {
    'Content-Type': 'application/json',
  },
});

export const getMappings = async (): Promise<RepoMapping[]> => {
  const res = await api.get('/mappings');
  return res.data;
};

export const createMapping = async (mapping: Partial<RepoMapping>): Promise<RepoMapping> => {
  const res = await api.post('/mappings', mapping);
  return res.data;
};

// ── Bulk migration ─────────────────────────────────────────────────────────────

export interface BulkMirrorRow {
  sourceUrl?: string;
  destUrl?: string;
  outcome: 'CREATED_QUEUED' | 'SKIPPED' | 'FAILED_VALIDATION';
  reason?: string;
  mappingId?: string;
  jobId?: string;
}

export interface BulkMirrorRequest {
  mode: 'CREATE_DEST' | 'USE_EXISTING' | 'SELECTIVE';
  items: Array<{
    sourceUrl: string;
    sourceProvider?: string;
    sourceCredentialId?: string;
    sourceInstallationId?: string;
    sourceVisibility?: 'UNKNOWN' | 'PUBLIC' | 'PRIVATE' | 'INTERNAL';
    sourcePublicRead?: boolean;
    destName?: string;
    destUrl?: string;
    destCredentialId?: string;
    destInstallationId?: string;
    destVisibility?: 'UNKNOWN' | 'PUBLIC' | 'PRIVATE' | 'INTERNAL';
    includeNonEmptyDest?: boolean;
  }>;
  branchPattern?: string;
  syncDirection?: string;
  trunkConflictPolicy?: string;
  storageTier?: string;
  active?: boolean;
  destOwner?: string;
  destCredentialId?: string;
  destInstallationId?: string;
  destPrivate?: boolean;
  destVisibility?: 'PUBLIC' | 'PRIVATE' | 'INTERNAL';
}

export interface BulkMirrorResponse {
  submissionId: string;
  rows: BulkMirrorRow[];
  createdQueuedCount: number;
  skippedCount: number;
  failedValidationCount: number;
}

export const submitBulkMigration = async (payload: BulkMirrorRequest): Promise<BulkMirrorResponse> => {
  const res = await api.post('/mappings/bulk', payload);
  return res.data;
};

export const getBulkSubmission = async (id: string) => {
  const res = await api.get(`/bulk/${id}`);
  return res.data;
};

export interface BulkSubmissionRecord {
  id: string;
  mode: 'CREATE_DEST' | 'USE_EXISTING' | string;
  itemCount: number;
  createdCount: number;
  skippedJson?: string | null;
  cancelledAt?: string | null;
  createdAt?: string | null;
}

export const listBulkSubmissions = async (): Promise<BulkSubmissionRecord[]> => {
  const res = await api.get('/bulk');
  return res.data;
};

export const cancelBulkSubmission = async (id: string, reason?: string) => {
  const res = await api.post(`/bulk/${id}/cancel`, { reason });
  return res.data as { submissionId: string; cancelled: number; status: string };
};

/** Lightweight "destination has commits" probe (bulk migration Option 2 pre-submit warning). */
export const probeRepoHasCommits = async (url: string, credentialId?: string): Promise<boolean> => {
  try {
    const res = await api.get('/github-app/repo-has-commits', { params: { url, credentialId } });
    return Boolean(res.data?.hasCommits);
  } catch {
    return false;
  }
};

/** Whether a destination name is already taken. Creation still happens when the mirror job starts. */
export const probeRepoExists = async (url: string, credentialId?: string): Promise<boolean> => {
  try {
    const res = await api.get('/github-app/repo-exists', { params: { url, credentialId } });
    return Boolean(res.data?.exists);
  } catch {
    return false;
  }
};

export const updateMapping = async (id: string, mapping: Partial<RepoMapping>): Promise<RepoMapping> => {
  const res = await api.put(`/mappings/${id}`, mapping);
  return res.data;
};

export const deleteMapping = async (id: string): Promise<void> => {
  await api.delete(`/mappings/${id}`);
};

export const triggerManualSync = async (
  id: string,
  branch = '*',
  direction = 'A_TO_B',
  overwriteFromSource = false,
  startFresh = false
): Promise<SyncJob> => {
  const res = await api.post(`/mappings/${id}/sync`, { branch, direction, overwriteFromSource, startFresh });
  return res.data;
};

export const syncPullRequests = async (mappingId: string): Promise<{ syncedCount: number; message: string }> => {
  const res = await api.post(`/mappings/${mappingId}/sync-prs`);
  return res.data;
};

export const syncLfsObjects = async (mappingId: string): Promise<{ syncedCount: number; bytesTransferred?: number; message: string; error?: string }> => {
  const res = await api.post(`/mappings/${mappingId}/sync-lfs`);
  return res.data;
};

export const syncReleases = async (mappingId: string): Promise<{ jobId?: string; syncedCount: number; message: string }> => {
  const res = await api.post(`/mappings/${mappingId}/sync-releases`);
  return res.data;
};

export interface WriteAuthorityChip {
  enabled: boolean;
  reason?: string | null;
  orgLogin?: string | null;
}

export interface WriteAuthoritySide {
  side: 'A' | 'B';
  repoFullName?: string | null;
  repoUrl?: string | null;
  credentialId?: string | null;
  credentialLabel?: string | null;
  access: 'write' | 'readonly';
  scope: 'repo' | 'org' | 'enterprise';
  target: 'this_repo' | 'all_repos';
  enforcement?: string | null;
  rulesetId?: number | null;
  rulesetName?: string | null;
  rulesetState?: 'missing' | 'active' | 'disabled' | 'unknown' | string | null;
  rulesetDetail?: string | null;
  repoRulesetName?: string | null;
  repoRulesetState?: 'missing' | 'active' | 'disabled' | 'unknown' | string | null;
  repoRulesetDetail?: string | null;
  repo: WriteAuthorityChip;
  org: WriteAuthorityChip;
  enterprise: WriteAuthorityChip;
  coverageNote?: string | null;
}

export interface WriteAuthorityRepo {
  credentialId: string;
  credentialLabel?: string | null;
  appId?: string | null;
  fullName: string;
  privateRepo?: boolean;
  canManage: boolean;
  disabledReason?: string | null;
  rulesetId?: number | null;
  rulesetName?: string | null;
  rulesetState?: string | null;
  rulesetDetail?: string | null;
}

export interface WriteAuthorityView {
  pairs: Array<{
    id: string;
    name: string;
    linkMode: 'linked' | 'independent' | string;
    sides: WriteAuthoritySide[];
  }>;
  orgs: Array<{
    credentialId: string;
    credentialLabel?: string;
    orgLogin: string;
    provider?: string;
    canManage: boolean;
    disabledReason?: string | null;
    access: 'write' | 'readonly';
    enforcement?: string | null;
    rulesetId?: number | null;
    rulesetName?: string | null;
    rulesetState?: string | null;
    rulesetDetail?: string | null;
  }>;
  enterprises: Array<{
    credentialId: string;
    credentialLabel?: string;
    slug: string;
    probeOk: boolean;
    disabledReason?: string | null;
    access: 'write' | 'readonly';
    enforcement?: string | null;
    rulesetId?: number | null;
    rulesetName?: string | null;
    rulesetState?: string | null;
    rulesetDetail?: string | null;
  }>;
}

export const getWriteAuthority = async (refresh = false): Promise<WriteAuthorityView> => {
  const res = await api.get('/write-authority', { params: refresh ? { refresh: true } : {} });
  return res.data;
};

export const getRepositoryRuleset = async (
  credentialId: string,
  fullName: string,
  installationId?: string
): Promise<WriteAuthorityRepo> => {
  const res = await api.get('/write-authority/repository', {
    params: { credentialId, fullName, installationId: installationId || undefined },
  });
  return res.data;
};

export interface RepositoryRulesetItem {
  id: number;
  name: string;
  target: string;
  enforcement: string;
  hubReplica: boolean;
}

export const listRepositoryRulesets = async (
  credentialId: string,
  fullName: string,
  installationId?: string
): Promise<RepositoryRulesetItem[]> => {
  const res = await api.get('/write-authority/repository/rulesets', {
    params: { credentialId, fullName, installationId: installationId || undefined },
  });
  return res.data;
};

export const setRepositoryRulesetEnforcement = async (body: {
  credentialId: string;
  repoFullName: string;
  installationId?: string;
  rulesetId: number;
  enforcement: 'active' | 'disabled';
}): Promise<void> => {
  await api.post('/write-authority/repository/rulesets/enforcement', body);
};

export const pageCredentialRepositories = async (
  id: string,
  params: { query?: string; page?: number; limit?: number; visibility?: 'private' | 'internal' | 'public' }
): Promise<RepoSearchResult> => {
  const res = await api.get(`/scm-credentials/${id}/repositories/page`, { params });
  return res.data;
};

export const applyWriteAuthority = async (body: {
  link?: 'linked' | 'independent';
  pairId?: string;
  sides?: Array<{ side: 'A' | 'B'; access: 'write' | 'readonly'; scope: string; target: string }>;
  org?: { credentialId: string; orgLogin: string; access: 'write' | 'readonly' };
  repository?: { credentialId: string; repoFullName: string; installationId?: string; access: 'write' | 'readonly' };
  enterprise?: { credentialId: string; access: 'write' | 'readonly' };
  confirmAllRepos?: string;
}): Promise<WriteAuthorityView> => {
  const res = await api.post('/write-authority', body);
  return res.data;
};

export const listDrLanes = async (): Promise<import('../types').DrLane[]> => {
  const res = await api.get('/dr-lanes');
  return res.data;
};

export const activateDrLane = async (laneKey: string): Promise<import('../types').DrLane> => {
  const res = await api.post('/dr-lanes/activate', { laneKey });
  return res.data;
};

export const failBackDrLane = async (laneKey: string): Promise<import('../types').DrLane> => {
  const res = await api.post('/dr-lanes/fail-back', { laneKey });
  return res.data;
};

export const probeDrLane = async (laneKey: string): Promise<import('../types').DrLane> => {
  const res = await api.post('/dr-lanes/probe', { laneKey });
  return res.data;
};

export const getPeerStatus = async (mappingId: string): Promise<import('../types').PeerStatus> => {
  const res = await api.get(`/mappings/${mappingId}/peer-status`);
  return res.data;
};

export const probePeerHeartbeat = async (mappingId: string): Promise<import('../types').PeerStatus> => {
  const res = await api.post(`/mappings/${mappingId}/peer-heartbeat`);
  return res.data;
};

export const activateDr = async (mappingId: string): Promise<import('../types').PeerStatus> => {
  const res = await api.post(`/mappings/${mappingId}/activate-dr`);
  return res.data;
};

export const failBack = async (mappingId: string): Promise<import('../types').PeerStatus> => {
  const res = await api.post(`/mappings/${mappingId}/fail-back`);
  return res.data;
};

export const applyReplicaRuleset = async (
  mappingId: string,
  action: 'lock' | 'unlock' | 'swap',
  primarySide?: 'A' | 'B'
): Promise<RepoMapping> => {
  const res = await api.post(`/mappings/${mappingId}/replica-ruleset`, { action, primarySide });
  return res.data;
};

export const syncCiChecks = async (mappingId: string): Promise<{ jobId?: string; syncedCount: number; message: string }> => {
  const res = await api.post(`/mappings/${mappingId}/sync-ci-checks`);
  return res.data;
};

export const getRecentJobs = async (): Promise<SyncJob[]> => {
  const res = await api.get('/jobs/recent');
  return res.data;
};

export const getJobs = async (
  page = 0,
  size = 50,
  status?: string,
  mappingId?: string,
  triggerType?: string,
  lane?: string
): Promise<{ content: SyncJob[]; totalElements: number; totalPages: number }> => {
  const params: Record<string, string | number> = { page, size };
  if (status) params.status = status;
  if (mappingId) params.mappingId = mappingId;
  if (triggerType) params.triggerType = triggerType;
  if (lane) params.lane = lane;
  const res = await api.get('/jobs', { params });
  return res.data;
};

export const getJob = async (id: string): Promise<SyncJob> => {
  const res = await api.get(`/jobs/${id}`);
  return res.data;
};

export const getJobLogs = async (id: string): Promise<SyncAuditLog[]> => {
  const res = await api.get(`/jobs/${id}/logs`);
  return res.data;
};

export const getDashboardStats = async (): Promise<DashboardStats> => {
  const res = await api.get('/jobs/stats');
  return res.data;
};

export const retryJob = async (id: string): Promise<SyncJob> => {
  const res = await api.post(`/jobs/${id}/retry`);
  return res.data;
};

export const resumeJob = async (id: string): Promise<SyncJob> => {
  const res = await api.post(`/jobs/${id}/resume`);
  return res.data;
};

export const dispatchJobs = async (
  jobIds: string[]
): Promise<{ dispatched: number; skipped: number; errors: string[] }> => {
  const res = await api.post('/jobs/dispatch', { jobIds });
  return res.data;
};

export const pauseJob = async (id: string): Promise<SyncJob> => {
  const res = await api.post(`/jobs/${id}/pause`);
  return res.data;
};

export const skipJobStage = async (id: string, stageId: string): Promise<SyncJob> => {
  const res = await api.post(`/jobs/${id}/skip-stage`, { stageId });
  return res.data;
};

export const cancelJob = async (id: string): Promise<SyncJob> => {
  const res = await api.post(`/jobs/${id}/cancel`);
  return res.data;
};

export const cancelQueuedJobs = async (mappingId?: string): Promise<{ cancelledCount: number; message: string }> => {
  const res = await api.post('/jobs/cancel-queued', null, { params: mappingId ? { mappingId } : {} });
  return res.data;
};

export const getQueueStatus = async (): Promise<QueueStatus> => {
  const res = await api.get('/queue/status');
  return res.data;
};

export interface KafkaPartitionStatus {
  partition: number;
  start: number;
  committed: number | null;
  end: number;
  pending: number;
  processed: number;
}

export interface KafkaStoredFailure {
  id: string;
  provider?: string;
  repoUrl?: string;
  repoFullName?: string;
  branch?: string;
  commitSha?: string;
  eventType?: string;
  details?: string;
  receivedAt?: string;
}

export interface WebhookBusStatus {
  provider: string;
  topic?: string;
  groupId?: string;
  lag?: number | null;
  pending?: number | null;
  processed?: number | null;
  paused?: boolean;
  lagError?: string;
  stale?: boolean;
  partitionCount?: number;
  groupState?: string;
  memberCount?: number;
  sampledAt?: string;
  partitions?: KafkaPartitionStatus[];
  storedFailureCount?: number;
  storedFailures?: KafkaStoredFailure[];
  queue?: string;
  exchange?: string;
  routingKey?: string;
  deadLetterQueue?: string;
}

export const getWebhookBus = async (fresh = false): Promise<WebhookBusStatus> => {
  const res = await api.get('/webhook-bus', { params: fresh ? { fresh: true } : {} });
  return res.data;
};

export const redriveWebhookBus = async (limit = 10): Promise<{ redriven: number }> => {
  const res = await api.post('/webhook-bus/redrive', null, { params: { limit } });
  return res.data;
};

export const getMessagingModule = async (): Promise<MessagingModuleInfo> => {
  const res = await api.get('/messaging');
  return res.data;
};

export const getPersistenceModule = async (): Promise<PersistenceModuleInfo> => {
  const res = await api.get('/persistence');
  return res.data;
};

export const getRuntimeMetrics = async (): Promise<RuntimeMetrics> => {
  const res = await api.get('/runtime-metrics');
  return res.data;
};

export const getClusterRuntimeMetrics = async (): Promise<ClusterRuntimeMetrics> => {
  const res = await api.get('/runtime-metrics/cluster');
  return res.data;
};

export const getScmQuotas = async (): Promise<ScmQuotas> => {
  const res = await api.get('/scm-quotas', { params: { topRepos: 25 } });
  return res.data;
};

export const getJobUsage = async (hours = 6, limit = 40): Promise<JobUsageResponse> => {
  const since = new Date(Date.now() - hours * 60 * 60 * 1000).toISOString();
  const res = await api.get('/jobs/usage', { params: { since, limit } });
  return res.data;
};

export const redriveDlq = async (): Promise<{ messagesRedriven: number; message: string }> => {
  const res = await api.post('/queue/dlq/redrive');
  return res.data;
};

export const purgeDlq = async (): Promise<{ status: string; message: string }> => {
  const res = await api.post('/queue/dlq/purge');
  return res.data;
};

export const purgeMainQueue = async (): Promise<{ status: string; cancelledCount: number; message: string }> => {
  const res = await api.post('/queue/purge');
  return res.data;
};

export const purgeInboundQueue = async (): Promise<{ status: string; message: string }> => {
  const res = await api.post('/queue/inbound/purge');
  return res.data;
};

export const pauseConsumer = async (): Promise<{ consumerPaused: boolean; message: string }> => {
  const res = await api.post('/simulation/consumer/pause');
  return res.data;
};

export const resumeConsumer = async (): Promise<{ consumerPaused: boolean; message: string }> => {
  const res = await api.post('/simulation/consumer/resume');
  return res.data;
};

export const updateSimulationConfig = async (config: {
  consumerPaused?: boolean;
  simulateTargetDown?: boolean;
  simulateSourceDown?: boolean;
  simulateRateLimit?: boolean;
  artificialDelayMs?: number;
}): Promise<any> => {
  const res = await api.post('/simulation/config', config);
  return res.data;
};

export const emitSyntheticWebhook = async (data: {
  mappingId: string;
  branch: string;
  commitSha: string;
  commitMessage: string;
  authorName?: string;
}): Promise<SyncJob> => {
  const res = await api.post('/simulation/emit-webhook', data);
  return res.data;
};

// GitHub App & Authentication APIs
export const getGitHubAppConfig = async (): Promise<GitHubAppConfig> => {
  const res = await api.get('/github-app/config');
  return res.data;
};

export const saveGitHubAppConfig = async (config: Partial<GitHubAppConfig>): Promise<GitHubAppConfig> => {
  const res = await api.post('/github-app/config', config);
  return res.data;
};

export const testRepoConnection = async (data: {
  repoUrl: string;
  token?: string;
  requiredAccess: 'READ' | 'WRITE' | 'BOTH';
  knownPrivate?: boolean;
  credentialId?: string;
  /** App installation that owns the repo. Omit to let the backend resolve it from the URL. */
  installationId?: string;
}): Promise<PermissionCheckReport> => {
  const res = await api.post('/github-app/test-connection', data);
  return res.data;
};

export const testProviderConnection = async (provider?: string, data?: { repoUrl?: string; token?: string }): Promise<PermissionCheckReport> => {
  const params: Record<string, string> = {};
  if (provider) params.provider = provider;
  const res = await api.post('/github-app/test-provider', data || {}, { params });
  return res.data;
};

export const listAccessibleRepositories = async (token?: string, provider?: string): Promise<GitHubRepoOption[]> => {
  const params: Record<string, string> = {};
  if (token) params.token = token;
  if (provider) params.provider = provider;
  const res = await api.get('/github-app/repositories', { params });
  return res.data;
};

export const searchRemoteRepositories = async (params: {
  query?: string;
  provider?: string;
  page?: number;
  limit?: number;
  credentialId?: string;
  access?: 'PULL' | 'PUSH';
}): Promise<RepoSearchResult> => {
  const res = await api.get('/github-app/search-repositories', { params });
  return res.data;
};

export const listScmCredentials = async (provider?: string): Promise<import('../types').ScmCredential[]> => {
  const res = await api.get('/scm-credentials', { params: provider ? { provider } : {} });
  return res.data;
};

export const createScmCredential = async (body: Record<string, unknown>): Promise<import('../types').ScmCredential> => {
  const res = await api.post('/scm-credentials', body);
  return res.data;
};

export const updateScmCredential = async (id: string, body: Record<string, unknown>): Promise<import('../types').ScmCredential> => {
  const res = await api.put(`/scm-credentials/${id}`, body);
  return res.data;
};

export const deleteScmCredential = async (id: string): Promise<void> => {
  await api.delete(`/scm-credentials/${id}`);
};

export const listScmInstallations = async (id: string): Promise<import('../types').ScmInstallationOption[]> => {
  const res = await api.get(`/scm-credentials/${id}/installations`);
  return res.data;
};

export const previewScmInstallations = async (body: {
  credentialId?: string;
  provider?: string;
  hostUrl?: string;
  appId?: string;
  privateKeyPem?: string;
}): Promise<import('../types').ScmInstallationOption[]> => {
  const res = await api.post('/scm-credentials/installations/preview', body);
  return res.data;
};

export const searchCredentialRepositories = async (
  id: string,
  params: { query?: string; page?: number; limit?: number; access?: string; installationId?: string }
): Promise<RepoSearchResult> => {
  const res = await api.get(`/scm-credentials/${id}/repositories`, { params });
  return res.data;
};

export const testScmCredential = async (id: string): Promise<PermissionCheckReport> => {
  const res = await api.post(`/scm-credentials/${id}/test`, {});
  return res.data;
};

export const createRemoteRepository = async (data: {
  repoUrl?: string;
  name?: string;
  owner?: string;
  accountType?: string;
  isPrivate?: boolean;
  visibility?: 'public' | 'private' | 'internal';
  description?: string;
  credentialId?: string;
}): Promise<GitHubRepoOption> => {
  const res = await api.post('/github-app/create-repo', data);
  return res.data;
};

const syncDiffInflight = new Map<string, Promise<SyncDiffReport>>();

export interface SyncDiffOptions {
  refresh?: boolean;
  metadata?: boolean;
  maxBranches?: number;
  branchOffset?: number;
  branchSearch?: string;
  branchStatus?: 'ALL' | 'IN_SYNC' | 'PENDING' | 'DIVERGED' | 'DEST_ONLY' | 'ACTIONABLE';
}

function syncDiffCacheKey(mappingId: string, options?: SyncDiffOptions): string {
  return `${mappingId}:${options?.refresh ? '1' : '0'}:${options?.metadata ? '1' : '0'}:${options?.maxBranches ?? ''}:${options?.branchOffset ?? ''}:${options?.branchSearch ?? ''}:${options?.branchStatus ?? ''}`;
}

export const getSyncDiffReport = async (
  mappingId: string,
  options?: SyncDiffOptions
): Promise<SyncDiffReport> => {
  const cacheKey = syncDiffCacheKey(mappingId, options);
  const existing = syncDiffInflight.get(cacheKey);
  if (existing) {
    return existing;
  }
  const params = new URLSearchParams();
  if (options?.refresh) params.set('refresh', 'true');
  if (options?.metadata) params.set('metadata', 'true');
  if (options?.maxBranches != null) params.set('maxBranches', String(options.maxBranches));
  if (options?.branchOffset != null) params.set('branchOffset', String(options.branchOffset));
  if (options?.branchSearch) params.set('branchSearch', options.branchSearch);
  if (options?.branchStatus && options.branchStatus !== 'ALL') params.set('branchStatus', options.branchStatus);
  const qs = params.toString();
  const request = api
    .get(`/mappings/${mappingId}/sync-diff${qs ? `?${qs}` : ''}`)
    .then((res) => res.data as SyncDiffReport)
    .finally(() => {
      syncDiffInflight.delete(cacheKey);
    });
  syncDiffInflight.set(cacheKey, request);
  return request;
};

export const getMappingConflicts = async (id: string): Promise<SyncConflictRecord[]> => {
  const res = await api.get(`/mappings/${id}/conflicts`);
  return res.data;
};

export const resolveMappingConflict = async (mappingId: string, conflictId: string): Promise<SyncConflictRecord> => {
  const res = await api.post(`/mappings/${mappingId}/conflicts/${conflictId}/resolve`);
  return res.data;
};

export const openConflictPr = async (mappingId: string, conflictId: string): Promise<SyncConflictRecord> => {
  const res = await api.post(`/mappings/${mappingId}/conflicts/${conflictId}/open-pr`);
  return res.data;
};

export const getUnmappedWebhooks = async (): Promise<UnmappedWebhookEvent[]> => {
  const res = await api.get('/unmapped-webhooks');
  return res.data;
};

export const deleteUnmappedWebhook = async (id: string): Promise<void> => {
  await api.delete(`/unmapped-webhooks/${id}`);
};

export const clearUnmappedWebhooks = async (): Promise<void> => {
  await api.delete('/unmapped-webhooks');
};

export const getStorageStatus = async (): Promise<StorageStatusResponse> => {
  const res = await api.get('/storage/status');
  return res.data;
};

export const triggerStorageEviction = async (): Promise<void> => {
  await api.post('/storage/evict-now');
};

export const getFeatureFlags = async (): Promise<import('../types').FeatureFlags> => {
  const res = await api.get('/feature-flags');
  return res.data;
};

export const saveFeatureFlags = async (
  body: Partial<import('../types').FeatureFlags>
): Promise<import('../types').FeatureFlags> => {
  const res = await api.put('/feature-flags', body);
  return res.data;
};

export const getMetadataSyncSettings = async (): Promise<import('../types').MetadataSyncSettings> => {
  const res = await api.get('/metadata-sync-settings');
  return res.data;
};

export const saveMetadataSyncSettings = async (
  body: Partial<import('../types').MetadataSyncSettings>
): Promise<import('../types').MetadataSyncSettings> => {
  const res = await api.put('/metadata-sync-settings', body);
  return res.data;
};

export const getSystemEngineConfig = async (): Promise<SystemEngineConfig> => {
  const res = await api.get('/system-config');
  return res.data;
};

export const saveSystemEngineConfig = async (config: Partial<SystemEngineConfig>): Promise<SystemEngineConfig> => {
  const res = await api.post('/system-config', config);
  return res.data;
};

export const testNasPath = async (path: string): Promise<NasPathTestResult> => {
  const res = await api.post('/system-config/test-nas-path', { path });
  return res.data;
};

export const probeAndResetCircuitBreaker = async (forceReset = false): Promise<CircuitBreakerResetResult> => {
  const res = await api.post(`/system-config/circuit-breaker/probe-and-reset?forceReset=${forceReset}`);
  return res.data;
};

export const testLoggingSink = async (payload: {
  sink: string;
  url?: string;
  token?: string;
  host?: string;
  filePath?: string;
}): Promise<LoggingSinkTestResult> => {
  const res = await api.post('/system-config/test-logging-sink', payload);
  return res.data;
};

