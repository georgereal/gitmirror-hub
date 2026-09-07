import { BranchDiffDetail, DiffInspectionProgress, PrSyncDetail, SyncDiffReport, SyncJob } from '../types';
import { parseRejectedRefs } from './liveJobLogs';

const SOURCE_TO_DEST_PENDING = new Set(['AHEAD', 'TARGET_MISSING', 'PENDING_SYNC']);

export function isActionablePending(status: string, direction?: string | null): boolean {
  if (status === 'SOURCE_MISSING') {
    return false;
  }
  if (status === 'BEHIND' && direction === 'UNIDIRECTIONAL_A_TO_B') {
    return false;
  }
  return SOURCE_TO_DEST_PENDING.has(status) || status === 'BEHIND';
}

function branchNameFromRejectedLine(line: string): string | null {
  const match = line.match(/refs\/heads\/([^\s\]]+)/);
  return match ? match[1] : null;
}

function jobTouchesBranch(job: SyncJob, branchName: string): boolean {
  const b = (job.branch || '').trim();
  if (!b || b === '*' || b.includes('All Branches')) {
    return true;
  }
  return b === branchName;
}

function summarizeBranches(branches: BranchDiffDetail[], direction?: string | null) {
  let inSync = 0;
  let pending = 0;
  let diverged = 0;
  let destOnly = 0;
  for (const branch of branches) {
    if (branch.status === 'DIVERGED') {
      diverged += 1;
    } else if (branch.status === 'SOURCE_MISSING') {
      destOnly += 1;
    } else if (branch.status === 'IN_SYNC' || !isActionablePending(branch.status, direction)) {
      inSync += 1;
    } else {
      pending += 1;
    }
  }
  const overall: SyncDiffReport['overallStatus'] =
    diverged > 0 ? 'DIVERGED' : pending > 0 ? 'PENDING_SYNC' : 'IN_SYNC';
  return { inSync, pending, diverged, destOnly, overall };
}

function pipelineStageDone(progress: DiffInspectionProgress | null | undefined, stageId: string): boolean {
  return Boolean(progress?.pipeline?.stages?.some((stage) => stage.id === stageId && stage.status === 'done'));
}

/** Overlay WebSocket diff-inspection counts onto the repo page while Refresh Diff is running. */
export function applyLiveDiffProgress(
  report: SyncDiffReport | null,
  progress: DiffInspectionProgress | null | undefined,
  active: boolean
): SyncDiffReport | null {
  if (!report || !active || !progress?.counts) {
    return report;
  }

  const inSync = progress.counts.inSync ?? report.inSyncBranchesCount;
  const pending = progress.counts.pending ?? report.pendingBranchesCount;
  const diverged = progress.counts.diverged ?? report.divergedBranchesCount ?? 0;
  const destOnly = progress.counts.destOnly ?? report.destOnlyBranchesCount ?? 0;
  const countedTotal = inSync + pending + diverged + destOnly;
  const compareDone = pipelineStageDone(progress, 'compare_branches');

  let overallStatus: SyncDiffReport['overallStatus'] = 'IN_SYNC';
  if (diverged > 0) {
    overallStatus = 'DIVERGED';
  } else if (pending > 0) {
    overallStatus = 'PENDING_SYNC';
  }

  return {
    ...report,
    inSyncBranchesCount: inSync,
    pendingBranchesCount: pending,
    divergedBranchesCount: diverged,
    destOnlyBranchesCount: destOnly,
    totalBranchesCount: countedTotal > 0 ? countedTotal : report.totalBranchesCount,
    overallStatus,
    metadataDeferred: compareDone ? false : report.metadataDeferred,
    fromPersistedSnapshot: compareDone ? false : report.fromPersistedSnapshot,
    inspectionMode: compareDone ? 'full' : report.inspectionMode,
  };
}

export function isPrMirrored(pr: PrSyncDetail): boolean {
  return !!(pr.isSynced || pr.syncStatus === 'MIRRORED' || (pr.targetPrNumber != null && pr.targetPrNumber > 0));
}

