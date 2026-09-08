import React, { useEffect, useState, useMemo, useRef } from 'react';
import {
  X,
  RefreshCw,
  Terminal,
  CheckCircle2,
  AlertCircle,
  Clock,
  ChevronDown,
  ChevronUp,
  Play,
  RotateCcw,
  Check,
  AlertTriangle,
  GitBranch,
  Filter,
  Tag,
  Database,
  GitPullRequest,
  Sparkles,
  Layers
} from 'lucide-react';
import { SyncJob, SyncAuditLog, JobProgress } from '../types';
import { getJobLogs, getJobs, resumeJob } from '../services/api';
import { formatBytes, formatDuration } from '../utils/format';
import { JobProgressBar } from './JobProgressBar';
import { overlayLiveProgress, finalizeStaleProgressLines, parseRejectedRefs, prepareAuditDisplayLogs } from '../utils/liveJobLogs';
import { ProviderTrafficStrip, trafficFromJobAndProgress } from './ProviderTrafficStrip';
import { JobExecutionSummary } from './JobExecutionSummary';
import { SyncPipelineStepper, pipelineFromJobAndProgress } from './SyncPipelineStepper';
import {
  RunFilter,
  matchesRunFilter,
  countRunsByFilter,
  resolveJobElapsedMs,
  emptyStateForFilter,
  isLiveJobStatus,
} from '../utils/jobTiming';

interface SyncRunsHistoryModalProps {
  isOpen: boolean;
  onClose: () => void;
  pairName: string;
  mappingId: number;
  jobs: SyncJob[];
  progressByJobId?: Record<number, JobProgress>;
  onTriggerSync: (branch?: string, startFresh?: boolean) => Promise<void>;
  hasCheckpoint?: boolean;
  syncCheckpointStage?: string;
  onRefresh: () => void;
  onCancelJob?: (id: number) => Promise<void>;
  onPauseJob?: (id: number) => Promise<void>;
}

