import React, { useEffect, useRef, useState } from 'react';
import {
  X,
  AlertTriangle,
  Maximize2,
  Minimize2,
  Copy,
  Check,
  Terminal,
  RotateCcw,
  Activity,
  Layers,
  Clock,
  Database,
  ArrowRight,
  ShieldAlert,
  GitBranch
} from 'lucide-react';
import { SyncJob, SyncAuditLog, JobProgress } from '../types';
import { getJob, getJobLogs, skipJobStage } from '../services/api';
import { formatBytes, formatDuration } from '../utils/format';
import { overlayLiveProgress, finalizeStaleProgressLines, formatEta, parseRejectedRefs, prepareAuditDisplayLogs } from '../utils/liveJobLogs';
import { ProviderTrafficStrip, trafficFromJobAndProgress } from './ProviderTrafficStrip';
import {
  SyncPipelineStepper,
  pipelineFromJobAndProgress,
  pipelineStageLabel,
  canSkipJobStages,
} from './SyncPipelineStepper';
import { resolveJobElapsedMs, isLiveJobStatus } from '../utils/jobTiming';

interface JobLogModalProps {
  job: SyncJob | null;
  progress?: JobProgress | null;
  onClose: () => void;
  onRetry: (id: number) => void;
  onCancel?: (id: number) => void | Promise<void>;
  onPause?: (id: number) => void | Promise<void>;
  onJobUpdated?: (job: SyncJob) => void;
}

