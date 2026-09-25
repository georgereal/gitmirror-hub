import React, { useEffect, useState } from 'react';
import { Layers, ChevronDown, ChevronRight, XCircle, Loader2 } from 'lucide-react';
import { cancelBulkSubmission, listBulkSubmissions, BulkSubmissionRecord } from '../services/api';

interface SkippedRow {
  outcome?: string;
  sourceUrl?: string;
  destUrl?: string;
  reason?: string;
}

interface BulkSubmissionsPanelProps {
  /** Optional external refresh signal (e.g. after a new submission). */
  refreshKey?: number;
}

/**
 * Queue Manager panel for bulk migration submissions: per-row outcomes (including skipped
 * rows that never became jobs) and one-click Cancel all for the whole batch's jobs.
 */
export const BulkSubmissionsPanel: React.FC<BulkSubmissionsPanelProps> = ({ refreshKey }) => {
  const [submissions, setSubmissions] = useState<BulkSubmissionRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [cancellingId, setCancellingId] = useState<string | null>(null);
  const [actionMessage, setActionMessage] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      const rows = await listBulkSubmissions();
      setSubmissions(rows || []);
    } catch {
      setSubmissions([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, [refreshKey]);

  if (!loading && submissions.length === 0) {
    return null;
  }

  const handleCancel = async (id: string) => {
    if (!window.confirm(`Cancel every queued and in-progress job of bulk submission #${id}?`)) {
      return;
    }
    setCancellingId(id);
    try {
      const res = await cancelBulkSubmission(id);
      setActionMessage(`Submission #${id}: ${res.cancelled} job(s) cancelled/flagged.`);
      await load();
    } catch (e: any) {
      setActionMessage(e.response?.data?.error || e.message || 'Bulk cancel failed');
    } finally {
      setCancellingId(null);
      setTimeout(() => setActionMessage(null), 6000);
    }
  };

  const parseSkipped = (sub: BulkSubmissionRecord): SkippedRow[] => {
    try {
      const parsed = JSON.parse(sub.skippedJson || '[]');
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  };

  return (
    <div className="rounded-2xl border border-zinc-200/90 bg-white p-6 shadow-sm space-y-3">
      <div className="flex items-center justify-between border-b border-zinc-100 pb-3">
        <div className="flex items-center gap-2">
          <Layers className="w-4 h-4 text-zinc-500" />
          <h3 className="text-sm font-semibold text-zinc-900">Bulk migration submissions</h3>
        </div>
        <button
          type="button"
          onClick={() => void load()}
          className="text-[11px] text-zinc-500 hover:text-zinc-800 font-medium"
        >
          Refresh
        </button>
      </div>

      {actionMessage && (
        <div className="p-2 bg-zinc-50 border border-zinc-200 rounded-lg text-[11px] text-zinc-700">
          {actionMessage}
        </div>
      )}

      <div className="space-y-2">
        {loading ? (
          <div className="py-4 flex items-center justify-center text-zinc-400 text-xs">
            <Loader2 className="w-4 h-4 animate-spin mr-2" /> Loading submissions…
          </div>
        ) : submissions.map((sub) => {
          const skipped = parseSkipped(sub);
          const expanded = expandedId === sub.id;
          return (
            <div key={sub.id} className="border border-zinc-200 rounded-xl overflow-hidden">
              <div
                className="px-3 py-2.5 flex items-center justify-between cursor-pointer hover:bg-zinc-50"
                onClick={() => setExpandedId(expanded ? null : sub.id)}
              >
                <div className="flex items-center gap-2 min-w-0">
                  {expanded ? <ChevronDown className="w-3.5 h-3.5 text-zinc-400" /> : <ChevronRight className="w-3.5 h-3.5 text-zinc-400" />}
                  <span className="text-xs font-semibold text-zinc-900">#{sub.id}</span>
                  <span className="px-2 py-0.5 rounded-full bg-zinc-100 text-zinc-600 text-[10px] font-medium">
                    {sub.mode === 'CREATE_DEST' ? 'create destinations' : 'existing destinations'}
                  </span>
                  {sub.cancelledAt && (
                    <span className="px-2 py-0.5 rounded-full bg-rose-50 text-rose-700 border border-rose-200 text-[10px] font-medium">
                      cancelled
                    </span>
                  )}
                </div>
                <div className="flex items-center gap-3 shrink-0">
                  <span className="text-[11px] text-zinc-500">
                    <span className="font-semibold text-emerald-700">{sub.createdCount}</span> queued ·{' '}
                    <span className="font-semibold text-amber-700">{(sub.itemCount ?? 0) - (sub.createdCount ?? 0)}</span> not queued
                  </span>
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      void handleCancel(sub.id);
                    }}
                    disabled={cancellingId === sub.id || Boolean(sub.cancelledAt)}
                    className="flex items-center gap-1 px-2.5 py-1 rounded-lg border border-zinc-200 text-zinc-700 text-[11px] font-medium hover:bg-rose-50 hover:text-rose-700 hover:border-rose-200 disabled:opacity-40"
                    title="Cancel all jobs of this submission"
                  >
                    {cancellingId === sub.id
                      ? <Loader2 className="w-3 h-3 animate-spin" />
                      : <XCircle className="w-3 h-3" />}
                    Cancel all
                  </button>
                </div>
              </div>

              {expanded && (
                <div className="px-3 py-2 bg-zinc-50/70 border-t border-zinc-100 space-y-1">
                  {skipped.length === 0 ? (
                    <p className="text-[11px] text-zinc-500 py-1">All {sub.createdCount} rows became pairs and queued bootstrap jobs.</p>
                  ) : (
                    skipped.map((row, idx) => (
                      <div key={idx} className="text-[11px] flex items-start gap-2">
                        <span className={`px-1.5 py-0.5 rounded-full text-[9px] font-semibold uppercase shrink-0 ${
                          row.outcome === 'FAILED_VALIDATION' ? 'bg-rose-100 text-rose-700' : 'bg-amber-100 text-amber-700'
                        }`}>
                          {row.outcome || 'SKIPPED'}
                        </span>
                        <span className="font-mono text-zinc-600 truncate">{row.sourceUrl}</span>
                        <span className="text-zinc-400 truncate">→ {row.destUrl || '(created at run time)'}</span>
                        <span className="text-zinc-500 truncate">— {row.reason}</span>
                      </div>
                    ))
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};