export const SyncRunsHistoryModal: React.FC<SyncRunsHistoryModalProps> = ({
  isOpen,
  onClose,
  pairName,
  mappingId,
  jobs,
  progressByJobId = {},
  onTriggerSync,
  onRefresh,
  onCancelJob,
  onPauseJob,
  hasCheckpoint = false,
  syncCheckpointStage,
}) => {
  const [filter, setFilter] = useState<RunFilter>('ACTIVE');
  const [expandedJobId, setExpandedJobId] = useState<number | null>(null);
  const [fullSyncChoiceOpen, setFullSyncChoiceOpen] = useState(false);
  const [jobLogs, setJobLogs] = useState<Record<number, SyncAuditLog[]>>({});
  const [loadingLogs, setLoadingLogs] = useState<Record<number, boolean>>({});
  const [retryingJobId, setRetryingJobId] = useState<number | null>(null);
  const [resumingJobId, setResumingJobId] = useState<number | null>(null);
  const [now, setNow] = useState(Date.now());
  const [historyJobs, setHistoryJobs] = useState<SyncJob[]>([]);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const autoExpandedRef = useRef(false);

  const fetchHistory = async () => {
    setLoadingHistory(true);
    try {
      const res = await getJobs(0, 100, undefined, mappingId);
      if (res && res.content) {
        setHistoryJobs(res.content);
      }
    } catch (e) {
      console.error(`Failed to load history for mapping ${mappingId}:`, e);
    } finally {
      setLoadingHistory(false);
    }
  };

  useEffect(() => {
    if (isOpen) {
      autoExpandedRef.current = false;
      fetchHistory();
    }
  }, [isOpen, mappingId]);

  const pairJobs = useMemo(() => {
    const map = new Map<number, SyncJob>();
    (historyJobs || []).forEach((j) => {
      if (j.mappingId === mappingId) map.set(j.id, j);
    });
    (jobs || []).forEach((j) => {
      if (j.mappingId === mappingId) map.set(j.id, j);
    });
    return Array.from(map.values()).sort((a, b) => b.id - a.id);
  }, [historyJobs, jobs, mappingId]);

  const filteredJobs = pairJobs.filter((job) => matchesRunFilter(job, filter));

  useEffect(() => {
    if (!isOpen || autoExpandedRef.current) return;
    const active = pairJobs.find((j) => j.status === 'IN_PROGRESS');
    if (active) {
      setExpandedJobId(active.id);
      loadLogsForJob(active.id);
      autoExpandedRef.current = true;
    }
  }, [isOpen, pairJobs]);

  const handleManualRefresh = () => {
    fetchHistory();
    onRefresh();
  };

  const loadLogsForJob = async (jobId: number) => {
    if (jobLogs[jobId]) return;
    setLoadingLogs((prev) => ({ ...prev, [jobId]: true }));
    try {
      const logs = await getJobLogs(jobId);
      setJobLogs((prev) => ({ ...prev, [jobId]: logs }));
    } catch (e) {
      console.error(`Failed to load logs for job #${jobId}:`, e);
    } finally {
      setLoadingLogs((prev) => ({ ...prev, [jobId]: false }));
    }
  };

  const toggleExpand = (jobId: number) => {
    if (expandedJobId === jobId) {
      setExpandedJobId(null);
    } else {
      setExpandedJobId(jobId);
      loadLogsForJob(jobId);
    }
  };

  useEffect(() => {
    if (!isOpen || expandedJobId == null) return;
    const job = pairJobs.find((j) => j.id === expandedJobId);
    const inProgress = isLiveJobStatus(job?.status);
    if (!inProgress) return;
    const timer = window.setInterval(() => {
      getJobLogs(expandedJobId)
        .then((logs) => setJobLogs((prev) => ({ ...prev, [expandedJobId]: logs })))
        .catch(console.error);
    }, 1000);
    return () => window.clearInterval(timer);
  }, [isOpen, expandedJobId, pairJobs]);

  useEffect(() => {
    if (!isOpen) return;
    const live = pairJobs.some((j) => isLiveJobStatus(j.status));
    if (!live) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [isOpen, pairJobs]);

  const handleRetry = async (branch?: string, jobId?: number, startFresh = false) => {
    if (jobId) setRetryingJobId(jobId);
    try {
      await onTriggerSync(branch || '*', startFresh);
      onRefresh();
      setFullSyncChoiceOpen(false);
    } finally {
      setRetryingJobId(null);
    }
  };

  const handleResume = async (jobId: number) => {
    setResumingJobId(jobId);
    try {
      await resumeJob(jobId);
      onRefresh();
      await fetchHistory();
    } finally {
      setResumingJobId(null);
    }
  };

  const formatDateTime = (isoString?: string) => {
    if (!isoString) return '--';
    try {
      const d = new Date(isoString);
      return (
        d.toLocaleDateString([], { month: 'short', day: 'numeric', year: 'numeric' }) +
        ' ' +
        d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
      );
    } catch {
      return isoString;
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-center justify-center p-2 sm:p-4 md:p-6 transition-all duration-200">
      <div className="bg-white border border-zinc-200 rounded-2xl w-full max-w-6xl shadow-2xl overflow-hidden max-h-[92vh] flex flex-col">
        {/* Header */}
        <div className="px-6 py-4 border-b border-zinc-100 flex items-center justify-between bg-zinc-50/70">
          <div className="flex items-center space-x-3">
            <div className="w-9 h-9 rounded-xl bg-zinc-900 text-white flex items-center justify-center shadow-xs">
              <Clock className="w-4 h-4" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-zinc-900 flex items-center space-x-2">
                <span>Sync Execution History</span>
                <span className="px-2 py-0.5 rounded-full text-[10px] font-semibold bg-zinc-200/80 text-zinc-700">
                  {pairName}
                </span>
              </h3>
              <p className="text-[11px] text-zinc-500">
                Detailed run logs, synchronization summaries, and error diagnostics
              </p>
            </div>
          </div>

          <div className="flex items-center space-x-2">
            <button
              onClick={handleManualRefresh}
              disabled={loadingHistory}
              className="p-1.5 text-zinc-400 hover:text-zinc-700 rounded-lg hover:bg-zinc-100 transition-colors disabled:opacity-50"
              title="Refresh runs"
            >
              <RefreshCw className={`w-4 h-4 ${loadingHistory ? 'animate-spin text-zinc-700' : ''}`} />
            </button>
            <button
              onClick={onClose}
              className="p-1.5 text-zinc-400 hover:text-zinc-700 rounded-lg hover:bg-zinc-100 transition-colors"
            >
              <X className="w-4 h-4" />
            </button>
          </div>
        </div>

        {/* Filter bar & Actions */}
        <div className="px-6 py-3 border-b border-zinc-100 flex flex-wrap items-center justify-between gap-3 bg-white text-xs">
          <div className="flex items-center flex-wrap gap-1.5">
            <Filter className="w-3.5 h-3.5 text-zinc-400 mr-0.5" />
            {(
              [
                ['ACTIVE', 'Active', 'bg-blue-700'],
                ['SUCCESS', 'Successful', 'bg-emerald-700'],
                ['FAILED', 'Failed / DLQ', 'bg-rose-700'],
                ['SKIPPED', 'Skipped', 'bg-zinc-600'],
                ['CANCELLED', 'Cancelled', 'bg-zinc-500'],
                ['ALL', 'All Runs', 'bg-zinc-900'],
              ] as const
            ).map(([key, label, activeClass]) => (
              <button
                key={key}
                onClick={() => setFilter(key)}
                className={`px-2.5 py-1 rounded-lg text-xs font-medium transition-colors ${
                  filter === key
                    ? `${activeClass} text-white`
                    : 'bg-zinc-100 text-zinc-600 hover:bg-zinc-200/70'
                }`}
              >
                {label} ({countRunsByFilter(pairJobs, key)})
              </button>
            ))}
          </div>

          <button
            onClick={() => setFullSyncChoiceOpen(true)}
            className="inline-flex items-center space-x-1.5 px-3 py-1.5 bg-zinc-900 hover:bg-zinc-800 text-white rounded-lg text-xs font-medium shadow-xs transition-colors"
          >
            <Play className="w-3 h-3 fill-current" />
            <span>Trigger Full Sync</span>
          </button>
        </div>

        {/* Job List Container */}
        <div className="p-6 overflow-y-auto space-y-3 flex-1 bg-zinc-50/40">
          {loadingHistory && pairJobs.length === 0 ? (
            <div className="py-16 text-center space-y-3 flex flex-col items-center justify-center">
              <div className="w-6 h-6 border-2 border-zinc-700 border-t-zinc-400 rounded-full animate-spin" />
              <p className="text-xs font-medium text-zinc-500">Loading synchronization execution history...</p>
            </div>
          ) : filteredJobs.length === 0 ? (
            <div className="py-16 text-center space-y-2">
              <Clock className="w-8 h-8 text-zinc-300 mx-auto" />
              <p className="text-xs font-medium text-zinc-500">{emptyStateForFilter(filter)}</p>
            </div>
          ) : (
            filteredJobs.map((job) => {
              const isExpanded = expandedJobId === job.id;
              const isSuccess = job.status === 'SUCCESS';
              const isFailed = job.status === 'FAILED' || job.status === 'DEAD_LETTERED' || job.status === 'CONFLICT_ISOLATED';
              const isInterrupted = job.status === 'INTERRUPTED' || job.status === 'PAUSED';
              const isSkipped = job.status === 'SKIPPED';
              const isCancelled = job.status === 'CANCELLED';
              const isInProgress = isLiveJobStatus(job.status);
              const progress = progressByJobId[job.id];
              const elapsedMs = resolveJobElapsedMs(job, now, progress);
              const pipeline = pipelineFromJobAndProgress(job, progress);
              const pushStage = pipeline?.stages.find((s) => s.id === 'push_dest');
              const logs = jobLogs[job.id] || [];
              const rawDisplayLogs = isInProgress
                ? overlayLiveProgress(logs, progress)
                : finalizeStaleProgressLines(logs);
              const displayLogs = prepareAuditDisplayLogs(rawDisplayLogs);
              const isLoadingThisLog = loadingLogs[job.id];

              return (
                <div
                  key={job.id}
                  className={`rounded-xl border transition-all ${
                    isFailed || isInterrupted
                      ? 'border-rose-200 bg-white shadow-xs'
                      : isSuccess
                      ? 'border-zinc-200/90 bg-white shadow-xs'
                      : isSkipped || isCancelled
                      ? 'border-zinc-200 bg-zinc-50/50 shadow-xs'
                      : 'border-blue-200 bg-blue-50/30'
                  }`}
                >
                  {/* Job Header Summary Row */}
                  <div
                    onClick={() => toggleExpand(job.id)}
                    className="p-4 cursor-pointer flex flex-col md:flex-row md:items-center justify-between gap-3 hover:bg-zinc-50/60 transition-colors rounded-xl"
                  >
                    <div className="flex items-start space-x-3">
                      <div
                        className={`w-7 h-7 rounded-lg flex items-center justify-center shrink-0 mt-0.5 ${
                          isSuccess
                            ? 'bg-emerald-50 text-emerald-600 border border-emerald-200'
                            : isFailed
                            ? 'bg-rose-50 text-rose-600 border border-rose-200'
                            : isSkipped || isCancelled
                            ? 'bg-zinc-100 text-zinc-500 border border-zinc-200'
                            : 'bg-blue-50 text-blue-600 border border-blue-200 animate-pulse'
                        }`}
                      >
                        {isSuccess && <CheckCircle2 className="w-4 h-4" />}
                        {isFailed && <AlertCircle className="w-4 h-4" />}
                        {isSkipped && <Check className="w-3.5 h-3.5" />}
                        {isCancelled && <X className="w-3.5 h-3.5" />}
                        {isInProgress && <RefreshCw className="w-3.5 h-3.5 animate-spin" />}
                      </div>

                      <div className="space-y-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="font-bold text-zinc-900 text-xs font-mono">
                            Job #{job.id}
                          </span>
                          <span
                            className={`px-2 py-0.5 rounded-full text-[10px] font-semibold border ${
                              isSuccess
                                ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                                : isFailed
                                ? 'bg-rose-50 text-rose-700 border-rose-200'
                                : 'bg-blue-50 text-blue-700 border-blue-200'
                            }`}
                          >
                            {job.status}
                          </span>
                          <span className="px-1.5 py-0.5 rounded text-[10px] font-mono bg-zinc-100 text-zinc-600">
                            {job.triggerType}
                          </span>
                          {isInProgress && (onPauseJob || onCancelJob) && (
                            <>
                              {onPauseJob && (
                                <button
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    void onPauseJob(job.id);
                                  }}
                                  className="px-2 py-0.5 rounded-lg bg-amber-600 hover:bg-amber-700 text-white text-[10px] font-semibold"
                                  title="Pause — checkpoint preserved"
                                >
                                  Pause
                                </button>
                              )}
                              {onCancelJob && (
                                <button
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    void onCancelJob(job.id);
                                  }}
                                  className="px-2 py-0.5 rounded-lg bg-rose-700 hover:bg-rose-800 text-white text-[10px] font-semibold"
                                  title="Cancel — clears resume checkpoint"
                                >
                                  Cancel
                                </button>
                              )}
                            </>
                          )}
                          <span className="text-[11px] text-zinc-600 font-mono flex items-center space-x-1 bg-zinc-100/80 px-1.5 py-0.5 rounded border border-zinc-200/60">
                            <GitBranch className="w-3 h-3 text-zinc-500" />
                            <span>
                              {job.branchesCount != null
                                ? `${job.branchesCount} branch${job.branchesCount === 1 ? '' : 'es'}`
                                : job.branch || 'all branches'}
                            </span>
                          </span>

                          {job.tagsCount != null && job.tagsCount > 0 && (
                            <span className="inline-flex items-center space-x-1 px-1.5 py-0.5 rounded text-[10px] font-mono bg-blue-50 text-blue-700 border border-blue-200">
                              <Tag className="w-2.5 h-2.5" />
                              <span>{job.tagsCount} tag{job.tagsCount === 1 ? '' : 's'}</span>
                            </span>
                          )}

                          {job.lfsObjectsCount != null && job.lfsObjectsCount > 0 && (
                            <span className="inline-flex items-center space-x-1 px-1.5 py-0.5 rounded text-[10px] font-mono bg-purple-50 text-purple-700 border border-purple-200">
                              <Database className="w-2.5 h-2.5" />
                              <span>LFS: {job.lfsObjectsCount}</span>
                            </span>
                          )}

                          {job.bytesTransferred != null && job.bytesTransferred > 0 && (
                            <span className="inline-flex items-center space-x-1 px-1.5 py-0.5 rounded text-[10px] font-mono bg-zinc-50 text-zinc-700 border border-zinc-200">
                              <Layers className="w-2.5 h-2.5" />
                              <span>{formatBytes(job.bytesTransferred)}</span>
                            </span>
                          )}

                          {job.objectsReceived != null && job.objectsReceived > 0 && (
                            <span className="inline-flex items-center space-x-1 px-1.5 py-0.5 rounded text-[10px] font-mono bg-zinc-50 text-zinc-600 border border-zinc-200">
                              <span>{job.objectsReceived.toLocaleString()} objects</span>
                            </span>
                          )}

                          {job.prsSyncedCount != null && job.prsSyncedCount > 0 && (
                            <span className="inline-flex items-center space-x-1 px-1.5 py-0.5 rounded text-[10px] font-mono bg-amber-50 text-amber-700 border border-amber-200">
                              <GitPullRequest className="w-2.5 h-2.5" />
                              <span>PRs: {job.prsSyncedCount}</span>
                            </span>
                          )}

                          {job.releasesCount != null && job.releasesCount > 0 && (
                            <span className="inline-flex items-center space-x-1 px-1.5 py-0.5 rounded text-[10px] font-mono bg-emerald-50 text-emerald-700 border border-emerald-200">
                              <Sparkles className="w-2.5 h-2.5" />
                              <span>Releases: {job.releasesCount}</span>
                            </span>
                          )}
                        </div>

                        {/* Summary Line */}
                        {isSuccess && (
                          <p className="text-[11px] text-zinc-600">
                            {job.summaryMessage || 'All repository branches and tags synchronized cleanly with zero divergence.'}
                          </p>
                        )}

                        {isFailed && (
                          <p className="text-[11px] font-mono text-rose-700 font-medium">
                            Error: {job.errorMessage || 'Execution encountered an error.'}
                          </p>
                        )}

                        {isSkipped && (
                          <p className="text-[11px] text-zinc-600">
                            {job.skipReason || job.summaryMessage || 'Webhook echo or dedup — no sync executed.'}
                          </p>
                        )}

                        {isInProgress && (
                          <JobProgressBar
                            progress={progress}
                            compact
                            elapsedMs={elapsedMs}
                          />
                        )}
                      </div>
                    </div>

                    <div className="flex items-center space-x-3 shrink-0 self-end md:self-center">
                      <div className="text-right text-[11px] space-y-0.5">
                        <div className="text-zinc-700 font-mono font-medium">
                          {formatDateTime(job.completedAt || job.createdAt)}
                        </div>
                        {elapsedMs != null && isInProgress ? (
                          <div className="text-blue-600 font-mono text-[10px]">
                            {formatDuration(elapsedMs)}
                          </div>
                        ) : elapsedMs != null ? (
                          <div className="text-zinc-500 font-mono text-[10px]">
                            {formatDuration(elapsedMs)}
                          </div>
                        ) : null}
                      </div>

                      <div className="p-1 text-zinc-400">
                        {isExpanded ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
                      </div>
                    </div>
                  </div>

                  {/* Expanded Diagnostics & Logs */}
                  {isExpanded && (
                    <div className="border-t border-zinc-100 p-4 sm:p-5 bg-zinc-50/70 space-y-4 rounded-b-xl">
                      {isInProgress && (progress?.message || pushStage?.detail) && (
                        <div className="px-3.5 py-2.5 rounded-xl bg-blue-50 border border-blue-200 text-xs text-blue-900 font-mono truncate">
                          <span className="font-semibold text-blue-800">Live: </span>
                          {progress?.message || pushStage?.detail}
                        </div>
                      )}
                      <div className="grid grid-cols-1 lg:grid-cols-12 gap-4 items-start">
                        {/* Left Column: Pipeline, Traffic, Rejections, Artifacts */}
                        <div className="lg:col-span-5 space-y-3.5">
                          <SyncPipelineStepper pipeline={pipeline} />
                          <ProviderTrafficStrip
                            traffic={trafficFromJobAndProgress(job, progress?.providerTraffic)}
                            pushBatchDetail={pushStage?.detail}
                          />

                          {parseRejectedRefs(job.rejectedPushRefs).length > 0 && (
                            <div className="p-3.5 bg-rose-50 border border-rose-200 rounded-xl space-y-2">
                              <div className="flex items-center space-x-1.5 text-xs font-bold text-rose-950">
                                <AlertTriangle className="w-4 h-4 text-rose-600" />
                                <span>Rejected on Destination</span>
                              </div>
                              <ul className="text-[11px] font-mono text-rose-800 space-y-1 max-h-28 overflow-y-auto bg-white/70 p-2 rounded-lg border border-rose-100">
                                {parseRejectedRefs(job.rejectedPushRefs).map((line) => (
                                  <li key={line} className="break-all">{line}</li>
                                ))}
                              </ul>
                              {isFailed && (
                                <button
                                  onClick={() => handleRetry(job.branch, job.id)}
                                  className="w-full inline-flex items-center justify-center space-x-1.5 px-3 py-1.5 bg-rose-700 hover:bg-rose-800 text-white rounded-lg text-xs font-semibold transition-colors"
                                >
                                  <RotateCcw className="w-3.5 h-3.5" />
                                  <span>Retry Rejected Refs</span>
                                </button>
                              )}
                            </div>
                          )}

                          <JobExecutionSummary job={job} pipeline={pipeline} elapsedMs={elapsedMs} />

                          {/* Failure / interrupted root cause */}
                          {(isFailed || isInterrupted) && (
                            <div className={`p-3.5 border rounded-xl space-y-2 ${isInterrupted ? 'bg-amber-50 border-amber-200' : 'bg-rose-50 border-rose-200'}`}>
                              <div className={`font-bold flex items-center space-x-1.5 text-xs ${isInterrupted ? 'text-amber-950' : 'text-rose-950'}`}>
                                <AlertTriangle className={`w-4 h-4 shrink-0 ${isInterrupted ? 'text-amber-600' : 'text-rose-600'}`} />
                                <span>{isInterrupted ? 'Interrupted — checkpoint available' : 'Failure Root Cause'}</span>
                              </div>
                              <p className={`font-mono text-[11px] break-all bg-white/80 p-2 rounded-lg border ${isInterrupted ? 'text-amber-800 border-amber-100' : 'text-rose-800 border-rose-100'}`}>
                                {job.errorMessage || (isInterrupted ? 'Server restart interrupted this run' : 'Unspecified runtime exception')}
                              </p>
                              <div className="flex items-center justify-between text-[11px] pt-1">
                                <span className={`font-mono text-[10px] ${isInterrupted ? 'text-amber-700' : 'text-rose-700'}`}>
                                  {isInterrupted ? 'Resume continues from the last checkpoint' : `Attempts: ${job.attemptCount}/${job.maxAttempts}`}
                                </span>
                                {isInterrupted ? (
                                  <button
                                    onClick={() => handleResume(job.id)}
                                    disabled={resumingJobId === job.id}
                                    className="inline-flex items-center space-x-1 px-2.5 py-1 bg-amber-700 hover:bg-amber-800 text-white rounded-lg text-xs font-semibold shadow-xs disabled:opacity-50"
                                  >
                                    <Play className={`w-3 h-3 ${resumingJobId === job.id ? 'animate-pulse' : ''}`} />
                                    <span>{resumingJobId === job.id ? 'Resuming...' : 'Resume Run'}</span>
                                  </button>
                                ) : (
                                  <button
                                    onClick={() => handleRetry(job.branch, job.id)}
                                    disabled={retryingJobId === job.id}
                                    className="inline-flex items-center space-x-1 px-2.5 py-1 bg-rose-700 hover:bg-rose-800 text-white rounded-lg text-xs font-semibold shadow-xs disabled:opacity-50"
                                  >
                                    <RotateCcw className={`w-3 h-3 ${retryingJobId === job.id ? 'animate-spin' : ''}`} />
                                    <span>{retryingJobId === job.id ? 'Retrying...' : 'Retry Run'}</span>
                                  </button>
                                )}
                              </div>
                            </div>
                          )}
                        </div>

                        {/* Right Column: Execution Audit Logs Stream */}
                        <div className="lg:col-span-7 flex flex-col bg-zinc-950 rounded-xl overflow-hidden border border-zinc-800 shadow-inner">
                          <div className="px-3.5 py-2 bg-zinc-900 border-b border-zinc-800 flex items-center justify-between text-xs">
                            <span className="flex items-center space-x-1.5 font-mono text-zinc-300 text-[11px] font-semibold">
                              <Terminal className="w-3.5 h-3.5 text-zinc-400" />
                              <span>Audit Log Stream</span>
                            </span>
                            <span className="text-[10px] text-zinc-400 font-mono">
                              {isLoadingThisLog ? 'Loading...' : `${displayLogs.length} entries`}
                            </span>
                          </div>

                          {isLoadingThisLog ? (
                            <div className="py-12 text-center text-xs text-zinc-400 font-mono">
                              Loading audit log stream...
                            </div>
                          ) : displayLogs.length === 0 ? (
                            <div className="py-12 text-center text-xs text-zinc-500 font-mono">
                              No log details recorded for this execution run.
                            </div>
                          ) : (
                            <div className="p-3.5 font-mono text-[11px] leading-relaxed max-h-80 overflow-y-auto space-y-1.5 text-zinc-300 selection:bg-zinc-800">
                              {displayLogs.map((log) => (
                                <div key={log.id} className="flex items-start space-x-2 group hover:bg-zinc-900/60 -mx-1.5 px-1.5 py-0.5 rounded">
                                  <span className="text-zinc-600 text-[10px] select-none shrink-0 tabular-nums mt-0.5">
                                    {new Date(log.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                                  </span>
                                  <span
                                    className={`text-[10px] font-semibold px-1 py-0.2 rounded shrink-0 ${
                                      log.level === 'ERROR'
                                        ? 'bg-rose-950 text-rose-400 border border-rose-900/50'
                                        : log.level === 'WARN'
                                        ? 'bg-amber-950 text-amber-400 border border-amber-900/50'
                                        : log.level === 'DEBUG'
                                        ? 'bg-zinc-900 text-zinc-500 border border-zinc-800'
                                        : 'bg-zinc-900 text-zinc-400 border border-zinc-800'
                                    }`}
                                  >
                                    {log.level}
                                  </span>
                                  <span className={`break-all ${log.level === 'ERROR' ? 'text-rose-300' : log.level === 'WARN' ? 'text-amber-200' : 'text-zinc-300'}`}>
                                    {log.message}
                                  </span>
                                </div>
                              ))}
                            </div>
                          )}
                        </div>
                      </div>
                    </div>
                  )}
                </div>
              );
            })
          )}
        </div>

        {/* Footer */}
        {fullSyncChoiceOpen && (
          <div className="absolute inset-0 z-10 flex items-center justify-center bg-black/30 p-6">
            <div className="bg-white rounded-xl border border-zinc-200 shadow-lg max-w-md w-full p-5 space-y-3">
              <h4 className="text-sm font-semibold text-zinc-900">Full mirror sync</h4>
              <p className="text-xs text-zinc-600">
                {hasCheckpoint
                  ? `Checkpoint saved (${syncCheckpointStage || 'in progress'}). Resume or start fresh?`
                  : 'Smart sync tip-checks the source; start fresh forces a full re-fetch.'}
              </p>
              <button
                onClick={() => void handleRetry('*', undefined, false)}
                className="w-full px-3 py-2 rounded-lg bg-zinc-900 text-white text-xs font-medium text-left"
              >
                <span className="block">{hasCheckpoint ? 'Resume interrupted run' : 'Smart sync'}</span>
                <span className="block text-[10px] font-normal text-zinc-300 mt-0.5">
                  {hasCheckpoint
                    ? 'Continue from checkpoint'
                    : 'Fetch only when source tips moved'}
                </span>
              </button>
              <button
                onClick={() => void handleRetry('*', undefined, true)}
                className="w-full px-3 py-2 rounded-lg border border-zinc-200 text-zinc-800 text-xs font-medium text-left"
              >
                <span className="block">Start fresh (force re-fetch)</span>
                <span className="block text-[10px] font-normal text-zinc-500 mt-0.5">
                  Always download from source
                </span>
              </button>
              <button onClick={() => setFullSyncChoiceOpen(false)} className="w-full text-xs text-zinc-500 py-1">
                Cancel
              </button>
            </div>
          </div>
        )}
        <div className="px-6 py-3 border-t border-zinc-100 bg-zinc-50 flex items-center justify-between text-xs">
          <span className="text-zinc-500 font-mono text-[11px]">
            Showing {filteredJobs.length} of {pairJobs.length} execution runs
          </span>
          <button
            onClick={onClose}
            className="px-3.5 py-1.5 bg-white hover:bg-zinc-100 border border-zinc-200 text-zinc-700 rounded-lg font-medium shadow-xs transition-colors"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};
