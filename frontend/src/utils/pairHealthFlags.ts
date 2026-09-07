import { RepoMapping, SyncStatus } from '../types';

export type HealthTone = 'ok' | 'warn' | 'muted';
export type HealthIcon = 'branch' | 'tag' | 'pr' | 'lfs' | 'checkpoint' | 'conflict';

export interface HealthChip {
  id: string;
  label: string;
  tone: HealthTone;
  icon?: HealthIcon;
}

function fmt(n: number): string {
  return n.toLocaleString();
}

function hasCount(value?: number | null): value is number {
  return value != null && Number.isFinite(value);
}

export function hasPairSyncHistory(mapping: RepoMapping): boolean {
  return !!(
    mapping.lastSyncAt
    || mapping.lastMirrorJobId
    || mapping.diffSnapshotAt
    || hasCount(mapping.sourceBranchesCount)
    || hasCount(mapping.lastMirrorBranchesCount)
    || hasCount(mapping.lastMirrorTagsCount)
    || hasCount(mapping.lastMirrorLfsObjects)
    || hasCount(mapping.lfsTotal)
    || hasCount(mapping.prsTotal)
  );
}

export function providerLabel(provider?: string | null): string | null {
  if (!provider) return null;
  switch (provider.toUpperCase()) {
    case 'GITHUB':
      return 'GitHub';
    case 'GITHUB_ENTERPRISE':
    case 'GHES':
      return 'GitHub Enterprise';
    case 'GITLAB':
      return 'GitLab';
    case 'BITBUCKET':
      return 'Bitbucket';
    case 'ORIGIN':
      return 'Origin';
    case 'GENERIC':
      return 'Git';
    default:
      return provider;
  }
}

export function lastSyncStatusLabel(status?: SyncStatus | string | null): string {
  switch (status) {
    case 'SUCCESS':
      return 'Synchronized';
    case 'FAILED':
    case 'DEAD_LETTERED':
      return 'Failed';
    case 'CONFLICT_ISOLATED':
      return 'Conflict';
    case 'INTERRUPTED':
      return 'Interrupted';
    case 'PAUSED':
      return 'Paused';
    case 'IN_PROGRESS':
      return 'Syncing';
    case 'QUEUED':
      return 'Queued';
    case 'CANCELLED':
      return 'Cancelled';
    case 'SKIPPED':
      return 'Skipped';
    default:
      return 'Ready';
  }
}

export function pairHealthChips(mapping: RepoMapping): HealthChip[] {
  if (!hasPairSyncHistory(mapping)) {
    return [{ id: 'none', label: 'Not synced yet', tone: 'muted' }];
  }

  const chips: HealthChip[] = [];
  const pending = mapping.pendingBranchesCount ?? 0;
  const diverged = mapping.divergedBranchesCount ?? 0;
  const branchWarn = pending > 0 || diverged > 0;

  if (hasCount(mapping.destBranchesCount) && hasCount(mapping.inSyncBranchesCount)) {
    chips.push({
      id: 'branches',
      label: `${fmt(mapping.inSyncBranchesCount)}/${fmt(mapping.destBranchesCount)} branches`,
      tone: branchWarn ? 'warn' : 'ok',
      icon: 'branch',
    });
  } else if (hasCount(mapping.sourceBranchesCount) && hasCount(mapping.destBranchesCount)) {
    chips.push({
      id: 'branches',
      label: `${fmt(mapping.destBranchesCount)}/${fmt(mapping.sourceBranchesCount)} branches`,
      tone: branchWarn ? 'warn' : 'ok',
      icon: 'branch',
    });
  } else {
    const count = mapping.sourceBranchesCount ?? mapping.lastMirrorBranchesCount;
    if (hasCount(count) && count > 0) {
      chips.push({
        id: 'branches',
        label: `${fmt(count)} branch${count === 1 ? '' : 'es'}`,
        tone: 'ok',
        icon: 'branch',
      });
    }
  }

  if (hasCount(mapping.tagsSourceCount) && hasCount(mapping.tagsTargetCount)) {
    const inSync = mapping.tagsTargetCount >= mapping.tagsSourceCount;
    chips.push({
      id: 'tags',
      label: `${fmt(mapping.tagsTargetCount)}/${fmt(mapping.tagsSourceCount)} tags`,
      tone: inSync ? 'ok' : 'warn',
      icon: 'tag',
    });
  } else {
    const count = mapping.tagsSourceCount ?? mapping.lastMirrorTagsCount;
    if (hasCount(count) && count > 0) {
      chips.push({
        id: 'tags',
        label: `${fmt(count)} tag${count === 1 ? '' : 's'}`,
        tone: 'ok',
        icon: 'tag',
      });
    }
  }

  if (hasCount(mapping.prsTotal) || hasCount(mapping.prsSynced)) {
    const total = mapping.prsTotal ?? mapping.prsSynced ?? 0;
    const synced = mapping.prsSynced ?? 0;
    chips.push({
      id: 'prs',
      label: `${fmt(synced)}/${fmt(total)} PRs`,
      tone: synced >= total ? 'ok' : 'warn',
      icon: 'pr',
    });
  }

  if (hasCount(mapping.lfsTotal) || hasCount(mapping.lastMirrorLfsObjects)) {
    const total = mapping.lfsTotal ?? mapping.lastMirrorLfsObjects ?? 0;
    const synced = mapping.lfsSynced ?? total;
    const pendingLfs = mapping.lfsPending ?? Math.max(0, total - synced);
    chips.push({
      id: 'lfs',
      label: total === synced
        ? `${fmt(total)} LFS`
        : `${fmt(synced)}/${fmt(total)} LFS`,
      tone: pendingLfs > 0 ? 'warn' : 'ok',
      icon: 'lfs',
    });
  }

  if (mapping.hasCheckpoint) {
    chips.push({
      id: 'checkpoint',
      label: 'Checkpoint',
      tone: 'warn',
      icon: 'checkpoint',
    });
  }

  if (mapping.lastSyncStatus === 'CONFLICT_ISOLATED') {
    chips.push({
      id: 'conflict',
      label: 'Conflict isolated',
      tone: 'warn',
      icon: 'conflict',
    });
  }

  if (chips.length > 0) {
    return chips;
  }
  if (mapping.lastSyncStatus === 'SUCCESS') {
    return [{ id: 'synced', label: 'Git objects synced', tone: 'ok', icon: 'branch' }];
  }
  return [{ id: 'none', label: 'Not synced yet', tone: 'muted' }];
}
