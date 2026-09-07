import { JobProgress, SyncJob, SyncStatus } from '../types';

const LIVE_STATUSES: SyncStatus[] = ['IN_PROGRESS', 'QUEUED'];

export type RunFilter = 'ACTIVE' | 'SUCCESS' | 'FAILED' | 'SKIPPED' | 'CANCELLED' | 'ALL';

export function isLiveJobStatus(status?: SyncStatus | string | null): boolean {
  return status != null && LIVE_STATUSES.includes(status as SyncStatus);
}

export function matchesRunFilter(job: SyncJob, filter: RunFilter): boolean {
  switch (filter) {
    case 'ACTIVE':
      return isLiveJobStatus(job.status);
    case 'SUCCESS':
      return job.status === 'SUCCESS';
    case 'FAILED':
      return job.status === 'FAILED' || job.status === 'DEAD_LETTERED' || job.status === 'CONFLICT_ISOLATED' || job.status === 'INTERRUPTED' || job.status === 'PAUSED';
    case 'SKIPPED':
      return job.status === 'SKIPPED';
    case 'CANCELLED':
      return job.status === 'CANCELLED';
    case 'ALL':
    default:
      return true;
  }
}

export function countRunsByFilter(jobs: SyncJob[], filter: RunFilter): number {
  return jobs.filter((j) => matchesRunFilter(j, filter)).length;
}

export function resolveJobElapsedMs(
  job: Pick<SyncJob, 'status' | 'startedAt' | 'completedAt' | 'durationMs'>,
  nowMs: number = Date.now(),
  progress?: JobProgress | null
): number | null {
  if (isLiveJobStatus(job.status)) {
    const fromStart = job.startedAt
      ? Math.max(0, nowMs - new Date(job.startedAt).getTime())
      : null;
    const fromDb = job.durationMs != null && job.durationMs >= 0 ? job.durationMs : null;
    const fromWs = progress?.wallElapsedMs != null && progress.wallElapsedMs >= 0
      ? progress.wallElapsedMs
      : null;
    const candidates = [fromStart, fromDb, fromWs].filter((v): v is number => v != null);
    return candidates.length > 0 ? Math.max(...candidates) : null;
  }
  if (job.durationMs != null && job.durationMs >= 0) {
    return job.durationMs;
  }
  if (job.startedAt && job.completedAt) {
    return Math.max(0, new Date(job.completedAt).getTime() - new Date(job.startedAt).getTime());
  }
  return null;
}

export function emptyStateForFilter(filter: RunFilter): string {
  switch (filter) {
    case 'ACTIVE':
      return 'No sync runs in progress.';
    case 'SUCCESS':
      return 'No successful runs recorded.';
    case 'FAILED':
      return 'No failed, interrupted, or dead-lettered runs.';
    case 'SKIPPED':
      return 'No skipped webhook or dedup runs.';
    case 'CANCELLED':
      return 'No cancelled runs.';
    default:
      return 'No sync execution records found.';
  }
}
