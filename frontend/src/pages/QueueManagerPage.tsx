import React, { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  AlertTriangle,
  Ban,
  ChevronLeft,
  ChevronRight,
  Clock,
  Layers,
  Pause,
  Play,
  RefreshCw,
  Trash2,
  XCircle,
} from 'lucide-react';
import { JobProgress, QueueStatus, RepoMapping, SyncJob, SyncStatus } from '../types';
import { mergeProviderTraffic } from '../components/ProviderTrafficStrip';
import {
  cancelJob,
  cancelQueuedJobs,
  dispatchJobs,
  getDashboardStats,
  getJobs,
  getMappings,
  getQueueStatus,
  pauseConsumer,
  pauseJob,
  purgeDlq,
  purgeInboundQueue,
  purgeMainQueue,
  redriveDlq,
  resumeConsumer,
  resumeJob,
} from '../services/api';
import { initWebSocket } from '../services/websocket';
import { JobLogModal } from '../components/JobLogModal';
import { JobProgressBar } from '../components/JobProgressBar';
import { isLiveSyncStatus, pipelineFromJobAndProgress, SyncPipelineStepper } from '../components/SyncPipelineStepper';
import { ConsumerRuntimePanel } from '../components/ConsumerRuntimePanel';

const HISTORY_PAGE_SIZE = 25;

const isFullLane = (job: SyncJob) => !job.ref || job.ref.trim() === '' || job.branch === '*';

const isDispatchableStatus = (status: SyncStatus) =>
  status === 'QUEUED' || status === 'FAILED' || status === 'INTERRUPTED' || status === 'DEAD_LETTERED' || status === 'PAUSED';

