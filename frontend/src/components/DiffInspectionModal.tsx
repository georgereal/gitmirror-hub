import React from 'react';
import { X, RefreshCw, GitBranch, CheckCircle2, AlertCircle, Search } from 'lucide-react';
import { DiffInspectionProgress, RepoMapping, SyncDiffReport } from '../types';
import { SyncPipelineStepper } from './SyncPipelineStepper';

interface DiffInspectionModalProps {
  open: boolean;
  onClose: () => void;
  mapping: RepoMapping;
  loading: boolean;
  progress: DiffInspectionProgress | null;
  error: string | null;
  diffReport: SyncDiffReport | null;
  onRetry: () => void;
}

export const DiffInspectionModal: React.FC<DiffInspectionModalProps> = ({
  open,
  onClose,
  mapping,
  loading,
  progress,
  error,
  diffReport,
  onRetry,
}) => {
  if (!open) {
    return null;
  }

  const running = loading;
  const percent = progress?.percent ?? 0;
  const counts = progress?.counts;
  const reportCounts = diffReport
    ? {
        inSync: diffReport.inSyncBranchesCount ?? 0,
        pending: diffReport.pendingBranchesCount ?? 0,
        diverged: diffReport.divergedBranchesCount ?? 0,
        destOnly: diffReport.destOnlyBranchesCount ?? 0,
        total: diffReport.totalBranchesCount ?? 0,
      }
    : null;

  return (
    <div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-center justify-center p-2 sm:p-4 md:p-6">
      <div className="bg-white border border-zinc-200 shadow-2xl overflow-hidden flex flex-col w-full max-w-3xl max-h-[90vh] rounded-2xl">
        <div className="px-5 py-3.5 border-b border-zinc-100 flex items-center justify-between bg-white shrink-0">
          <div className="flex items-center space-x-3 min-w-0">
            <div className="w-9 h-9 rounded-xl bg-indigo-600 text-white flex items-center justify-center shrink-0">
              <Search className="w-4 h-4" />
            </div>
            <div className="min-w-0">
              <h3 className="text-sm font-bold text-zinc-900 truncate">Refresh Diff — {mapping.name}</h3>
              <p className="text-[11px] text-zinc-500 font-mono truncate mt-0.5">
                Live source ↔ destination inspection
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="p-1.5 text-zinc-400 hover:text-zinc-700 rounded-lg hover:bg-zinc-100 transition-colors"
            title="Close (inspection continues in background)"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {running ? (
          <div className="px-5 py-3 bg-gradient-to-r from-indigo-50/90 via-blue-50/80 to-indigo-50/90 border-b border-indigo-100 shrink-0">
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center space-x-2.5 min-w-0">
                <div className="w-2.5 h-2.5 rounded-full bg-indigo-600 animate-ping shrink-0" />
                <div className="min-w-0">
                  <div className="text-xs font-semibold text-indigo-950 truncate">
                    {progress?.message || 'Comparing source and destination...'}
                  </div>
                  {(progress?.total ?? 0) > 0 && (
                    <div className="text-[11px] text-indigo-800/80 font-mono mt-0.5">
                      {progress?.current?.toLocaleString()} / {progress?.total?.toLocaleString()} ({percent}%)
                    </div>
                  )}
                </div>
              </div>
              {(progress?.total ?? 0) > 0 && (
                <div className="w-40 shrink-0">
                  <div className="w-full rounded-full bg-indigo-200/70 h-2 overflow-hidden">
                    <div
                      className="h-full rounded-full bg-indigo-600 transition-all duration-300"
                      style={{ width: `${percent}%` }}
                    />
                  </div>
                </div>
              )}
            </div>
          </div>
        ) : error ? (
          <div className="px-5 py-2.5 bg-rose-50 border-b border-rose-100 text-xs text-rose-900 shrink-0 flex items-center gap-2">
            <AlertCircle className="w-4 h-4 shrink-0" />
            <span className="font-mono break-all">{error}</span>
          </div>
        ) : (
          <div className="px-5 py-2.5 bg-emerald-50 border-b border-emerald-100 text-xs text-emerald-900 shrink-0 flex items-center gap-2">
            <CheckCircle2 className="w-4 h-4 shrink-0" />
            <span>Inspection complete — repo page metrics updated.</span>
          </div>
        )}

        <div className="flex-1 overflow-y-auto p-5 space-y-4">
          {(counts || reportCounts) && (
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
              <div className="rounded-lg border border-emerald-200 bg-emerald-50/80 p-3">
                <div className="text-[10px] uppercase tracking-wide text-emerald-700 font-semibold">In sync</div>
                <div className="text-lg font-bold text-emerald-900">
                  {(running ? counts?.inSync : reportCounts?.inSync) ?? 0}
                </div>
              </div>
              <div className="rounded-lg border border-amber-200 bg-amber-50/80 p-3">
                <div className="text-[10px] uppercase tracking-wide text-amber-700 font-semibold">Pending</div>
                <div className="text-lg font-bold text-amber-900">
                  {(running ? counts?.pending : reportCounts?.pending) ?? 0}
                </div>
              </div>
              <div className="rounded-lg border border-rose-200 bg-rose-50/80 p-3">
                <div className="text-[10px] uppercase tracking-wide text-rose-700 font-semibold">Diverged</div>
                <div className="text-lg font-bold text-rose-900">
                  {(running ? counts?.diverged : reportCounts?.diverged) ?? 0}
                </div>
              </div>
              <div className="rounded-lg border border-zinc-200 bg-zinc-50 p-3">
                <div className="text-[10px] uppercase tracking-wide text-zinc-600 font-semibold">Dest-only</div>
                <div className="text-lg font-bold text-zinc-900">
                  {(running ? counts?.destOnly : reportCounts?.destOnly) ?? 0}
                </div>
              </div>
            </div>
          )}

          {progress?.pipeline && (
            <div className="rounded-xl border border-zinc-200 bg-zinc-50/50 p-4">
              <div className="text-xs font-semibold text-zinc-800 mb-3 flex items-center gap-1.5">
                <GitBranch className="w-3.5 h-3.5" />
                Inspection pipeline
              </div>
              <SyncPipelineStepper pipeline={progress.pipeline} />
            </div>
          )}

          {!running && !error && reportCounts && (
            <div className="rounded-lg border border-zinc-200 bg-white p-3 text-xs text-zinc-600">
              Compared <strong className="text-zinc-900">{reportCounts.total.toLocaleString()}</strong> branch
              heads. Pending and diverged refs are listed first on the repo page table.
            </div>
          )}

          {running && !progress?.pipeline && (
            <div className="flex items-center justify-center py-12 text-sm text-zinc-500">
              <RefreshCw className="w-5 h-5 animate-spin mr-2 text-indigo-500" />
              Starting inspection...
            </div>
          )}
        </div>

        <div className="px-5 py-3 border-t border-zinc-100 bg-zinc-50 flex items-center justify-between shrink-0">
          <p className="text-[11px] text-zinc-500">
            {running ? 'You can close this window — inspection continues in the background.' : 'Close to return to the repo page.'}
          </p>
          <div className="flex items-center gap-2">
            {error && (
              <button
                type="button"
                onClick={onRetry}
                className="px-3 py-1.5 text-xs font-medium rounded-lg bg-rose-600 text-white hover:bg-rose-700"
              >
                Retry
              </button>
            )}
            <button
              type="button"
              onClick={onClose}
              className="px-3 py-1.5 text-xs font-medium rounded-lg bg-zinc-900 text-white hover:bg-zinc-800"
            >
              {running ? 'Run in background' : 'Close'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
