import React, { useEffect, useState } from 'react';
import { RefreshCw, Play, FileText, CheckCircle2, AlertCircle, Clock, Search, Filter } from 'lucide-react';
import { JobProgress, SyncJob } from '../types';
import { formatBytes, formatDuration } from '../utils/format';
import { resolveJobElapsedMs, isLiveJobStatus } from '../utils/jobTiming';
import { JobProgressBar } from './JobProgressBar';
import { pipelineFromJobAndProgress, SyncPipelineStepper } from './SyncPipelineStepper';
import { ProviderTrafficStrip, trafficFromJobAndProgress } from './ProviderTrafficStrip';

interface LiveSyncTableProps {
  jobs: SyncJob[];
  progressByJobId?: Record<number, JobProgress>;
  onViewLogs: (job: SyncJob) => void;
  onRetry: (id: number) => void;
  onCancel?: (id: number) => void;
  onPause?: (id: number) => void;
}

export const LiveSyncTable: React.FC<LiveSyncTableProps> = ({
  jobs,
  progressByJobId = {},
  onViewLogs,
  onRetry,
  onCancel,
  onPause,
}) => {
  const [filterStatus, setFilterStatus] = useState<string>('ALL');
  const [search, setSearch] = useState('');
  const [now, setNow] = useState(Date.now());

  const hasLive = jobs.some((j) => isLiveJobStatus(j.status));
  useEffect(() => {
    if (!hasLive) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [hasLive]);

  const filteredJobs = jobs.filter((job) => {
    if (filterStatus !== 'ALL' && job.status !== filterStatus) return false;
    if (search) {
      const q = search.toLowerCase();
      return (
        job.pairName.toLowerCase().includes(q) ||
        job.branch.toLowerCase().includes(q) ||
        (job.commitSha && job.commitSha.toLowerCase().includes(q)) ||
        (job.commitMessage && job.commitMessage.toLowerCase().includes(q))
      );
    }
    return true;
  });

  const getStatusBadge = (status: string) => {
    switch (status) {
      case 'SUCCESS':
        return (
          <span className="inline-flex items-center space-x-1 bg-emerald-50 text-emerald-700 border border-emerald-200 px-2 py-0.5 rounded-full text-[11px] font-medium">
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" />
            <span>Success</span>
          </span>
        );
      case 'IN_PROGRESS':
        return (
          <span className="inline-flex items-center space-x-1 bg-blue-50 text-blue-700 border border-blue-200 px-2 py-0.5 rounded-full text-[11px] font-medium">
            <RefreshCw className="w-3 h-3 animate-spin" />
            <span>Syncing</span>
          </span>
        );
      case 'QUEUED':
        return (
          <span className="inline-flex items-center space-x-1 bg-zinc-100 text-zinc-600 border border-zinc-200 px-2 py-0.5 rounded-full text-[11px] font-medium">
            <Clock className="w-3 h-3" />
            <span>Queued</span>
          </span>
        );
      case 'CANCELLED':
        return (
          <span className="inline-flex items-center space-x-1 bg-zinc-100 text-zinc-500 border border-zinc-200 px-2 py-0.5 rounded-full text-[11px] font-medium">
            <span>Cancelled</span>
          </span>
        );
      case 'CONFLICT_ISOLATED':
        return (
          <span className="inline-flex items-center space-x-1 bg-amber-50 text-amber-700 border border-amber-300 px-2 py-0.5 rounded-full text-[11px] font-medium" title="Divergence isolated non-destructively to prevent data loss">
            <span className="w-1.5 h-1.5 rounded-full bg-amber-500" />
            <span>Conflict Isolated</span>
          </span>
        );
      case 'SKIPPED':
        return (
          <span className="inline-flex items-center space-x-1 bg-zinc-100 text-zinc-600 border border-zinc-200 px-2 py-0.5 rounded-full text-[11px] font-medium">
            <span>Echo Skipped</span>
          </span>
        );
      case 'FAILED':
      case 'DEAD_LETTERED':
        return (
          <span className="inline-flex items-center space-x-1 bg-rose-50 text-rose-700 border border-rose-200 px-2 py-0.5 rounded-full text-[11px] font-medium">
            <span className="w-1.5 h-1.5 rounded-full bg-rose-500" />
            <span>Failed</span>
          </span>
        );
      default:
        return (
          <span className="bg-zinc-100 text-zinc-600 px-2 py-0.5 rounded-full text-[11px]">
            {status}
          </span>
        );
    }
  };

  const getTriggerBadge = (trigger: string) => {
    switch (trigger) {
      case 'INITIAL_BOOTSTRAP':
        return (
          <span className="inline-flex items-center bg-purple-50 text-purple-700 border border-purple-200/80 px-1.5 py-0.5 rounded text-[10px] font-medium tracking-tight">
            Bootstrap
          </span>
        );
      case 'WEBHOOK':
        return (
          <span className="inline-flex items-center bg-zinc-100 text-zinc-600 border border-zinc-200/80 px-1.5 py-0.5 rounded text-[10px] font-medium tracking-tight">
            Webhook
          </span>
        );
      case 'MANUAL':
        return (
          <span className="inline-flex items-center bg-amber-50 text-amber-700 border border-amber-200/80 px-1.5 py-0.5 rounded text-[10px] font-medium tracking-tight">
            Manual
          </span>
        );
      case 'DLQ_REDRIVE':
        return (
          <span className="inline-flex items-center bg-orange-50 text-orange-700 border border-orange-200/80 px-1.5 py-0.5 rounded text-[10px] font-medium tracking-tight">
            Redrive
          </span>
        );
      case 'SYNTHETIC':
        return (
          <span className="inline-flex items-center bg-indigo-50 text-indigo-700 border border-indigo-200/80 px-1.5 py-0.5 rounded text-[10px] font-medium tracking-tight">
            Sim
          </span>
        );
      default:
        return null;
    }
  };

  const formatTime = (isoString?: string) => {
    if (!isoString) return '--';
    try {
      const d = new Date(isoString);
      return (
        d.toLocaleDateString([], { month: 'short', day: 'numeric' }) +
        ' ' +
        d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
      );
    } catch {
      return isoString;
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div>
          <h2 className="text-base font-semibold text-zinc-900">Sync Activity Stream</h2>
          <p className="text-xs text-zinc-500">Real-time trace of webhook ingestions, queue events, and Git pushes</p>
        </div>

        <div className="flex items-center space-x-2">
          <div className="relative">
            <Search className="w-3.5 h-3.5 text-zinc-400 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Filter by branch, sha..."
              className="bg-white border border-zinc-200 rounded-lg pl-8 pr-3 py-1.5 text-xs text-zinc-900 placeholder:text-zinc-400 focus:outline-none focus:border-zinc-400 w-44"
            />
          </div>

          <select
            value={filterStatus}
            onChange={(e) => setFilterStatus(e.target.value)}
            className="bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-xs text-zinc-700 focus:outline-none focus:border-zinc-400"
          >
            <option value="ALL">All Statuses</option>
            <option value="SUCCESS">Success</option>
            <option value="CONFLICT_ISOLATED">Conflict Isolated</option>
            <option value="FAILED">Failed</option>
            <option value="QUEUED">Queued</option>
            <option value="IN_PROGRESS">In Progress</option>
            <option value="CANCELLED">Cancelled</option>
          </select>
        </div>
      </div>

      <div className="rounded-2xl border border-zinc-200/90 bg-white shadow-sm overflow-hidden">
        {filteredJobs.length === 0 ? (
          <div className="py-12 text-center text-xs text-zinc-400">
            No sync activity recorded matching your filter
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className="bg-zinc-50/70 text-zinc-500 border-b border-zinc-100">
                <tr>
                  <th className="py-2.5 px-4 font-medium">Status</th>
                  <th className="py-2.5 px-4 font-medium">Repository Pair</th>
                  <th className="py-2.5 px-4 font-medium">Ref / Branch</th>
                  <th className="py-2.5 px-4 font-medium">Commit SHA & Message</th>
                  <th className="py-2.5 px-4 font-medium">Time / Duration</th>
                  <th className="py-2.5 px-4 font-medium text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-zinc-100">
                {filteredJobs.map((job) => {
                  const progress = progressByJobId[job.id];
                  const elapsedMs = resolveJobElapsedMs(job, now, progress);
                  const pipeline = pipelineFromJobAndProgress(job, progress);
                  const pushStage = pipeline?.stages.find((s) => s.id === 'push_dest');
                  return (
                  <tr key={job.id} className="hover:bg-zinc-50/70 transition-colors">
                    <td className="py-3 px-4 whitespace-nowrap">
                      {getStatusBadge(job.status)}
                      {job.status === 'IN_PROGRESS' && (
                        <>
                          <SyncPipelineStepper pipeline={pipeline} compact />
                          <JobProgressBar
                            progress={progress}
                            compact
                            elapsedMs={elapsedMs}
                          />
                          <ProviderTrafficStrip
                            traffic={trafficFromJobAndProgress(job, progress?.providerTraffic)}
                            compact
                          />
                        </>
                      )}
                    </td>
                    <td className="py-3 px-4 whitespace-nowrap font-medium text-zinc-900">
                      <div className="flex items-center space-x-2">
                        <span>{job.pairName}</span>
                        {getTriggerBadge(job.triggerType)}
                      </div>
                    </td>
                    <td className="py-3 px-4 whitespace-nowrap font-mono text-zinc-600">
                      {job.branch}
                    </td>
                    <td className="py-3 px-4 max-w-xs truncate">
                      {job.commitSha && (
                        <span className="font-mono text-zinc-500 bg-zinc-100 px-1.5 py-0.5 rounded text-[11px] mr-1.5">
                          {job.commitSha.substring(0, 7)}
                        </span>
                      )}
                      <span className="text-zinc-600">{job.commitMessage || 'Automated Sync'}</span>
                    </td>
                    <td className="py-3 px-4 whitespace-nowrap text-zinc-500 text-[11px]">
                      <div>{formatTime(job.createdAt)}</div>
                      {elapsedMs != null && job.status !== 'IN_PROGRESS' && (
                        <div className="font-mono flex items-center space-x-1">
                          {elapsedMs <= 5 && job.status === 'SUCCESS' ? (
                            <span className="text-amber-600 font-semibold flex items-center">
                              <span>⚡ {formatDuration(elapsedMs)} (Fast-Path)</span>
                            </span>
                          ) : (
                            <span className="text-zinc-500">{formatDuration(elapsedMs)}</span>
                          )}
                        </div>
                      )}
                      {job.status === 'IN_PROGRESS' && elapsedMs != null && (
                        <div className="font-mono text-blue-600">
                          {formatDuration(elapsedMs)}
                        </div>
                      )}
                      {job.bytesTransferred != null && job.bytesTransferred > 0 && (
                        <div className="font-mono text-zinc-400">{formatBytes(job.bytesTransferred)}</div>
                      )}
                    </td>
                    <td className="py-3 px-4 whitespace-nowrap text-right">
                      <div className="flex items-center justify-end space-x-1.5">
                        <button
                          onClick={() => onViewLogs(job)}
                          className="px-2.5 py-1 rounded bg-zinc-100 hover:bg-zinc-200 text-zinc-700 text-[11px] font-medium transition-colors"
                        >
                          Logs
                        </button>

                        {(job.status === 'QUEUED' || job.status === 'IN_PROGRESS') && (onPause || onCancel) && (
                          <>
                            {onPause && (
                              <button
                                onClick={() => onPause(job.id)}
                                className="px-2.5 py-1 rounded bg-amber-600 hover:bg-amber-700 text-white text-[11px] font-medium transition-colors"
                                title="Pause — checkpoint preserved"
                              >
                                Pause
                              </button>
                            )}
                            {onCancel && (
                              <button
                                onClick={() => onCancel(job.id)}
                                className="px-2.5 py-1 rounded bg-white hover:bg-rose-50 border border-zinc-200 hover:border-rose-200 text-zinc-700 hover:text-rose-700 text-[11px] font-medium transition-colors"
                                title="Cancel — clears resume checkpoint"
                              >
                                Cancel
                              </button>
                            )}
                          </>
                        )}
                        {(job.status === 'FAILED' || job.status === 'DEAD_LETTERED') && (
                          <button
                            onClick={() => onRetry(job.id)}
                            className="px-2.5 py-1 rounded bg-zinc-900 hover:bg-zinc-800 text-white text-[11px] font-medium transition-colors"
                          >
                            Retry
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
};