export const QueueManagerPage: React.FC = () => {
  const [queueStatus, setQueueStatus] = useState<QueueStatus | null>(null);
  const [mappings, setMappings] = useState<RepoMapping[]>([]);
  const [historyJobs, setHistoryJobs] = useState<SyncJob[]>([]);
  const [historyTotal, setHistoryTotal] = useState(0);
  const [historyPages, setHistoryPages] = useState(0);
  const [historyPage, setHistoryPage] = useState(0);
  const [queuedTotal, setQueuedTotal] = useState(0);
  const [cancelledCount, setCancelledCount] = useState(0);
  const [interruptedCount, setInterruptedCount] = useState(0);
  const [runningJobs, setRunningJobs] = useState<SyncJob[]>([]);
  const [progressByJobId, setProgressByJobId] = useState<Record<number, JobProgress>>({});
  const [pairFilter, setPairFilter] = useState<string>('ALL');
  const [statusFilter, setStatusFilter] = useState<string>('ALL');
  const [laneFilter, setLaneFilter] = useState<string>('ALL');
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [busy, setBusy] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [confirmPurge, setConfirmPurge] = useState(false);
  const [selectedJobForLogs, setSelectedJobForLogs] = useState<SyncJob | null>(null);

  const loadData = async () => {
    try {
      const mappingId = pairFilter === 'ALL' ? undefined : Number(pairFilter);
      const status = statusFilter === 'ALL' ? undefined : statusFilter;
      const lane = laneFilter === 'ALL' ? undefined : laneFilter;
      const [q, m, history, running, stats] = await Promise.allSettled([
        getQueueStatus(),
        getMappings(),
        getJobs(historyPage, HISTORY_PAGE_SIZE, status, mappingId, undefined, lane),
        getJobs(0, 20, 'IN_PROGRESS'),
        getDashboardStats(),
      ]);
      if (q.status === 'fulfilled') setQueueStatus(q.value);
      if (m.status === 'fulfilled') setMappings(m.value);
      if (history.status === 'fulfilled') {
        setHistoryJobs(history.value.content);
        setHistoryTotal(history.value.totalElements);
        setHistoryPages(history.value.totalPages);
      }
      if (running.status === 'fulfilled') setRunningJobs(running.value.content);
      if (stats.status === 'fulfilled') {
        setCancelledCount(stats.value.cancelledCount ?? 0);
        setQueuedTotal(stats.value.queuedCount ?? 0);
        setInterruptedCount(stats.value.interruptedCount ?? 0);
      }
    } catch (e) {
      console.error('Failed to load queue manager data:', e);
    }
  };

  useEffect(() => {
    loadData();
    const timer = setInterval(loadData, 4000);
    return () => clearInterval(timer);
  }, [historyPage, pairFilter, statusFilter, laneFilter]);

  useEffect(() => {
    const cleanup = initWebSocket((data) => {
      if (data.type === 'JOB_UPDATE' && data.job) {
        const job = data.job as SyncJob;
        setSelectedJobForLogs((current) => (current && current.id === job.id ? job : current));
        if (job.status !== 'IN_PROGRESS' && job.status !== 'QUEUED') {
          setProgressByJobId((prev) => {
            if (!(job.id in prev)) return prev;
            const next = { ...prev };
            delete next[job.id];
            return next;
          });
        }
        void loadData();
      }
      if (data.type === 'JOB_PROGRESS' && data.jobId != null) {
        const progress = data as JobProgress;
        setHistoryJobs((jobs) => {
          const job = jobs.find((j) => j.id === progress.jobId);
          if (!job || isLiveSyncStatus(job.status)) {
            setProgressByJobId((prev) => {
              const existing = prev[progress.jobId];
              const mergedTraffic = mergeProviderTraffic(
                existing?.providerTraffic,
                progress.providerTraffic
              );
              return {
                ...prev,
                [progress.jobId]: {
                  ...progress,
                  providerTraffic: mergedTraffic ?? progress.providerTraffic ?? existing?.providerTraffic,
                },
              };
            });
          }
          return jobs;
        });
      }
    });
    return () => cleanup();
  }, []);

  const queuedOnPage = useMemo(
    () => historyJobs.filter((j) => j.status === 'QUEUED'),
    [historyJobs]
  );

  const showFeedback = (message: string) => {
    setFeedback(message);
    setTimeout(() => setFeedback(null), 6000);
  };

  const runAction = async (key: string, fn: () => Promise<string>) => {
    setBusy(key);
    try {
      const message = await fn();
      showFeedback(message);
      setSelectedIds(new Set());
      setConfirmPurge(false);
      await loadData();
    } catch (e: any) {
      showFeedback(e?.response?.data?.error || e?.message || 'Action failed');
    } finally {
      setBusy(null);
    }
  };

  const toggleSelected = (id: number) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const dispatchableOnPage = historyJobs.filter((j) => isDispatchableStatus(j.status));

  const toggleSelectDispatchable = () => {
    const visibleIds = dispatchableOnPage.map((j) => j.id);
    const allSelected = visibleIds.length > 0 && visibleIds.every((id) => selectedIds.has(id));
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (allSelected) {
        visibleIds.forEach((id) => next.delete(id));
      } else {
        visibleIds.forEach((id) => next.add(id));
      }
      return next;
    });
  };

  const lanes = queueStatus?.consumers;
  const fullCount = lanes?.find((l) => l.lane === 'FULL')?.readyCount ?? queueStatus?.mainQueueMessageCount ?? 0;
  const incrementalCount = lanes?.find((l) => l.lane === 'INCREMENTAL')?.readyCount ?? queueStatus?.incrementalQueueMessageCount ?? 0;
  const inboundCount = lanes?.find((l) => l.lane === 'INBOUND')?.readyCount ?? queueStatus?.inboundQueueMessageCount ?? 0;
  const amqpWaiting = fullCount + incrementalCount;
  const fullUnacked = lanes?.find((l) => l.lane === 'FULL')?.unackedCount ?? queueStatus?.mainQueueUnackedCount ?? 0;
  const incrementalUnacked = lanes?.find((l) => l.lane === 'INCREMENTAL')?.unackedCount ?? queueStatus?.incrementalQueueUnackedCount ?? 0;
  const dlqCount = queueStatus?.dlqMessageCount ?? 0;
  const isPaused = queueStatus?.consumerPaused || false;
  const listenerRunning = queueStatus?.consumerRunning ?? true;
  const listenerStopped = !listenerRunning;
  const orphanAmqp = amqpWaiting > 0 && queuedTotal === 0;

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-start justify-between gap-3">
        <div>
          <h2 className="text-base font-semibold text-zinc-900">Queue Manager</h2>
          <p className="text-xs text-zinc-500 mt-0.5">
            Job history is the source of truth. RabbitMQ depths are live broker health, not the job list.
            Webhook syncs and full mirrors run on separate consumers so one clone cannot block other pairs.
          </p>
        </div>
        <Link
          to="/observability"
          className="text-xs font-medium text-zinc-600 hover:text-zinc-900 px-3 py-1.5 rounded-lg hover:bg-zinc-100"
        >
          Open activity stream
        </Link>
      </div>

      {isPaused && !listenerStopped && (
        <div className="rounded-2xl border border-amber-200 bg-amber-50/80 p-4 flex items-start justify-between gap-3 text-xs">
          <div className="flex items-start space-x-2.5">
            <AlertTriangle className="w-4 h-4 text-amber-600 shrink-0 mt-0.5" />
            <div>
              <strong className="text-amber-900">Sync consumers are paused.</strong>
              <p className="text-amber-700 text-[11px] mt-0.5">
                {interruptedCount > 0
                  ? `${interruptedCount} job${interruptedCount === 1 ? ' was' : 's were'} interrupted by a server restart. `
                  : ''}
                Select jobs below and dispatch them, then resume consumers when ready.
              </p>
            </div>
          </div>
          <button
            onClick={() =>
              runAction('consumer', async () => {
                await resumeConsumer();
                return 'Consumers resumed';
              })
            }
            disabled={busy === 'consumer'}
            className="inline-flex items-center space-x-1.5 px-3 py-1.5 rounded-lg bg-amber-700 text-white text-xs font-medium shrink-0"
          >
            <Play className="w-3 h-3" />
            <span>Resume workers</span>
          </button>
        </div>
      )}

      {listenerStopped && (
        <div className="rounded-2xl border border-rose-200 bg-rose-50/80 p-4 flex items-start justify-between gap-3 text-xs">
          <div className="flex items-start space-x-2.5">
            <AlertTriangle className="w-4 h-4 text-rose-600 shrink-0 mt-0.5" />
            <div>
              <strong className="text-rose-900">An execution worker is stopped, not paused.</strong>
              <p className="text-rose-700 text-[11px] mt-0.5">
                Resume both listeners. Cancelled jobs are skipped and ACK&apos;d on pickup so the AMQP count drains.
              </p>
            </div>
          </div>
          <button
            onClick={() =>
              runAction('consumer', async () => {
                await resumeConsumer();
                return 'Consumers restarted; cancelled leftovers will be skipped';
              })
            }
            disabled={busy === 'consumer'}
            className="inline-flex items-center space-x-1.5 px-3 py-1.5 rounded-lg bg-rose-700 text-white text-xs font-medium shrink-0"
          >
            <Play className="w-3 h-3" />
            <span>Resume workers</span>
          </button>
        </div>
      )}

      {orphanAmqp && !listenerStopped && (
        <div className="rounded-2xl border border-amber-200 bg-amber-50/80 p-4 flex items-start space-x-2.5 text-xs">
          <AlertTriangle className="w-4 h-4 text-amber-600 shrink-0 mt-0.5" />
          <div>
            <strong className="text-amber-900">
              {amqpWaiting} Ready (pending) message{amqpWaiting === 1 ? '' : 's'}; {cancelledCount || 0} job
              {cancelledCount === 1 ? ' is' : 's are'} already cancelled in history.
            </strong>
            <p className="text-amber-700 text-[11px] mt-0.5">
              Ready is waiting in RabbitMQ. Unacked is the message a consumer thread currently holds.
              Cancel updates the database immediately; leftovers ACK on pickup.
            </p>
          </div>
        </div>
      )}

      {amqpWaiting > 20 && queuedTotal > 0 && (
        <div className="rounded-2xl border border-amber-200 bg-amber-50/80 p-4 flex items-start space-x-2.5 text-xs">
          <AlertTriangle className="w-4 h-4 text-amber-600 shrink-0 mt-0.5" />
          <div>
            <strong className="text-amber-900">{queuedTotal} queued jobs · {amqpWaiting} Ready messages waiting.</strong>
            <p className="text-amber-700 text-[11px] mt-0.5">
              Full mirrors ({fullCount} ready / {fullUnacked} in-flight) and webhook syncs ({incrementalCount} ready / {incrementalUnacked} in-flight) are separate lanes.
            </p>
          </div>
        </div>
      )}

      {feedback && (
        <div className="rounded-xl border border-blue-200 bg-blue-50 p-3 text-xs text-blue-800">{feedback}</div>
      )}

      <ConsumerRuntimePanel lanes={lanes} />

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <MetricCard label="Dead letter queue" value={dlqCount} hint={queueStatus?.dlqQueueName || 'git.sync.dlq'} tone={dlqCount > 0 ? 'rose' : 'zinc'} />
        <div className="rounded-2xl border border-zinc-200/90 bg-white p-5 shadow-sm space-y-2">
          <div className="text-xs text-zinc-500 font-medium">Execution consumers</div>
          <div className="text-lg font-bold text-zinc-900">
            {listenerStopped ? 'Stopped' : isPaused ? 'Paused' : 'Active'}
          </div>
          <p className="text-[10px] text-zinc-400">
            Pause both Git execution lanes. Inbound webhook ingest stays up.
          </p>
          <button
            onClick={() =>
              runAction('consumer', async () => {
                if (isPaused || listenerStopped) {
                  await resumeConsumer();
                  return 'Consumers resumed';
                }
                await pauseConsumer();
                return 'Consumers paused; messages will buffer';
              })
            }
            disabled={busy === 'consumer'}
            className="inline-flex items-center space-x-1.5 text-[11px] font-medium px-2.5 py-1 rounded-lg border border-zinc-200 hover:bg-zinc-50"
          >
            {isPaused || listenerStopped ? <Play className="w-3 h-3" /> : <Pause className="w-3 h-3" />}
            <span>{isPaused || listenerStopped ? 'Resume' : 'Pause'}</span>
          </button>
        </div>
      </div>

      {runningJobs.length > 0 && (
        <div className="rounded-2xl border border-blue-200 bg-white p-5 shadow-sm space-y-3">
          <h3 className="text-sm font-semibold text-zinc-900">Running now</h3>
          {runningJobs.map((job) => (
            <div key={job.id} className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 rounded-xl border border-blue-100 bg-blue-50/50 p-3">
              <div className="min-w-0">
                <div className="text-xs font-semibold text-zinc-900">
                  Job #{job.id} · {job.pairName}
                  {job.workerInstanceId ? (
                    <span className="ml-2 font-mono text-[10px] text-zinc-400">@{job.workerInstanceId}</span>
                  ) : null}
                  <span className="ml-2 text-[10px] font-medium uppercase tracking-wide text-blue-700">
                    {isFullLane(job) ? 'full' : 'webhook'}
                  </span>
                </div>
                <div className="text-[11px] text-zinc-500 font-mono truncate">
                  {job.branch || 'all branches'} {job.commitSha ? `· ${job.commitSha.substring(0, 7)}` : ''}
                </div>
                <SyncPipelineStepper pipeline={pipelineFromJobAndProgress(job, progressByJobId[job.id])} compact />
                <JobProgressBar progress={progressByJobId[job.id]} compact />
              </div>
              <div className="flex items-center space-x-2 shrink-0">
                <button
                  onClick={() => setSelectedJobForLogs(job)}
                  className="px-2.5 py-1 rounded-lg bg-white border border-zinc-200 text-[11px] font-medium"
                >
                  Logs
                </button>
                <button
                  onClick={() =>
                    runAction(`pause-${job.id}`, async () => {
                      await pauseJob(job.id);
                      return `Pause requested for job #${job.id}. Current step will stop shortly; checkpoint preserved.`;
                    })
                  }
                  disabled={busy === `pause-${job.id}`}
                  className="inline-flex items-center space-x-1 px-2.5 py-1 rounded-lg bg-amber-600 hover:bg-amber-700 text-white text-[11px] font-medium disabled:opacity-50"
                >
                  <Pause className="w-3 h-3" />
                  <span>Pause</span>
                </button>
                <button
                  onClick={() =>
                    runAction(`cancel-${job.id}`, async () => {
                      await cancelJob(job.id);
                      return `Cancel requested for job #${job.id}. Fetch/push will abort shortly.`;
                    })
                  }
                  disabled={busy === `cancel-${job.id}`}
                  className="inline-flex items-center space-x-1 px-2.5 py-1 rounded-lg bg-rose-700 hover:bg-rose-800 text-white text-[11px] font-medium disabled:opacity-50"
                >
                  <Ban className="w-3 h-3" />
                  <span>Cancel run</span>
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      <div className="rounded-2xl border border-zinc-200/90 bg-white shadow-sm overflow-hidden">
        <div className="p-5 border-b border-zinc-100 space-y-3">
          <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-3">
            <div>
              <h3 className="text-sm font-semibold text-zinc-900">Job history ({historyTotal})</h3>
              <p className="text-[11px] text-zinc-500 mt-0.5">
                Database ledger of every sync, including cancelled. AMQP Ready (pending): {amqpWaiting}.
                Cancel marks jobs skipped; the worker ACKs those messages on pickup.
              </p>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <select
                value={pairFilter}
                onChange={(e) => {
                  setPairFilter(e.target.value);
                  setHistoryPage(0);
                }}
                className="bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-xs text-zinc-700"
              >
                <option value="ALL">All pairs</option>
                {mappings.map((m) => (
                  <option key={m.id} value={String(m.id)}>
                    {m.name}
                  </option>
                ))}
              </select>
              <select
                value={statusFilter}
                onChange={(e) => {
                  setStatusFilter(e.target.value);
                  setHistoryPage(0);
                }}
                className="bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-xs text-zinc-700"
              >
                <option value="ALL">All statuses</option>
                <option value="QUEUED">Queued</option>
                <option value="IN_PROGRESS">In progress</option>
                <option value="SUCCESS">Success</option>
                <option value="FAILED">Failed</option>
                <option value="INTERRUPTED">Interrupted</option>
                <option value="PAUSED">Paused</option>
                <option value="CANCELLED">Cancelled</option>
                <option value="SKIPPED">Skipped</option>
                <option value="DEAD_LETTERED">Dead-lettered</option>
                <option value="CONFLICT_ISOLATED">Conflict isolated</option>
              </select>
              <select
                value={laneFilter}
                onChange={(e) => {
                  setLaneFilter(e.target.value);
                  setHistoryPage(0);
                }}
                className="bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-xs text-zinc-700"
              >
                <option value="ALL">All lanes</option>
                <option value="INCREMENTAL">Webhook syncs</option>
                <option value="FULL">Full mirrors</option>
              </select>
              <button
                onClick={() =>
                  runAction('dispatch-selected', async () => {
                    const ids = Array.from(selectedIds);
                    const res = await dispatchJobs(ids);
                    const errNote = res.errors?.length ? ` · ${res.errors.length} error(s)` : '';
                    return `Dispatched ${res.dispatched} job(s)${res.skipped ? `, skipped ${res.skipped}` : ''}${errNote}`;
                  })
                }
                disabled={selectedIds.size === 0 || busy === 'dispatch-selected'}
                className="inline-flex items-center space-x-1 px-2.5 py-1.5 rounded-lg border border-emerald-200 bg-emerald-50 text-emerald-900 text-xs font-medium disabled:opacity-40"
              >
                <Play className="w-3.5 h-3.5" />
                <span>Dispatch selected ({selectedIds.size})</span>
              </button>
              <button
                onClick={() =>
                  runAction('cancel-selected', async () => {
                    const ids = Array.from(selectedIds);
                    for (const id of ids) {
                      await cancelJob(id);
                    }
                    return `Cancelled ${ids.length} selected job(s)`;
                  })
                }
                disabled={selectedIds.size === 0 || busy === 'cancel-selected'}
                className="inline-flex items-center space-x-1 px-2.5 py-1.5 rounded-lg border border-zinc-200 text-xs font-medium disabled:opacity-40"
              >
                <XCircle className="w-3.5 h-3.5" />
                <span>Cancel selected ({selectedIds.size})</span>
              </button>
              {pairFilter !== 'ALL' && (
                <button
                  onClick={() =>
                    runAction('cancel-pair', async () => {
                      const res = await cancelQueuedJobs(Number(pairFilter));
                      return res.message;
                    })
                  }
                  disabled={busy === 'cancel-pair'}
                  className="inline-flex items-center space-x-1 px-2.5 py-1.5 rounded-lg border border-amber-200 bg-amber-50 text-amber-900 text-xs font-medium disabled:opacity-40"
                >
                  <Ban className="w-3.5 h-3.5" />
                  <span>Cancel this pair&apos;s queue</span>
                </button>
              )}
              <button
                onClick={() =>
                  runAction('cancel-all', async () => {
                    const res = await cancelQueuedJobs();
                    return res.message;
                  })
                }
                disabled={queuedTotal === 0 || busy === 'cancel-all'}
                className="inline-flex items-center space-x-1 px-2.5 py-1.5 rounded-lg border border-rose-200 bg-rose-50 text-rose-800 text-xs font-medium disabled:opacity-40"
              >
                <Ban className="w-3.5 h-3.5" />
                <span>Cancel all queued</span>
              </button>
            </div>
          </div>
        </div>

        {historyJobs.length === 0 ? (
          <div className="py-12 text-center text-xs text-zinc-400">No jobs match these filters</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className="bg-zinc-50/70 text-zinc-500 border-b border-zinc-100">
                <tr>
                  <th className="py-2.5 px-4">
                    <input
                      type="checkbox"
                      checked={dispatchableOnPage.length > 0 && dispatchableOnPage.every((j) => selectedIds.has(j.id))}
                      onChange={toggleSelectDispatchable}
                    />
                  </th>
                  <th className="py-2.5 px-4 font-medium">Job</th>
                  <th className="py-2.5 px-4 font-medium">Pair</th>
                  <th className="py-2.5 px-4 font-medium">Lane</th>
                  <th className="py-2.5 px-4 font-medium">Branch</th>
                  <th className="py-2.5 px-4 font-medium">Trigger</th>
                  <th className="py-2.5 px-4 font-medium">Status</th>
                  <th className="py-2.5 px-4 font-medium">When</th>
                  <th className="py-2.5 px-4 font-medium text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-zinc-100">
                {historyJobs.map((job) => (
                  <tr key={job.id} className="hover:bg-zinc-50/70">
                    <td className="py-3 px-4">
                      {isDispatchableStatus(job.status) ? (
                        <input
                          type="checkbox"
                          checked={selectedIds.has(job.id)}
                          onChange={() => toggleSelected(job.id)}
                        />
                      ) : null}
                    </td>
                    <td className="py-3 px-4 font-mono text-zinc-700">#{job.id}</td>
                    <td className="py-3 px-4 font-medium text-zinc-900">{job.pairName}</td>
                    <td className="py-3 px-4 text-zinc-500">{isFullLane(job) ? 'Full' : 'Webhook'}</td>
                    <td className="py-3 px-4 font-mono text-zinc-600 max-w-[220px] truncate">{job.branch || '*'}</td>
                    <td className="py-3 px-4 text-zinc-500">{job.triggerType}</td>
                    <td className="py-3 px-4">
                      <StatusPill status={job.status} />
                    </td>
                    <td className="py-3 px-4 text-zinc-500 whitespace-nowrap">
                      {job.completedAt
                        ? new Date(job.completedAt).toLocaleString()
                        : job.createdAt
                          ? new Date(job.createdAt).toLocaleString()
                          : '--'}
                    </td>
                    <td className="py-3 px-4 text-right space-x-1">
                      <button
                        onClick={() => setSelectedJobForLogs(job)}
                        className="px-2.5 py-1 rounded-lg bg-zinc-100 hover:bg-zinc-200 text-[11px] font-medium"
                      >
                        Logs
                      </button>
                      {(job.status === 'QUEUED' || job.status === 'IN_PROGRESS') && (
                        <button
                          onClick={() =>
                            runAction(`cancel-${job.id}`, async () => {
                              await cancelJob(job.id);
                              return `Cancelled job #${job.id}`;
                            })
                          }
                          disabled={busy === `cancel-${job.id}`}
                          className="px-2.5 py-1 rounded-lg bg-zinc-100 hover:bg-rose-50 hover:text-rose-700 text-[11px] font-medium disabled:opacity-50"
                        >
                          Cancel
                        </button>
                      )}
                      {isDispatchableStatus(job.status) && job.status !== 'QUEUED' && (
                        <button
                          onClick={() =>
                            runAction(`resume-${job.id}`, async () => {
                              await resumeJob(job.id);
                              return `Job #${job.id} re-queued from checkpoint`;
                            })
                          }
                          disabled={busy === `resume-${job.id}`}
                          className="px-2.5 py-1 rounded-lg bg-emerald-50 hover:bg-emerald-100 text-emerald-800 text-[11px] font-medium disabled:opacity-50"
                        >
                          Resume
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="p-4 border-t border-zinc-100 bg-zinc-50/50 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <p className="text-[11px] text-zinc-500">
            Showing {historyJobs.length} of {historyTotal} jobs.
            Ready (pending): {amqpWaiting}
            {cancelledCount > 0 ? ` · ${cancelledCount} cancelled` : ''}
            {interruptedCount > 0 ? ` · ${interruptedCount} interrupted` : ''}
            {queuedTotal > 0 ? ` · ${queuedTotal} still queued` : ''}.
          </p>
          <div className="flex items-center gap-2">
            <button
              onClick={() => setHistoryPage((p) => Math.max(0, p - 1))}
              disabled={historyPage === 0}
              className="p-1 rounded-lg border border-zinc-200 disabled:opacity-40"
            >
              <ChevronLeft className="w-3.5 h-3.5" />
            </button>
            <span className="text-[11px] text-zinc-500">
              Page {historyPage + 1} / {Math.max(1, historyPages)}
            </span>
            <button
              onClick={() => setHistoryPage((p) => p + 1)}
              disabled={historyPage + 1 >= historyPages}
              className="p-1 rounded-lg border border-zinc-200 disabled:opacity-40"
            >
              <ChevronRight className="w-3.5 h-3.5" />
            </button>
            {confirmPurge ? (
              <div className="flex items-center space-x-2">
                <span className="text-[11px] text-rose-700 font-medium">Drop waiting AMQP messages on both lanes and cancel queued jobs?</span>
                <button
                  onClick={() =>
                    runAction('purge', async () => {
                      const res = await purgeMainQueue();
                      return res.message;
                    })
                  }
                  disabled={busy === 'purge'}
                  className="px-2.5 py-1.5 rounded-lg bg-rose-700 text-white text-xs font-medium"
                >
                  Confirm purge
                </button>
                <button onClick={() => setConfirmPurge(false)} className="px-2.5 py-1.5 rounded-lg border border-zinc-200 text-xs">
                  Back
                </button>
              </div>
            ) : (
              <button
                onClick={() => setConfirmPurge(true)}
                disabled={amqpWaiting === 0 && queuedTotal === 0}
                className="inline-flex items-center space-x-1.5 px-2.5 py-1.5 rounded-lg border border-rose-200 text-rose-700 text-xs font-medium disabled:opacity-40"
              >
                <Trash2 className="w-3.5 h-3.5" />
                <span>Purge execution queues</span>
              </button>
            )}
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <div className="rounded-2xl border border-zinc-200/90 bg-white p-5 shadow-sm space-y-3">
          <h3 className="text-sm font-semibold text-zinc-900">Dead letter recovery</h3>
          <p className="text-[11px] text-zinc-500">{dlqCount} failed message(s) waiting to be replayed onto the original lane or dropped.</p>
          <div className="flex items-center space-x-2">
            <button
              onClick={() =>
                runAction('redrive', async () => {
                  const res = await redriveDlq();
                  return res.message;
                })
              }
              disabled={dlqCount === 0 || busy === 'redrive'}
              className="inline-flex items-center space-x-1.5 px-3 py-1.5 rounded-lg bg-zinc-900 text-white text-xs font-medium disabled:opacity-40"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${busy === 'redrive' ? 'animate-spin' : ''}`} />
              <span>Redrive ({dlqCount})</span>
            </button>
            <button
              onClick={() =>
                runAction('purge-dlq', async () => {
                  const res = await purgeDlq();
                  return res.message;
                })
              }
              disabled={dlqCount === 0 || busy === 'purge-dlq'}
              className="p-1.5 rounded-lg text-zinc-400 hover:text-rose-600 disabled:opacity-30"
              title="Purge DLQ"
            >
              <Trash2 className="w-4 h-4" />
            </button>
          </div>
        </div>
        <div className="rounded-2xl border border-zinc-200/90 bg-white p-5 shadow-sm space-y-3">
          <h3 className="text-sm font-semibold text-zinc-900">Inbound webhook buffer</h3>
          <p className="text-[11px] text-zinc-500">
            Edge-ingested events waiting to be matched to a pair. Purge only if you intend to drop those deliveries.
          </p>
          <button
            onClick={() =>
              runAction('purge-inbound', async () => {
                const res = await purgeInboundQueue();
                return res.message;
              })
            }
            disabled={inboundCount === 0 || busy === 'purge-inbound'}
            className="inline-flex items-center space-x-1.5 px-3 py-1.5 rounded-lg border border-zinc-200 text-xs font-medium disabled:opacity-40"
          >
            <Trash2 className="w-3.5 h-3.5" />
            <span>Purge inbound ({inboundCount})</span>
          </button>
        </div>
      </div>

      <JobLogModal
        job={selectedJobForLogs}
        progress={selectedJobForLogs ? progressByJobId[selectedJobForLogs.id] : undefined}
        onClose={() => setSelectedJobForLogs(null)}
        onRetry={async () => undefined}
        onCancel={async (id) => {
          await cancelJob(id);
          await loadData();
        }}
        onJobUpdated={setSelectedJobForLogs}
      />
    </div>
  );
};

const StatusPill: React.FC<{ status: SyncStatus }> = ({ status }) => {
  const styles: Record<string, string> = {
    SUCCESS: 'bg-emerald-50 text-emerald-700 border-emerald-200',
    IN_PROGRESS: 'bg-blue-50 text-blue-700 border-blue-200',
    QUEUED: 'bg-zinc-100 text-zinc-600 border-zinc-200',
    CANCELLED: 'bg-zinc-100 text-zinc-500 border-zinc-200',
    FAILED: 'bg-rose-50 text-rose-700 border-rose-200',
    INTERRUPTED: 'bg-amber-50 text-amber-800 border-amber-200',
    PAUSED: 'bg-amber-50 text-amber-800 border-amber-200',
    DEAD_LETTERED: 'bg-rose-50 text-rose-700 border-rose-200',
    SKIPPED: 'bg-zinc-100 text-zinc-600 border-zinc-200',
    CONFLICT_ISOLATED: 'bg-amber-50 text-amber-700 border-amber-300',
  };
  return (
    <span className={`inline-flex px-2 py-0.5 rounded-full text-[11px] font-medium border ${styles[status] || 'bg-zinc-100 text-zinc-600 border-zinc-200'}`}>
      {status.replace('_', ' ').toLowerCase()}
    </span>
  );
};

const MetricCard: React.FC<{ label: string; value: number; hint: string; tone?: 'zinc' | 'amber' | 'rose' }> = ({
  label,
  value,
  hint,
  tone = 'zinc',
}) => (
  <div className="rounded-2xl border border-zinc-200/90 bg-white p-5 shadow-sm space-y-2">
    <div className="flex items-center justify-between text-xs text-zinc-500 font-medium">
      <span>{label}</span>
      {tone === 'amber' && value > 0 && <Clock className="w-3.5 h-3.5 text-amber-500" />}
      {tone === 'rose' && value > 0 && <Layers className="w-3.5 h-3.5 text-rose-500" />}
    </div>
    <div className={`text-2xl font-bold ${tone === 'amber' && value > 0 ? 'text-amber-800' : tone === 'rose' && value > 0 ? 'text-rose-800' : 'text-zinc-900'}`}>
      {value}
    </div>
    <p className="text-[11px] text-zinc-400 font-mono truncate">{hint}</p>
  </div>
);
