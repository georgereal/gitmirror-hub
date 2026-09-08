import axios from 'axios';
import {
  RepoMapping,
  SyncJob,
  SyncAuditLog,
  QueueStatus,
  MessagingModuleInfo,
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

export const updateMapping = async (id: number, mapping: Partial<RepoMapping>): Promise<RepoMapping> => {
  const res = await api.put(`/mappings/${id}`, mapping);
  return res.data;
};

export const deleteMapping = async (id: number): Promise<void> => {
  await api.delete(`/mappings/${id}`);
};

export const triggerManualSync = async (
  id: number,
  branch = '*',
  direction = 'A_TO_B',
  overwriteFromSource = false,
  startFresh = false
): Promise<SyncJob> => {
  const res = await api.post(`/mappings/${id}/sync`, { branch, direction, overwriteFromSource, startFresh });
  return res.data;
};

export const syncPullRequests = async (mappingId: number): Promise<{ syncedCount: number; message: string }> => {
  const res = await api.post(`/mappings/${mappingId}/sync-prs`);
  return res.data;
};

export const syncLfsObjects = async (mappingId: number): Promise<{ syncedCount: number; bytesTransferred?: number; message: string; error?: string }> => {
  const res = await api.post(`/mappings/${mappingId}/sync-lfs`);
  return res.data;
};

export const syncReleases = async (mappingId: number): Promise<{ syncedCount: number; message: string }> => {
  const res = await api.post(`/mappings/${mappingId}/sync-releases`);
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
  mappingId?: number,
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

export const getJob = async (id: number): Promise<SyncJob> => {
  const res = await api.get(`/jobs/${id}`);
  return res.data;
};

export const getJobLogs = async (id: number): Promise<SyncAuditLog[]> => {
  const res = await api.get(`/jobs/${id}/logs`);
  return res.data;
};

export const getDashboardStats = async (): Promise<DashboardStats> => {
  const res = await api.get('/jobs/stats');
  return res.data;
};

export const retryJob = async (id: number): Promise<SyncJob> => {
  const res = await api.post(`/jobs/${id}/retry`);
  return res.data;
};

export const resumeJob = async (id: number): Promise<SyncJob> => {
  const res = await api.post(`/jobs/${id}/resume`);
  return res.data;
};

export const dispatchJobs = async (
  jobIds: number[]
): Promise<{ dispatched: number; skipped: number; errors: string[] }> => {
  const res = await api.post('/jobs/dispatch', { jobIds });
  return res.data;
};

export const pauseJob = async (id: number): Promise<SyncJob> => {
  const res = await api.post(`/jobs/${id}/pause`);
  return res.data;
};

export const skipJobStage = async (id: number, stageId: string): Promise<SyncJob> => {
  const res = await api.post(`/jobs/${id}/skip-stage`, { stageId });
  return res.data;
};

export const cancelJob = async (id: number): Promise<SyncJob> => {
  const res = await api.post(`/jobs/${id}/cancel`);
  return res.data;
};

export const cancelQueuedJobs = async (mappingId?: number): Promise<{ cancelledCount: number; message: string }> => {
  const res = await api.post('/jobs/cancel-queued', null, { params: mappingId ? { mappingId } : {} });
  return res.data;
};

export const getQueueStatus = async (): Promise<QueueStatus> => {
  const res = await api.get('/queue/status');
  return res.data;
};

export const getMessagingModule = async (): Promise<MessagingModuleInfo> => {
  const res = await api.get('/messaging');
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
  mappingId: number;
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
  credentialId?: number;
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
  credentialId?: number;
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

export const updateScmCredential = async (id: number, body: Record<string, unknown>): Promise<import('../types').ScmCredential> => {
  const res = await api.put(`/scm-credentials/${id}`, body);
  return res.data;
};

export const deleteScmCredential = async (id: number): Promise<void> => {
  await api.delete(`/scm-credentials/${id}`);
};

export const listScmInstallations = async (id: number): Promise<import('../types').ScmInstallationOption[]> => {
  const res = await api.get(`/scm-credentials/${id}/installations`);
  return res.data;
};

export const searchCredentialRepositories = async (
  id: number,
  params: { query?: string; page?: number; limit?: number; access?: string }
): Promise<RepoSearchResult> => {
  const res = await api.get(`/scm-credentials/${id}/repositories`, { params });
  return res.data;
};

export const testScmCredential = async (id: number): Promise<PermissionCheckReport> => {
  const res = await api.post(`/scm-credentials/${id}/test`, {});
  return res.data;
};

export const createRemoteRepository = async (data: {
  repoUrl?: string;
  name?: string;
  owner?: string;
  isPrivate?: boolean;
  description?: string;
  credentialId?: number;
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

function syncDiffCacheKey(mappingId: number, options?: SyncDiffOptions): string {
  return `${mappingId}:${options?.refresh ? '1' : '0'}:${options?.metadata ? '1' : '0'}:${options?.maxBranches ?? ''}:${options?.branchOffset ?? ''}:${options?.branchSearch ?? ''}:${options?.branchStatus ?? ''}`;
}

export const getSyncDiffReport = async (
  mappingId: number,
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

export const getMappingConflicts = async (id: number): Promise<SyncConflictRecord[]> => {
  const res = await api.get(`/mappings/${id}/conflicts`);
  return res.data;
};

export const resolveMappingConflict = async (mappingId: number, conflictId: number): Promise<SyncConflictRecord> => {
  const res = await api.post(`/mappings/${mappingId}/conflicts/${conflictId}/resolve`);
  return res.data;
};

export const openConflictPr = async (mappingId: number, conflictId: number): Promise<SyncConflictRecord> => {
  const res = await api.post(`/mappings/${mappingId}/conflicts/${conflictId}/open-pr`);
  return res.data;
};

export const getUnmappedWebhooks = async (): Promise<UnmappedWebhookEvent[]> => {
  const res = await api.get('/unmapped-webhooks');
  return res.data;
};

export const deleteUnmappedWebhook = async (id: number): Promise<void> => {
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