export function isPrHandled(pr: PrSyncDetail): boolean {
  return isPrMirrored(pr) || pr.syncStatus === 'SKIPPED';
}

function applyJobToPullRequests(prs: PrSyncDetail[] | undefined, job: SyncJob): PrSyncDetail[] {
  if (!prs?.length) {
    return prs || [];
  }
  let remaining = Math.max(0, (job.prsSyncedCount ?? 0) - prs.filter(isPrMirrored).length);
  return prs.map((pr) => {
    if (isPrHandled(pr) || remaining <= 0) {
      return pr;
    }
    if (pr.syncStatus === 'PENDING' || !pr.syncStatus) {
      remaining -= 1;
      return {
        ...pr,
        isSynced: true,
        syncStatus: 'MIRRORED',
        reason: pr.reason || 'Mirrored in latest sync job',
      };
    }
    return pr;
  });
}

export function normalizeDiffReport(report: SyncDiffReport, direction?: string | null): SyncDiffReport {
  const branches = report.branches || [];
  const total = report.totalBranchesCount ?? 0;
  // Backend sends aggregate counts for every ref; branches[] is only the first table page.
  const trustBackendCounts =
    report.branchesTruncated === true || (total > 0 && branches.length < total);

  if (trustBackendCounts || !branches.length) {
    return report;
  }

  const summary = summarizeBranches(branches, direction);
  return {
    ...report,
    overallStatus: summary.overall,
    inSyncBranchesCount: summary.inSync,
    pendingBranchesCount: summary.pending,
    divergedBranchesCount: summary.diverged,
    destOnlyBranchesCount: summary.destOnly,
  };
}

export function applyJobMirrorMetrics(report: SyncDiffReport, job: SyncJob): SyncDiffReport {
  const lfsDiscovered = job.lfsObjectsCount ?? 0;
  const tagsCount = job.tagsCount ?? report.tags?.targetTagsCount ?? 0;
  const branchesCount = job.branchesCount ?? 0;
  let next: SyncDiffReport = { ...report };
  if (lfsDiscovered > 0) {
    const previousSynced = next.lfs?.syncedCount ?? 0;
    const synced = Math.max(previousSynced, lfsDiscovered);
    next = {
      ...next,
      lfs: {
        totalDiscovered: Math.max(next.lfs?.totalDiscovered ?? 0, lfsDiscovered),
        syncedCount: synced,
        pendingCount: Math.max(0, Math.max(next.lfs?.totalDiscovered ?? 0, lfsDiscovered) - synced),
        inSync: synced >= Math.max(next.lfs?.totalDiscovered ?? 0, lfsDiscovered),
      },
    };
  }
  if (branchesCount > 0) {
    const source = Math.max(next.sourceBranchesCount ?? 0, branchesCount);
    const destOnly = next.destOnlyBranchesCount ?? 0;
    next = {
      ...next,
      sourceBranchesCount: source,
      destBranchesCount: Math.max(next.destBranchesCount ?? 0, source + destOnly),
      inSyncBranchesCount: Math.max(next.inSyncBranchesCount ?? 0, source),
      pendingBranchesCount: 0,
      totalBranchesCount: Math.max(next.totalBranchesCount ?? 0, source + destOnly),
    };
  }
  if (tagsCount > 0) {
    next = {
      ...next,
      tags: {
        sourceTagsCount: Math.max(next.tags?.sourceTagsCount ?? 0, tagsCount),
        targetTagsCount: Math.max(next.tags?.targetTagsCount ?? 0, tagsCount),
        inSync: true,
      },
    };
  }
  if ((job.prsSyncedCount ?? 0) > 0) {
    const synced = job.prsSyncedCount ?? 0;
    const source = Math.max(next.totalOpenPrsCount ?? next.persistedPrsTotal ?? 0, synced);
    next = {
      ...next,
      persistedPrsTotal: source,
      persistedPrsSynced: Math.max(next.persistedPrsSynced ?? 0, synced),
      totalOpenPrsCount: source,
      mirroredPrsCount: Math.max(next.mirroredPrsCount ?? 0, synced),
      destOpenPrsCount: Math.max(next.destOpenPrsCount ?? 0, synced),
      fromPersistedSnapshot: true,
    };
  }
  return next;
}

