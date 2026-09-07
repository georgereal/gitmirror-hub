import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowRight,
  ChevronLeft,
  ChevronRight,
  GitBranch,
  RefreshCw,
  Search,
} from 'lucide-react';
import { getSyncDiffReport, SyncDiffOptions } from '../services/api';
import { BranchDiffDetail, RepoMapping, SyncDiffReport } from '../types';
import { isActionablePending } from '../utils/syncDiff';

const PAGE_SIZES = [25, 50, 100] as const;
type BranchStatusFilter = NonNullable<SyncDiffOptions['branchStatus']>;

interface BranchComparisonTableProps {
  mapping: RepoMapping;
  diffReport: SyncDiffReport | null;
  summaryReport?: SyncDiffReport | null;
  initialLoading: boolean;
  fullDiffLoading: boolean;
  onReportUpdate: (report: SyncDiffReport) => void;
  onConfirmOverwrite: (branch: string | null) => void;
  confirmOverwrite: string | null;
  triggeringBranch: string | null;
  fullDiffLoadingBlocked: boolean;
  onSyncBranch: (branch: string, overwrite?: boolean) => void;
  getBranchStatusBadge: (status: string, forkPrHead?: boolean) => React.ReactNode;
}

export const BranchComparisonTable: React.FC<BranchComparisonTableProps> = ({
  mapping,
  diffReport,
  summaryReport = null,
  initialLoading,
  fullDiffLoading,
  onReportUpdate,
  onConfirmOverwrite,
  confirmOverwrite,
  triggeringBranch,
  fullDiffLoadingBlocked,
  onSyncBranch,
  getBranchStatusBadge,
}) => {
  const [searchInput, setSearchInput] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<BranchStatusFilter>('ALL');
  const [pageSize, setPageSize] = useState(50);
  const [pageOffset, setPageOffset] = useState(0);
  const [pageLoading, setPageLoading] = useState(false);
  const [pageError, setPageError] = useState<string | null>(null);
  const skipFilterFetchRef = useRef(true);

  useEffect(() => {
    const timer = window.setTimeout(() => setSearchQuery(searchInput.trim()), 300);
    return () => window.clearTimeout(timer);
  }, [searchInput]);

  useEffect(() => {
    setSearchInput('');
    setSearchQuery('');
    setStatusFilter('ALL');
    setPageSize(50);
    setPageOffset(0);
    setPageError(null);
  }, [mapping.id]);

  const headerReport = summaryReport ?? diffReport;
  const filteredTotal = diffReport?.branchesFilteredCount ?? headerReport?.totalBranchesCount ?? 0;
  const currentOffset = diffReport?.branchesPageOffset ?? pageOffset;
  const currentPageSize = diffReport?.branchesPageSize ?? pageSize;
  const pageStart = filteredTotal === 0 ? 0 : currentOffset + 1;
  const pageEnd = Math.min(currentOffset + (diffReport?.branches?.length ?? 0), filteredTotal);
  const hasNext = Boolean(diffReport?.branchesTruncated);

  const fetchPage = async (
    offset: number,
    nextPageSize = pageSize,
    nextSearch = searchQuery,
    nextStatus = statusFilter
  ) => {
    setPageLoading(true);
    setPageError(null);
    try {
      const report = await getSyncDiffReport(mapping.id, {
        maxBranches: nextPageSize,
        branchOffset: offset,
        branchSearch: nextSearch || undefined,
        branchStatus: nextStatus,
      });
      onReportUpdate(report);
      setPageOffset(offset);
      setPageSize(nextPageSize);
    } catch (e: unknown) {
      setPageError(e instanceof Error ? e.message : 'Failed to load branch page');
    } finally {
      setPageLoading(false);
    }
  };

  useEffect(() => {
    skipFilterFetchRef.current = true;
  }, [mapping.id]);

  useEffect(() => {
    if (skipFilterFetchRef.current) {
      if (!initialLoading && diffReport) {
        skipFilterFetchRef.current = false;
      }
      return;
    }
    if (initialLoading || fullDiffLoading) {
      return;
    }
    void fetchPage(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchQuery, statusFilter]);

  const rows = useMemo(() => {
    const list = diffReport?.branches || [];
    const rank = (status: string) => {
      if (status === 'DIVERGED') return 0;
      if (isActionablePending(status, mapping.syncDirection)) return 1;
      if (status === 'SOURCE_MISSING') return 2;
      return 3;
    };
    return [...list].sort(
      (a, b) => rank(a.status) - rank(b.status) || a.branchName.localeCompare(b.branchName)
    );
  }, [diffReport?.branches, mapping.syncDirection]);

  const renderRow = (b: BranchDiffDetail) => (
    <div
      key={b.branchName}
      className="p-4 sm:px-6 hover:bg-zinc-50/70 transition-colors flex flex-col sm:flex-row sm:items-center justify-between gap-3"
    >
      <div className="space-y-1.5">
        <div className="flex items-center space-x-2.5">
          <span className="font-mono text-xs font-semibold text-zinc-900 flex items-center space-x-1">
            <GitBranch className="w-3.5 h-3.5 text-zinc-500" />
            <span>{b.branchName}</span>
          </span>
          {getBranchStatusBadge(b.status, b.forkPrHead)}
        </div>

        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-[11px] text-zinc-500 font-mono">
          <div className="flex items-center space-x-1.5">
            <span className="text-zinc-400">Source:</span>
            <span className="bg-zinc-100 px-1.5 py-0.5 rounded text-zinc-800 font-semibold">
              {b.sourceShortSha || '--'}
            </span>
          </div>
          <ArrowRight className="w-3 h-3 text-zinc-400" />
          <div className="flex items-center space-x-1.5">
            <span className="text-zinc-400">Target:</span>
            <span className="bg-zinc-100 px-1.5 py-0.5 rounded text-zinc-800 font-semibold">
              {b.targetShortSha || 'Not created'}
            </span>
          </div>
          {b.aheadCount > 0 && (
            <span className="text-amber-600 font-sans font-medium">
              ({b.aheadCount} commit{b.aheadCount > 1 ? 's' : ''} ahead)
            </span>
          )}
          {b.lastCommitMessage && (
            <span className="text-zinc-400 font-sans truncate max-w-[260px]" title={b.lastCommitMessage}>
              "{b.lastCommitMessage}" {b.lastCommitAuthor ? `— ${b.lastCommitAuthor}` : ''}
            </span>
          )}
        </div>
        {b.status === 'DIVERGED' && (
          <p className="text-[11px] text-rose-600/90 font-sans max-w-xl">
            Destination {b.targetShortSha} is not an ancestor of source {b.sourceShortSha}.
          </p>
        )}
      </div>

      <div className="flex items-center space-x-2 shrink-0">
        {b.status === 'DIVERGED' ? (
          confirmOverwrite === b.branchName ? (
            <div className="flex flex-col items-end gap-1.5 max-w-[260px]">
              <p className="text-[10px] leading-snug text-rose-700 text-right">
                Force-push source {b.sourceShortSha} onto destination {b.targetShortSha}.
                Destination-only commits on this branch will no longer be the tip.
              </p>
              <div className="flex items-center space-x-1.5">
                <button
                  type="button"
                  onClick={() => onConfirmOverwrite(null)}
                  className="px-2.5 py-1.5 rounded-lg border border-zinc-200 text-zinc-600 hover:bg-zinc-100 text-xs font-medium"
                >
                  Cancel
                </button>
                <button
                  type="button"
                  onClick={() => {
                    onConfirmOverwrite(null);
                    onSyncBranch(b.branchName, true);
                  }}
                  disabled={triggeringBranch === b.branchName || fullDiffLoadingBlocked}
                  className="inline-flex items-center space-x-1 px-2.5 py-1.5 rounded-lg bg-rose-600 hover:bg-rose-700 text-white text-xs font-medium disabled:opacity-50"
                >
                  Confirm overwrite
                </button>
              </div>
            </div>
          ) : (
            <div className="flex items-center space-x-1.5">
              <button
                type="button"
                onClick={() => onSyncBranch(b.branchName)}
                disabled={triggeringBranch === b.branchName || fullDiffLoadingBlocked}
                className="inline-flex items-center space-x-1 px-2.5 py-1.5 rounded-lg border border-zinc-200 text-zinc-700 hover:bg-zinc-100 text-xs font-medium disabled:opacity-50 shadow-sm"
                title="Keep destination tip and push source onto a sync-conflict/* branch"
              >
                Isolate
              </button>
              <button
                type="button"
                onClick={() => onConfirmOverwrite(b.branchName)}
                disabled={triggeringBranch === b.branchName || fullDiffLoadingBlocked}
                className="inline-flex items-center space-x-1 px-2.5 py-1.5 rounded-lg border border-rose-200 text-rose-700 hover:bg-rose-50 text-xs font-medium disabled:opacity-50 shadow-sm"
                title={`Replace destination ${b.branchName} with source`}
              >
                Overwrite from source
              </button>
            </div>
          )
        ) : b.status !== 'IN_SYNC' && b.status !== 'SOURCE_MISSING' ? (
          <button
            type="button"
            onClick={() => onSyncBranch(b.branchName)}
            disabled={triggeringBranch === b.branchName || fullDiffLoadingBlocked}
            className="inline-flex items-center space-x-1 px-2.5 py-1.5 rounded-lg border border-zinc-200 text-zinc-700 hover:bg-zinc-100 text-xs font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed shadow-sm"
            title={fullDiffLoadingBlocked ? 'Waiting for inspection to complete...' : `Sync ${b.branchName}`}
          >
            <RefreshCw className={`w-3 h-3 ${triggeringBranch === b.branchName ? 'animate-spin' : ''}`} />
            <span>{triggeringBranch === b.branchName ? 'Syncing...' : `Sync ${b.branchName}`}</span>
          </button>
        ) : null}
      </div>
    </div>
  );

  return (
    <div className="rounded-2xl border border-zinc-200/90 bg-white shadow-sm overflow-hidden">
      <div className="px-6 py-3.5 border-b border-zinc-100 bg-zinc-50/50 space-y-3">
        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-3">
          <div>
            <h4 className="text-xs font-semibold text-zinc-900">Branch Comparison Details</h4>
            <p className="text-[11px] text-zinc-500 mt-0.5">
              {filteredTotal.toLocaleString()} matching ref{filteredTotal === 1 ? '' : 's'}
              {headerReport?.totalBranchesCount
                ? ` of ${headerReport.totalBranchesCount.toLocaleString()} tracked`
                : ''}
              {headerReport?.metadataDeferred ? ' · SHA-only scan until Refresh Diff' : ''}
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <div className="relative">
              <Search className="w-3.5 h-3.5 text-zinc-400 absolute left-2.5 top-1/2 -translate-y-1/2" />
              <input
                type="text"
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                placeholder="Filter branch name..."
                className="bg-white border border-zinc-200 rounded-lg pl-8 pr-3 py-1.5 text-xs text-zinc-900 focus:outline-none focus:border-zinc-400 w-52"
              />
            </div>
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value as BranchStatusFilter)}
              className="bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-xs text-zinc-900 focus:outline-none focus:border-zinc-400"
            >
              <option value="ALL">All statuses</option>
              <option value="ACTIONABLE">Needs action</option>
              <option value="PENDING">Pending</option>
              <option value="DIVERGED">Diverged</option>
              <option value="DEST_ONLY">Destination only</option>
              <option value="IN_SYNC">In sync</option>
            </select>
            <select
              value={pageSize}
              onChange={(e) => {
                const next = Number(e.target.value);
                setPageSize(next);
                void fetchPage(0, next);
              }}
              className="bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-xs text-zinc-900 focus:outline-none focus:border-zinc-400"
            >
              {PAGE_SIZES.map((size) => (
                <option key={size} value={size}>
                  {size} / page
                </option>
              ))}
            </select>
          </div>
        </div>
      </div>

      {pageError && (
        <div className="px-6 py-2 text-xs text-rose-700 bg-rose-50 border-b border-rose-100">{pageError}</div>
      )}

      <div className="divide-y divide-zinc-100 min-h-[120px]">
        {initialLoading && !diffReport ? (
          <div className="p-6 space-y-4 animate-pulse">
            {[1, 2, 3].map((i) => (
              <div key={i} className="flex items-center justify-between py-2">
                <div className="space-y-2 w-1/2">
                  <div className="h-3.5 bg-zinc-200/80 rounded w-1/3" />
                  <div className="h-2.5 bg-zinc-100 rounded w-2/3" />
                </div>
                <div className="h-7 bg-zinc-100 rounded w-24" />
              </div>
            ))}
          </div>
        ) : pageLoading ? (
          <div className="py-12 flex items-center justify-center text-xs text-zinc-500">
            <RefreshCw className="w-4 h-4 animate-spin mr-2" />
            Loading branch page...
          </div>
        ) : rows.length > 0 ? (
          rows.map(renderRow)
        ) : (
          <div className="py-12 text-center text-xs text-zinc-400">
            {fullDiffLoading ? 'Computing live branch diff...' : 'No branches match the current filter'}
          </div>
        )}
      </div>

      <div className="px-6 py-3 border-t border-zinc-100 bg-zinc-50/60 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <span className="text-[11px] text-zinc-500 font-mono">
          {filteredTotal > 0 ? `Showing ${pageStart}-${pageEnd} of ${filteredTotal.toLocaleString()}` : 'No rows'}
        </span>
        <div className="flex items-center gap-2">
          <button
            type="button"
            disabled={pageLoading || currentOffset <= 0}
            onClick={() => void fetchPage(Math.max(0, currentOffset - currentPageSize))}
            className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg border border-zinc-200 text-xs text-zinc-700 hover:bg-white disabled:opacity-40"
          >
            <ChevronLeft className="w-3.5 h-3.5" />
            Previous
          </button>
          <button
            type="button"
            disabled={pageLoading || !hasNext}
            onClick={() => void fetchPage(currentOffset + currentPageSize)}
            className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg border border-zinc-200 text-xs text-zinc-700 hover:bg-white disabled:opacity-40"
          >
            Next
            <ChevronRight className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>
    </div>
  );
};