export const JobLogModal: React.FC<JobLogModalProps> = ({
  job,
  progress,
  onClose,
  onRetry,
  onCancel,
  onPause,
  onJobUpdated,
}) => {
  const [logs, setLogs] = useState<SyncAuditLog[]>([]);
  const [loading, setLoading] = useState(false);
  const [now, setNow] = useState(Date.now());
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [autoScroll, setAutoScroll] = useState(true);
  const [copied, setCopied] = useState(false);
  const [localJob, setLocalJob] = useState<SyncJob | null>(job);
  const [skippingStageId, setSkippingStageId] = useState<string | null>(null);
  const consoleRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    setLocalJob(job);
  }, [job]);

  const onJobUpdatedRef = useRef(onJobUpdated);
  onJobUpdatedRef.current = onJobUpdated;

  useEffect(() => {
    if (!localJob) {
      setLogs([]);
      return;
    }
    let cancelled = false;
    const inProgress = isLiveJobStatus(localJob.status);
    const load = (showSpinner: boolean) => {
      if (showSpinner) setLoading(true);
      const jobId = localJob.id;
      Promise.all([
        getJobLogs(jobId),
        inProgress ? getJob(jobId).catch(() => null) : Promise.resolve(null),
      ])
        .then(([nextLogs, latestJob]) => {
          if (cancelled) return;
          setLogs(nextLogs);
          if (!latestJob || latestJob.id !== jobId) return;
          const changed = latestJob.status !== localJob.status
            || latestJob.completedAt !== localJob.completedAt
            || latestJob.summaryMessage !== localJob.summaryMessage;
          if (!changed) return;
          setLocalJob(latestJob);
          onJobUpdatedRef.current?.(latestJob);
        })
        .catch(console.error)
        .finally(() => {
          if (!cancelled && showSpinner) setLoading(false);
        });
    };
    load(true);
    const timer = inProgress ? window.setInterval(() => load(false), 1000) : undefined;
    return () => {
      cancelled = true;
      if (timer) window.clearInterval(timer);
    };
  }, [localJob?.id, localJob?.status, localJob?.completedAt, localJob?.summaryMessage]);

  const running = isLiveJobStatus(localJob?.status);
  const liveProgress = running ? progress : undefined;
  useEffect(() => {
    if (!running) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [running, localJob?.id]);

  useEffect(() => {
    if (!autoScroll) return;
    const el = consoleRef.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
  }, [logs, liveProgress?.message, liveProgress?.percent, autoScroll]);

  if (!localJob) return null;

  const handleSkipStage = async (stageId: string) => {
    if (!window.confirm(
      `Skip "${pipelineStageLabel(stageId, pipeline)}" for job #${localJob.id}? `
      + 'The job will resume from the next step when dispatched.'
    )) {
      return;
    }
    setSkippingStageId(stageId);
    try {
      const updated = await skipJobStage(localJob.id, stageId);
      setLocalJob(updated);
      onJobUpdated?.(updated);
      const nextLogs = await getJobLogs(localJob.id);
      setLogs(nextLogs);
    } catch (e) {
      console.error(e);
      window.alert(e instanceof Error ? e.message : 'Failed to skip stage');
    } finally {
      setSkippingStageId(null);
    }
  };

  const elapsedMs = resolveJobElapsedMs(localJob, now, liveProgress);
  const etaSuffix = formatEta(liveProgress?.etaMs);
  const rawDisplayLogs = running
    ? overlayLiveProgress(logs, liveProgress, etaSuffix || undefined)
    : finalizeStaleProgressLines(logs);
  const displayLogs = prepareAuditDisplayLogs(rawDisplayLogs);
  const pipeline = pipelineFromJobAndProgress(localJob, liveProgress);
  const pushStage = pipeline?.stages.find((s) => s.id === 'push_dest');
  const traffic = trafficFromJobAndProgress(localJob, liveProgress?.providerTraffic);
  const rejected = parseRejectedRefs(localJob.rejectedPushRefs);
  const showRetry = localJob.status === 'FAILED' || localJob.status === 'DEAD_LETTERED';
  const showCancel = !!onCancel && (localJob.status === 'QUEUED' || localJob.status === 'IN_PROGRESS');
  const showPause = !!onPause && (localJob.status === 'QUEUED' || localJob.status === 'IN_PROGRESS');
  const allowSkip = canSkipJobStages(localJob.status);

  const knownTotal = !!liveProgress && liveProgress.total > 0;
  const percent = knownTotal
    ? Math.min(100, liveProgress.percent ?? Math.round((100 * liveProgress.current) / liveProgress.total))
    : 0;

  const handleCopyLogs = () => {
    const text = displayLogs
      .map((l) => `[${l.timestamp}] [${l.level}] ${l.message}`)
      .join('\n');
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-center justify-center p-2 sm:p-4 md:p-6 transition-all duration-200">
      <div
        className={`bg-white border border-zinc-200 shadow-2xl overflow-hidden flex flex-col transition-all duration-200 ${
          isFullscreen
            ? 'w-full h-full rounded-none fixed inset-0 z-50'
            : 'w-full max-w-6xl h-[90vh] max-h-[920px] rounded-2xl'
        }`}
      >
        {/* Top Header */}
        <div className="px-6 py-3.5 border-b border-zinc-100 flex items-center justify-between bg-white shrink-0">
          <div className="flex items-center space-x-3 min-w-0">
            <div className="w-9 h-9 rounded-xl bg-zinc-900 text-white flex items-center justify-center shrink-0 shadow-xs">
              <Terminal className="w-4 h-4 text-zinc-200" />
            </div>
            <div className="min-w-0">
              <div className="flex items-center space-x-2">
                <h3 className="text-sm font-bold text-zinc-900 truncate">
                  Sync Execution Log #{localJob.id}
                </h3>
                <span
                  className={`px-2 py-0.5 rounded-full text-[10px] font-semibold tracking-wide uppercase ${
                    localJob.status === 'SUCCESS'
                      ? 'bg-emerald-50 text-emerald-700 border border-emerald-200'
                      : localJob.status === 'IN_PROGRESS' || localJob.status === 'QUEUED'
                      ? 'bg-blue-50 text-blue-700 border border-blue-200 animate-pulse'
                      : localJob.status === 'FAILED' || localJob.status === 'DEAD_LETTERED'
                      ? 'bg-rose-50 text-rose-700 border border-rose-200'
                      : localJob.status === 'CANCELLED'
                      ? 'bg-zinc-100 text-zinc-600 border border-zinc-200'
                      : 'bg-zinc-100 text-zinc-700'
                  }`}
                >
                  {localJob.status}
                </span>
              </div>
              <p className="text-xs text-zinc-500 font-mono truncate mt-0.5 flex items-center space-x-1.5">
                <span className="font-semibold text-zinc-700">{localJob.pairName}</span>
                <span>•</span>
                <span>{localJob.branch || 'all branches'}</span>
                {localJob.commitSha && (
                  <>
                    <span>•</span>
                    <span className="bg-zinc-100 px-1 py-0.2 rounded text-[11px]">
                      {localJob.commitSha.substring(0, 8)}
                    </span>
                  </>
                )}
              </p>
            </div>
          </div>

          <div className="flex items-center space-x-1.5 shrink-0">
            <button
              onClick={() => setIsFullscreen(!isFullscreen)}
              className="p-1.5 text-zinc-400 hover:text-zinc-700 rounded-lg hover:bg-zinc-100 transition-colors"
              title={isFullscreen ? 'Exit Fullscreen' : 'Fullscreen View'}
            >
              {isFullscreen ? <Minimize2 className="w-4 h-4" /> : <Maximize2 className="w-4 h-4" />}
            </button>
            <button
              onClick={onClose}
              className="p-1.5 text-zinc-400 hover:text-zinc-700 rounded-lg hover:bg-zinc-100 transition-colors"
            >
              <X className="w-4 h-4" />
            </button>
          </div>
        </div>

        {/* Prominent In-Progress / Status Banner */}
        {running ? (
          <div className="px-6 py-3 bg-gradient-to-r from-blue-50/90 via-indigo-50/80 to-blue-50/90 border-b border-blue-100 shrink-0">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
              <div className="flex items-center space-x-2.5 min-w-0">
                <div className="w-2.5 h-2.5 rounded-full bg-blue-600 animate-ping shrink-0" />
                <div className="min-w-0">
                  <div className="text-xs font-semibold text-blue-900 truncate">
                    {liveProgress?.message || 'Synchronizing repository contents...'}
                  </div>
                  <div className="text-[11px] text-blue-700/80 font-mono flex items-center space-x-2 mt-0.5">
                    {liveProgress?.phase && <span className="capitalize">{liveProgress.phase}</span>}
                    {knownTotal && (
                      <span>
                        • {liveProgress.current.toLocaleString()} of {liveProgress.total.toLocaleString()} objects ({percent}%)
                      </span>
                    )}
                    {elapsedMs != null && (
                      <span>• Elapsed: {formatDuration(elapsedMs)}</span>
                    )}
                    {etaSuffix && (
                      <span className="font-semibold text-indigo-700">• {etaSuffix}</span>
                    )}
                  </div>
                </div>
              </div>

              <div className="w-full sm:w-64 shrink-0">
                <div className="w-full rounded-full bg-blue-200/70 h-2.5 overflow-hidden">
                  {knownTotal ? (
                    <div
                      className="h-full rounded-full bg-blue-600 transition-all duration-300"
                      style={{ width: `${percent}%` }}
                    />
                  ) : (
                    <div className="h-full w-1/2 rounded-full bg-blue-600 animate-pulse" />
                  )}
                </div>
              </div>
            </div>
          </div>
        ) : localJob.errorMessage ? (
          <div className="px-6 py-2.5 bg-rose-50 border-b border-rose-100 flex items-center justify-between text-xs text-rose-900 shrink-0">
            <div className="flex items-center space-x-2 min-w-0">
              <ShieldAlert className="w-4 h-4 text-rose-600 shrink-0" />
              <span className="font-medium truncate">
                <span className="font-bold">Execution Failed:</span> {localJob.errorMessage}
              </span>
            </div>
            {elapsedMs != null && (
              <span className="font-mono text-rose-700 text-[11px] shrink-0 ml-4">
                Duration: {formatDuration(elapsedMs)}
              </span>
            )}
          </div>
        ) : (
          <div className="px-6 py-2 bg-zinc-50 border-b border-zinc-100 flex items-center justify-between text-xs text-zinc-600 shrink-0 font-mono text-[11px]">
            <div className="flex items-center space-x-4">
              {localJob.bytesTransferred != null && localJob.bytesTransferred > 0 && (
                <span>Transferred: {formatBytes(localJob.bytesTransferred)}</span>
              )}
              {localJob.objectsReceived != null && localJob.objectsReceived > 0 && (
                <span>Objects: {localJob.objectsReceived.toLocaleString()}</span>
              )}
              {localJob.prsSyncedCount != null && localJob.prsSyncedCount > 0 && (
                <span>PRs: {localJob.prsSyncedCount}</span>
              )}
            </div>
            {elapsedMs != null && (
              <span>Total Duration: {formatDuration(elapsedMs)}</span>
            )}
          </div>
        )}

        {/* Main 2-Column Workspace Body */}
        <div className="flex-1 min-h-0 flex flex-col md:flex-row overflow-hidden">
          {/* Left Sidebar: Pipeline, Traffic, and Diagnostics */}
          <div className="w-full md:w-80 lg:w-96 shrink-0 bg-zinc-50/80 border-b md:border-b-0 md:border-r border-zinc-200 flex flex-col overflow-y-auto p-4 space-y-3.5">
            {localJob.resumeStageId && (
              <div className="p-3 bg-amber-50 border border-amber-200 rounded-xl text-[11px] text-amber-950 shadow-2xs">
                <div className="font-semibold text-amber-900">Resume cursor</div>
                <div className="mt-1 font-mono text-[10px] text-amber-800">
                  {pipelineStageLabel(localJob.resumeStageId, pipeline)}
                  <span className="text-amber-600"> ({localJob.resumeStageId})</span>
                </div>
                {allowSkip ? (
                  <p className="mt-1.5 text-[10px] text-amber-700 leading-relaxed">
                    Use <strong>Skip</strong> on a step to mark it done and move the cursor forward before you resume.
                  </p>
                ) : running ? (
                  <p className="mt-1.5 text-[10px] text-amber-700 leading-relaxed">
                    Pause the job first if you need to skip a step manually.
                  </p>
                ) : null}
              </div>
            )}

            {/* Sync Pipeline Stepper */}
            <SyncPipelineStepper
              pipeline={pipeline}
              allowSkip={allowSkip}
              skippingStageId={skippingStageId}
              onSkipStage={handleSkipStage}
            />

            {/* Provider API Traffic Meter */}
            <ProviderTrafficStrip traffic={traffic} pushBatchDetail={pushStage?.detail} />

            {/* Destination Rejected References Alert */}
            {rejected.length > 0 && (
              <div className="p-3.5 bg-rose-50 border border-rose-200 rounded-xl space-y-2.5 shadow-xs">
                <div className="flex items-center space-x-1.5 text-xs font-bold text-rose-950">
                  <AlertTriangle className="w-4 h-4 text-rose-600 shrink-0" />
                  <span>Destination Push Rejections</span>
                </div>
                <div className="text-[11px] text-rose-800 leading-relaxed font-sans">
                  The destination remote rejected one or more branch/tag updates:
                </div>
                <ul className="text-[11px] font-mono text-rose-900 space-y-1 max-h-32 overflow-y-auto bg-white/70 p-2 rounded-lg border border-rose-100">
                  {rejected.map((line) => (
                    <li key={line} className="break-all">{line}</li>
                  ))}
                </ul>
                {showRetry && (
                  <button
                    onClick={() => {
                      onRetry(localJob.id);
                      onClose();
                    }}
                    className="w-full inline-flex items-center justify-center space-x-1.5 px-3 py-1.5 bg-rose-700 hover:bg-rose-800 text-white rounded-lg text-xs font-semibold shadow-xs transition-colors"
                  >
                    <RotateCcw className="w-3.5 h-3.5" />
                    <span>Retry Rejected References</span>
                  </button>
                )}
              </div>
            )}

            {/* Job Metadata Card */}
            <div className="p-3.5 bg-white border border-zinc-200 rounded-xl space-y-2 text-xs">
              <div className="font-semibold text-zinc-800 flex items-center justify-between">
                <span>Execution Summary</span>
                <span className="text-[10px] font-mono text-zinc-400">ID #{localJob.id}</span>
              </div>
              <div className="space-y-1.5 font-mono text-[11px] text-zinc-600">
                <div className="flex justify-between py-0.5 border-b border-zinc-100">
                  <span className="text-zinc-400 font-sans">Mapping</span>
                  <span className="font-semibold text-zinc-800 truncate max-w-[160px]" title={localJob.pairName}>
                    {localJob.pairName}
                  </span>
                </div>
                <div className="flex justify-between py-0.5 border-b border-zinc-100">
                  <span className="text-zinc-400 font-sans">Branch Ref</span>
                  <span className="truncate max-w-[160px]" title={localJob.branch || '*'}>
                    {localJob.branch || '*'}
                  </span>
                </div>
                <div className="flex justify-between py-0.5 border-b border-zinc-100">
                  <span className="text-zinc-400 font-sans">Trigger</span>
                  <span className="capitalize">{localJob.triggerType ? localJob.triggerType.toLowerCase() : 'manual'}</span>
                </div>
                <div className="flex justify-between py-0.5">
                  <span className="text-zinc-400 font-sans">Started At</span>
                  <span>
                    {localJob.startedAt
                      ? new Date(localJob.startedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
                      : '--'}
                  </span>
                </div>
              </div>
            </div>
          </div>

          {/* Right Main Panel: Full-Height Live Terminal Console */}
          <div className="flex-1 min-w-0 flex flex-col bg-zinc-950 overflow-hidden">
            {/* Terminal Header Bar */}
            <div className="px-4 py-2.5 bg-zinc-900 border-b border-zinc-800 flex items-center justify-between text-xs shrink-0">
              <div className="flex items-center space-x-2">
                <div className="flex space-x-1.5 mr-2">
                  <div className="w-2.5 h-2.5 rounded-full bg-rose-500/80" />
                  <div className="w-2.5 h-2.5 rounded-full bg-amber-500/80" />
                  <div className="w-2.5 h-2.5 rounded-full bg-emerald-500/80" />
                </div>
                <span className="font-mono text-zinc-300 font-medium text-[11px]">
                  Live Audit Trace ({displayLogs.length} line{displayLogs.length === 1 ? '' : 's'})
                </span>
                {running && (
                  <span className="px-1.5 py-0.2 rounded bg-blue-900/60 text-blue-300 text-[10px] font-mono animate-pulse">
                    STREAMING
                  </span>
                )}
              </div>

              <div className="flex items-center space-x-2">
                <label className="flex items-center space-x-1.5 text-[11px] text-zinc-400 cursor-pointer select-none hover:text-zinc-200">
                  <input
                    type="checkbox"
                    checked={autoScroll}
                    onChange={(e) => setAutoScroll(e.target.checked)}
                    className="rounded border-zinc-700 bg-zinc-800 text-blue-500 focus:ring-0 w-3 h-3"
                  />
                  <span>Auto-scroll</span>
                </label>
                <button
                  onClick={handleCopyLogs}
                  className="inline-flex items-center space-x-1 px-2.5 py-1 rounded bg-zinc-800 hover:bg-zinc-700 text-zinc-300 text-[11px] font-mono transition-colors"
                >
                  {copied ? <Check className="w-3 h-3 text-emerald-400" /> : <Copy className="w-3 h-3" />}
                  <span>{copied ? 'Copied' : 'Copy'}</span>
                </button>
              </div>
            </div>

            {/* Scrollable Terminal Output */}
            <div
              ref={consoleRef}
              className="p-4 md:p-5 font-mono text-[11px] text-zinc-300 overflow-y-auto flex-1 space-y-1.5 selection:bg-zinc-800 leading-relaxed"
            >
              {loading && displayLogs.length === 0 ? (
                <div className="text-zinc-500 py-12 text-center flex flex-col items-center justify-center space-y-2">
                  <div className="w-5 h-5 border-2 border-zinc-700 border-t-zinc-400 rounded-full animate-spin" />
                  <span>Loading audit trace...</span>
                </div>
              ) : displayLogs.length === 0 ? (
                <div className="text-zinc-500 py-12 text-center">
                  No audit logs recorded for this execution event.
                </div>
              ) : (
                displayLogs.map((log) => (
                  <div key={log.id} className="flex items-start space-x-2.5 group hover:bg-zinc-900/50 -mx-2 px-2 py-0.5 rounded">
                    <span className="text-zinc-600 select-none shrink-0 text-[10px] tabular-nums mt-0.5">
                      {new Date(log.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                    </span>
                    <span
                      className={`px-1.5 py-0.2 rounded text-[10px] font-semibold shrink-0 tracking-wide ${
                        log.level === 'ERROR'
                          ? 'bg-rose-950 text-rose-400 border border-rose-800/40'
                          : log.level === 'WARN'
                          ? 'bg-amber-950 text-amber-400 border border-amber-800/40'
                          : log.level === 'DEBUG'
                          ? 'bg-zinc-900 text-zinc-500 border border-zinc-800'
                          : 'bg-zinc-900 text-zinc-400 border border-zinc-800'
                      }`}
                    >
                      {log.level}
                    </span>
                    <span
                      className={`break-all ${
                        log.level === 'ERROR'
                          ? 'text-rose-300 font-semibold'
                          : log.level === 'WARN'
                          ? 'text-amber-200'
                          : 'text-zinc-300'
                      }`}
                    >
                      {log.message}
                    </span>
                  </div>
                ))
              )}
            </div>
          </div>
        </div>

        {/* Bottom Footer Bar */}
        <div className="px-6 py-3 bg-white border-t border-zinc-200 flex items-center justify-between text-xs shrink-0">
          <div className="text-zinc-500 text-[11px] truncate mr-4">
            {running
              ? 'Real-time WebSocket event streaming active'
              : localJob.summaryMessage || (localJob.errorMessage ? `Error: ${localJob.errorMessage}` : 'Sync completed successfully')}
          </div>

          <div className="flex items-center space-x-2.5 shrink-0">
            {showPause && (
              <button
                onClick={() => {
                  void onPause?.(localJob.id);
                }}
                className="inline-flex items-center space-x-1.5 px-3 py-1.5 bg-amber-600 hover:bg-amber-700 text-white rounded-lg text-xs font-semibold shadow-xs transition-colors"
                title="Stop after the current step; resume checkpoint is preserved"
              >
                <span>Pause job</span>
              </button>
            )}
            {showCancel && (
              <button
                onClick={() => {
                  void onCancel?.(localJob.id);
                }}
                className="inline-flex items-center space-x-1.5 px-3 py-1.5 bg-rose-700 hover:bg-rose-800 text-white rounded-lg text-xs font-semibold shadow-xs transition-colors"
                title="Abort and clear resume checkpoint"
              >
                <span>Cancel job</span>
              </button>
            )}
            {showRetry && (
              <button
                onClick={() => {
                  onRetry(localJob.id);
                  onClose();
                }}
                className="inline-flex items-center space-x-1.5 px-3 py-1.5 bg-zinc-900 hover:bg-zinc-800 text-white rounded-lg text-xs font-semibold shadow-xs transition-colors"
              >
                <RotateCcw className="w-3.5 h-3.5" />
                <span>Retry Execution</span>
              </button>
            )}
            <button
              onClick={onClose}
              className="px-3.5 py-1.5 bg-white hover:bg-zinc-100 border border-zinc-200 text-zinc-700 rounded-lg text-xs font-semibold transition-colors"
            >
              Close
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