/**
 * Overlay a completed source→dest mirror onto a previously inspected report
 * without fetching remotes again. Destination-only refs stay as SOURCE_MISSING.
 */
export function applySuccessfulMirrorJob(
  report: SyncDiffReport,
  job: SyncJob,
  direction?: string | null
): SyncDiffReport {
  const rejectedBranches = new Set(
    parseRejectedRefs(job.rejectedPushRefs)
      .map(branchNameFromRejectedLine)
      .filter((name): name is string => !!name)
  );

  const branches = (report.branches || []).map((branch) => {
    if (branch.status === 'SOURCE_MISSING' || branch.status === 'BEHIND') {
      return branch;
    }
    if (branch.status === 'DIVERGED') {
      if (job.status === 'SUCCESS' && jobTouchesBranch(job, branch.branchName) && !rejectedBranches.has(branch.branchName)) {
        return {
          ...branch,
          status: 'IN_SYNC' as const,
          aheadCount: 0,
          behindCount: 0,
          targetSha: branch.sourceSha,
          targetShortSha: branch.sourceShortSha,
        };
      }
      return branch;
    }
    if (!SOURCE_TO_DEST_PENDING.has(branch.status)) {
      return branch;
    }
    if (rejectedBranches.has(branch.branchName)) {
      return branch;
    }
    return {
      ...branch,
      status: 'IN_SYNC' as const,
      aheadCount: 0,
      behindCount: 0,
      targetSha: branch.targetSha || branch.sourceSha,
      targetShortSha: branch.targetShortSha || branch.sourceShortSha,
    };
  });

  const summary = summarizeBranches(branches, direction);
  const withMetrics = applyJobMirrorMetrics(
    {
      ...report,
      branches,
      overallStatus: summary.overall,
      inSyncBranchesCount: summary.inSync,
      pendingBranchesCount: summary.pending,
      divergedBranchesCount: summary.diverged,
      destOnlyBranchesCount: summary.destOnly,
      pullRequests: applyJobToPullRequests(report.pullRequests, job),
    },
    job
  );
  return withMetrics;
}

export function prsAccounted(prs: PrSyncDetail[] | undefined): { handled: number; total: number; pending: number } {
  const list = prs || [];
  const handled = list.filter(isPrHandled).length;
  return { handled, total: list.length, pending: list.length - handled };
}

export function prsAccountedFromReport(report: SyncDiffReport | null | undefined): {
  handled: number;
  total: number;
  pending: number;
  dest: number;
} {
  if (report?.fromPersistedSnapshot && report.persistedPrsTotal != null) {
    const total = report.persistedPrsTotal;
    const handled = report.persistedPrsSynced ?? 0;
    const dest = report.destOpenPrsCount ?? handled;
    return { handled, total, dest, pending: Math.max(0, total - handled) };
  }
  const list = report?.pullRequests || [];
  const total = report?.totalOpenPrsCount && report.totalOpenPrsCount > 0
    ? report.totalOpenPrsCount
    : list.length;
  const handled = report?.mirroredPrsCount != null && report.mirroredPrsCount > 0
    ? report.mirroredPrsCount
    : list.filter(isPrHandled).length;
  const dest = report?.destOpenPrsCount && report.destOpenPrsCount > 0
    ? report.destOpenPrsCount
    : handled;
  return { handled, total, dest, pending: Math.max(0, total - handled) };
}

export function prDiscussionSummary(pr: PrSyncDetail): string | null {
  const comments = pr.commentsCount ?? 0;
  const reviews = pr.reviewCommentsCount ?? 0;
  const total = comments + reviews;
  if (total <= 0) {
    return null;
  }
  if (reviews > 0 && comments > 0) {
    return `${comments} comment(s), ${reviews} review comment(s) on source — not replicated to destination`;
  }
  if (reviews > 0) {
    return `${reviews} review comment(s) on source — not replicated to destination`;
  }
  return `${comments} comment(s) on source — not replicated to destination`;
}
