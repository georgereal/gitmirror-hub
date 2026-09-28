import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router';
import {
  AlertTriangle,
  Ban,
  Braces,
  ChevronDown,
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
import { JobProgress, QueueStatus, RepoMapping, SyncJob, SyncStatus, UnmappedWebhookEvent } from '../types';
import { KafkaStoredFailure } from '../services/api';
import { mergeProviderTraffic } from '../components/ProviderTrafficStrip';
import {
  cancelJob,
  cancelQueuedJobs,
  dispatchJobs,
  getDashboardStats,
  getJobs,
  getMappings,
  getQueueStatus,
  getUnmappedWebhooks,
  getWebhookBus,
  redriveWebhookBus,
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
import { WebhookBusStrip } from '../components/WebhookBusStrip';

const HISTORY_PAGE_SIZE = 25;
const POISON_REASONS = new Set(['KAFKA_POISON', 'KAFKA_POISON_REPLAYED']);
const JOB_STATUSES = [
  'QUEUED',
  'IN_PROGRESS',
  'SUCCESS',
  'FAILED',
  'INTERRUPTED',
  'PAUSED',
  'CANCELLED',
  'SKIPPED',
  'DEAD_LETTERED',
  'CONFLICT_ISOLATED',
] as const;
const SKIP_REASONS = [
  'UNMAPPED_REPOSITORY',
  'INACTIVE_MAPPING',
  'DIRECTION_IGNORED',
  'LOOP_DETECTED_SYSTEM_ECHO',
  'PROTECTED_TRUNK_DELETE',
  'PULL_REQUEST_PAYLOAD',
] as const;
type HistoryTab = 'full' | 'events' | 'dlq';
type IncrementalRow =
  | { kind: 'job'; id: string; at: number; job: SyncJob }
  | { kind: 'skip'; id: string; at: number; skip: UnmappedWebhookEvent };

function instantOf(value?: string): number {
  if (!value) return 0;
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? 0 : parsed;
}
type TabFilters = { pair: string; outcome: string };

const matchesSelectedPair = (
  row: { repoUrl?: string; repoFullName?: string },
  pairId: string,
  mappings: RepoMapping[],
) => {
  if (pairId === 'ALL') return true;
  const mapping = mappings.find((item) => String(item.id) === pairId);
  if (!mapping) return false;
  const hay = `${row.repoUrl ?? ''} ${row.repoFullName ?? ''}`.toLowerCase();
  const needles = [mapping.repoAUrl, mapping.repoBUrl, mapping.name]
    .filter((value): value is string => Boolean(value))
    .map((value) => value.toLowerCase().replace(/\.git$/, ''));
  return needles.some((needle) => needle.length > 0 && (hay.includes(needle) || needle.includes(hay)));
};

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
  const [filters, setFilters] = useState<Record<HistoryTab, TabFilters>>({
    full: { pair: 'ALL', outcome: 'ALL' },
    events: { pair: 'ALL', outcome: 'ALL' },
    dlq: { pair: 'ALL', outcome: 'ALL' },
  });
  const [historyTab, setHistoryTab] = useState<HistoryTab>('full');
  const [fullTotal, setFullTotal] = useState(0);
  const [eventJobTotal, setEventJobTotal] = useState(0);
  const [skippedEvents, setSkippedEvents] = useState<UnmappedWebhookEvent[]>([]);
  const [poisonRows, setPoisonRows] = useState<KafkaStoredFailure[]>([]);
  const [poisonCount, setPoisonCount] = useState(0);
  const [replayNote, setReplayNote] = useState<string | null>(null);
  const openedEventsTab = useRef(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [confirmPurge, setConfirmPurge] = useState(false);
  const [selectedJobForLogs, setSelectedJobForLogs] = useState<SyncJob | null>(null);
  const [sourceRecordId, setSourceRecordId] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [eventLimit, setEventLimit] = useState(100);

  const loadData = async () => {
    try {
      const fullFilters = filters.full;
      const eventFilters = filters.events;
      const fullMappingId = fullFilters.pair === 'ALL' ? undefined : fullFilters.pair;
      const fullStatus = fullFilters.outcome === 'ALL' ? undefined : fullFilters.outcome;
      const eventOutcomeIsJob = (JOB_STATUSES as readonly string[]).includes(eventFilters.outcome);
      const eventMappingId = eventFilters.pair === 'ALL' ? undefined : eventFilters.pair;
      const eventStatus = eventOutcomeIsJob ? eventFilters.outcome : undefined;
      const fullPageIndex = historyTab === 'full' ? historyPage : 0;
      const fullPageSize = historyTab === 'full' ? HISTORY_PAGE_SIZE : 1;
      const eventPageIndex = 0;
      const eventPageSize = historyTab === 'events' ? eventLimit : 1;
      const [q, m, fullHistory, eventHistory, running, stats, unmapped, bus] = await Promise.allSettled([
        getQueueStatus(),
        getMappings(),
        getJobs(fullPageIndex, fullPageSize, fullStatus, fullMappingId, undefined, 'FULL'),
        getJobs(eventPageIndex, eventPageSize, eventStatus, eventMappingId, undefined, 'INCREMENTAL'),
        getJobs(0, 20, 'IN_PROGRESS'),
        getDashboardStats(),
        getUnmappedWebhooks(),
        getWebhookBus(),
      ]);
      if (q.status === 'fulfilled') setQueueStatus(q.value);
      if (m.status === 'fulfilled') setMappings(m.value);
      if (fullHistory.status === 'fulfilled') setFullTotal(fullHistory.value.totalElements);
      const eventOutcomeIsSkip = eventFilters.outcome !== 'ALL' && !eventOutcomeIsJob;
      if (eventOutcomeIsSkip) {
        setEventJobTotal(0);
      } else if (eventHistory.status === 'fulfilled') {
        setEventJobTotal(eventHistory.value.totalElements);
      }
      const activeHistory = historyTab === 'events' ? eventHistory : fullHistory;
      if (historyTab === 'events' && eventOutcomeIsSkip) {
        setHistoryJobs([]);
        setHistoryTotal(0);
        setHistoryPages(0);
      } else if (activeHistory.status === 'fulfilled') {
        setHistoryJobs(activeHistory.value.content);
        setHistoryTotal(activeHistory.value.totalElements);
        setHistoryPages(activeHistory.value.totalPages);
      }
      if (unmapped.status === 'fulfilled') {
        setSkippedEvents(unmapped.value.filter((row) => !POISON_REASONS.has(row.discardReason)));
      }
      if (bus.status === 'fulfilled' && bus.value.provider === 'kafka') {
        setPoisonRows(bus.value.storedFailures ?? []);
        setPoisonCount(bus.value.storedFailureCount ?? (bus.value.storedFailures?.length ?? 0));
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
    if (openedEventsTab.current || fullTotal > 0 || skippedEvents.length === 0) return;
    openedEventsTab.current = true;
    setHistoryTab('events');
  }, [fullTotal, skippedEvents.length]);

  useEffect(() => {
    loadData();
    const timer = setInterval(loadData, 4000);
    return () => clearInterval(timer);
  }, [historyPage, filters, historyTab, eventLimit]);

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

  const toggleSelected = (id: string) => {
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
  const waitingCount = fullCount + incrementalCount;
  const fullUnacked = lanes?.find((l) => l.lane === 'FULL')?.unackedCount ?? queueStatus?.mainQueueUnackedCount ?? 0;
  const incrementalUnacked = lanes?.find((l) => l.lane === 'INCREMENTAL')?.unackedCount ?? queueStatus?.incrementalQueueUnackedCount ?? 0;
  const dlqCount = queueStatus?.dlqMessageCount ?? 0;
  const isPaused = queueStatus?.consumerPaused || false;
  const listenerRunning = queueStatus?.consumerRunning ?? true;
  const listenerStopped = !listenerRunning;
  const orphanWaiting = waitingCount > 0 && queuedTotal === 0;
  const brokerBacked = queueStatus?.supportsQueueManager !== false && queueStatus?.durableBroker !== false;
  const supportsDlq = brokerBacked && queueStatus?.supportsDlq !== false;
  const supportsPurge = brokerBacked && queueStatus?.supportsPurge !== false;
  const supportsInbound = brokerBacked && queueStatus?.supportsInboundBrokerQueue !== false;
  const activeFilters = filters[historyTab];
  const visibleSkips = useMemo(() => {
    const outcome = filters.events.outcome;
    const jobStatus = (JOB_STATUSES as readonly string[]).includes(outcome);
    return skippedEvents.filter((row) => {
      if (!matchesSelectedPair(row, filters.events.pair, mappings)) return false;
      if (outcome === 'ALL' || outcome === 'SKIPPED') return true;
      if (jobStatus) return false;
      return row.discardReason === outcome;
    });
  }, [skippedEvents, filters.events, mappings]);
  const showSkipList = historyTab === 'events' && (
    filters.events.outcome === 'ALL'
    || filters.events.outcome === 'SKIPPED'
    || !(JOB_STATUSES as readonly string[]).includes(filters.events.outcome)
  );
  const incrementalRows = useMemo(() => {
    if (historyTab !== 'events') return [];
    const jobs: IncrementalRow[] = historyJobs.map((job) => ({
      kind: 'job',
      id: `job-${job.id}`,
      at: instantOf(job.completedAt || job.createdAt),
      job,
    }));
    const skips: IncrementalRow[] = showSkipList
      ? visibleSkips.map((skip) => ({
          kind: 'skip',
          id: `skip-${skip.id}`,
          at: instantOf(skip.receivedAt),
          skip,
        }))
      : [];
    return [...jobs, ...skips].sort((left, right) => right.at - left.at);
  }, [historyTab, historyJobs, showSkipList, visibleSkips]);
  const incrementalPages = Math.max(1, Math.ceil(incrementalRows.length / HISTORY_PAGE_SIZE));
  const pagedIncremental = incrementalRows.slice(
    historyPage * HISTORY_PAGE_SIZE,
    (historyPage + 1) * HISTORY_PAGE_SIZE,
  );
  const eventJobsOnPage = pagedIncremental.flatMap((row) => (row.kind === 'job' ? [row.job] : []));
  const eventDispatchable = eventJobsOnPage.filter((job) => isDispatchableStatus(job.status));
  const visiblePoison = useMemo(() => {
    return poisonRows.filter((row) => {
      if (!matchesSelectedPair(row, filters.dlq.pair, mappings)) return false;
      if (filters.dlq.outcome === 'ALL') return true;
      return filters.dlq.outcome === 'KAFKA_POISON';
    });
  }, [poisonRows, filters.dlq, mappings]);
  const pageTitle = brokerBacked ? 'Queue Manager' : 'Execution';
  const pageBlurb = brokerBacked
    ? 'Job history is the source of truth. RabbitMQ depths are live broker health, not the job list. Webhook syncs and full mirrors run on separate consumers so one clone cannot block other pairs.'
    : `${queueStatus?.messagingDisplayName ?? 'In-process execution'}: ${
        queueStatus?.messagingDescription
          ?? 'No external broker — sync jobs run on this JVM. Pause defers work in memory; not for multi-pod webhook durability.'
      }`;
  const workerHint = queueStatus?.workerThreads != null
    ? `${queueStatus.workerThreads} in-process worker thread${queueStatus.workerThreads === 1 ? '' : 's'}`
    : 'in-process workers';

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-start justify-between gap-3">
        <div>
          <h2 className="text-base font-semibold text-zinc-900">{pageTitle}</h2>
          <p className="text-xs text-zinc-500 mt-0.5">{pageBlurb}</p>
        </div>
        <Link
          to="/observability"
          className="text-xs font-medium text-zinc-600 hover:text-zinc-900 px-3 py-1.5 rounded-lg hover:bg-zinc-100"
        >
          Open activity stream
        </Link>
      </div>

      <WebhookBusStrip />

      {isPaused && (
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

      {listenerStopped && !isPaused && (
        <div className="rounded-2xl border border-rose-200 bg-rose-50/80 p-4 flex items-start justify-between gap-3 text-xs">
          <div className="flex items-start space-x-2.5">
            <AlertTriangle className="w-4 h-4 text-rose-600 shrink-0 mt-0.5" />
            <div>
              <strong className="text-rose-900">An execution worker is stopped, not paused.</strong>
              <p className="text-rose-700 text-[11px] mt-0.5">
                {brokerBacked
                  ? "Resume both listeners. Cancelled jobs are skipped and ACK'd on pickup so the AMQP count drains."
                  : 'Resume in-process workers so deferred and queued jobs can run on this JVM.'}
              </p>
            </div>
          </div>
          <button
            onClick={() =>
              runAction('consumer', async () => {
                await resumeConsumer();
                return brokerBacked
                  ? 'Consumers restarted; cancelled leftovers will be skipped'
                  : 'Workers resumed';
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

      {orphanWaiting && !listenerStopped && (
        <div className="rounded-2xl border border-amber-200 bg-amber-50/80 p-4 flex items-start space-x-2.5 text-xs">
          <AlertTriangle className="w-4 h-4 text-amber-600 shrink-0 mt-0.5" />
          <div>
            <strong className="text-amber-900">
              {waitingCount} {brokerBacked ? 'Ready (pending) message' : 'deferred job'}
              {waitingCount === 1 ? '' : 's'}; {cancelledCount || 0} job
              {cancelledCount === 1 ? ' is' : 's are'} already cancelled in history.
            </strong>
            <p className="text-amber-700 text-[11px] mt-0.5">
              {brokerBacked
                ? 'Ready is waiting in RabbitMQ. Unacked is the message a consumer thread currently holds. Cancel updates the database immediately; leftovers ACK on pickup.'
                : 'Deferred jobs wait in memory while workers are paused. Cancel updates the database; resume workers to drain the rest.'}
            </p>
          </div>
        </div>
      )}

      {waitingCount > 20 && queuedTotal > 0 && (
        <div className="rounded-2xl border border-amber-200 bg-amber-50/80 p-4 flex items-start space-x-2.5 text-xs">
          <AlertTriangle className="w-4 h-4 text-amber-600 shrink-0 mt-0.5" />
          <div>
            <strong className="text-amber-900">
              {queuedTotal} queued jobs · {waitingCount}{' '}
              {brokerBacked ? 'Ready messages waiting' : 'deferred / pending'}.
            </strong>
            <p className="text-amber-700 text-[11px] mt-0.5">
              Full mirrors ({fullCount} ready / {fullUnacked} in-flight) and webhook syncs ({incrementalCount} ready /{' '}
              {incrementalUnacked} in-flight) are separate lanes
              {!brokerBacked ? ` sharing ${workerHint}` : ''}.
            </p>
          </div>
        </div>
      )}

      {feedback && (
        <div className="rounded-xl border border-blue-200 bg-blue-50 p-3 text-xs text-blue-800">{feedback}</div>
      )}

      <ConsumerRuntimePanel lanes={lanes} durableBroker={brokerBacked} />

      <div className={`grid grid-cols-1 ${supportsDlq ? 'sm:grid-cols-2' : ''} gap-4`}>
        {supportsDlq && (
          <MetricCard label="Dead letter queue" value={dlqCount} hint={queueStatus?.dlqQueueName || 'git.sync.dlq'} tone={dlqCount > 0 ? 'rose' : 'zinc'} />
        )}
        <div className="rounded-2xl border border-zinc-200/90 bg-white p-5 shadow-sm space-y-2">
          <div className="text-xs text-zinc-500 font-medium">Execution consumers</div>
          <div className="text-lg font-bold text-zinc-900">
            {isPaused ? 'Paused' : listenerStopped ? 'Stopped' : 'Active'}
          </div>
          <p className="text-[10px] text-zinc-400">
            {brokerBacked
              ? 'Pause both Git execution lanes. Inbound webhook ingest stays up.'
              : `Pause defers new work in memory (${workerHint}).`}
          </p>
          <button
            onClick={() =>
              runAction('consumer', async () => {
                if (isPaused || listenerStopped) {
                  await resumeConsumer();
                  return 'Consumers resumed';
                }
                await pauseConsumer();
                return brokerBacked
                  ? 'Consumers paused; messages will buffer'
                  : 'Workers paused; new jobs deferred in memory';
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
              <h3 className="text-sm font-semibold text-zinc-900">Job history</h3>
              <div className="flex flex-wrap items-center gap-1.5 mt-2">
                {([
                  ['full', `Full syncs (${fullTotal})`],
                  ['events', `Incremental events (${eventJobTotal + visibleSkips.length})`],
                  ['dlq', `Dead letter (${visiblePoison.length})`],
                ] as const).map(([id, label]) => (
                  <button
                    key={id}
                    type="button"
                    onClick={() => {
                      setHistoryTab(id);
                      setHistoryPage(0);
                    }}
                    className={`px-2.5 py-1 rounded-lg text-xs font-medium ${
                      historyTab === id
                        ? 'bg-zinc-900 text-white'
                        : 'text-zinc-600 hover:bg-zinc-100'
                    }`}
                  >
                    {label}
                  </button>
                ))}
              </div>
              <p className="text-[11px] text-zinc-500 mt-2">
                {historyTab === 'full' && 'Full mirror jobs. '}
                {historyTab === 'events' && 'Webhook jobs and skipped topic records in one list, newest first. '}
                {historyTab === 'dlq' && 'Incremental records Hub could not apply. Stored in the database and kept past the 7-day discard cleanup. '}
                {brokerBacked
                  ? `AMQP Ready (pending): ${waitingCount}. Cancel marks jobs skipped; the worker ACKs those messages on pickup.`
                  : `Deferred (paused): ${waitingCount}. Cancel marks jobs skipped in the database.`}
              </p>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <select
                value={activeFilters.pair}
                onChange={(e) => {
                  const pair = e.target.value;
                  setFilters((prev) => ({ ...prev, [historyTab]: { ...prev[historyTab], pair } }));
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
                value={activeFilters.outcome}
                onChange={(e) => {
                  const outcome = e.target.value;
                  setFilters((prev) => ({ ...prev, [historyTab]: { ...prev[historyTab], outcome } }));
                  setHistoryPage(0);
                }}
                className="bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-xs text-zinc-700"
              >
                <option value="ALL">{historyTab === 'dlq' ? 'All dead-letter rows' : 'All statuses'}</option>
                {historyTab !== 'dlq' && JOB_STATUSES.map((status) => (
                  <option key={status} value={status}>
                    {status.charAt(0) + status.slice(1).toLowerCase().replaceAll('_', ' ')}
                  </option>
                ))}
                {historyTab === 'events' && SKIP_REASONS.map((reason) => (
                  <option key={reason} value={reason}>{reason}</option>
                ))}
                {historyTab === 'dlq' && <option value="KAFKA_POISON">Dead letter</option>}
              </select>
              {historyTab !== 'dlq' && (
              <>
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
              {historyTab !== 'dlq' && activeFilters.pair !== 'ALL' && (
                <button
                  onClick={() =>
                    runAction('cancel-pair', async () => {
                      const res = await cancelQueuedJobs(activeFilters.pair);
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
              </>
              )}
            </div>
          </div>
        </div>

        {historyTab === 'dlq' ? (
          <div>
            <div className="px-5 py-3 border-b border-zinc-100 flex justify-end">
              <button
                type="button"
                disabled={poisonCount === 0 || busy === 'replay-poison'}
                onClick={() =>
                  runAction('replay-poison', async () => {
                    const result = await redriveWebhookBus(10);
                    setReplayNote(`Replayed ${result.redriven}.`);
                    return `Replayed ${result.redriven} stored failure${result.redriven === 1 ? '' : 's'}`;
                  })
                }
                className="px-3 py-1.5 rounded-lg border border-zinc-200 text-xs font-medium hover:bg-zinc-50 disabled:opacity-50"
              >
                {busy === 'replay-poison' ? 'Replaying…' : 'Replay stored failures'}
              </button>
            </div>
            {visiblePoison.length === 0 ? (
              <div className="py-12 text-center text-xs text-zinc-400">No dead-letter rows match this filter</div>
            ) : (
              <WebhookRecordTable
                rows={visiblePoison}
                sourceRecordId={sourceRecordId}
                idPrefix="poison-"
                onToggle={(id) => setSourceRecordId((current) => current === id ? null : id)}
              />
            )}
            {replayNote && <p className="px-5 pb-3 text-[11px] text-zinc-500">{replayNote}</p>}
          </div>
        ) : historyTab === 'events' ? (
          pagedIncremental.length === 0 ? (
            <div className="py-12 text-center text-xs text-zinc-400">No incremental events match this filter</div>
          ) : (
            <IncrementalFeed
              rows={pagedIncremental}
              sourceRecordId={sourceRecordId}
              expandedId={expandedId}
              selectedIds={selectedIds}
              dispatchableIds={eventDispatchable.map((job) => job.id)}
              allDispatchableSelected={eventDispatchable.length > 0 && eventDispatchable.every((job) => selectedIds.has(job.id))}
              busy={busy}
              onToggleSource={(id) => setSourceRecordId((current) => current === id ? null : id)}
              onToggleExpanded={(id) => setExpandedId((current) => current === id ? null : id)}
              onToggleSelected={toggleSelected}
              onToggleSelectDispatchable={() => {
                const visibleIds = eventDispatchable.map((job) => job.id);
                const allSelected = visibleIds.length > 0 && visibleIds.every((id) => selectedIds.has(id));
                setSelectedIds((prev) => {
                  const next = new Set(prev);
                  if (allSelected) visibleIds.forEach((id) => next.delete(id));
                  else visibleIds.forEach((id) => next.add(id));
                  return next;
                });
              }}
              onLogs={setSelectedJobForLogs}
              onCancel={(job) =>
                runAction(`cancel-${job.id}`, async () => {
                  await cancelJob(job.id);
                  return `Cancelled job #${job.id}`;
                })
              }
              onResume={(job) =>
                runAction(`resume-${job.id}`, async () => {
                  await resumeJob(job.id);
                  return `Job #${job.id} re-queued from checkpoint`;
                })
              }
            />
          )
        ) : historyJobs.length === 0 ? (
          <div className="py-12 text-center text-xs text-zinc-400">No full sync jobs</div>
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
                  <React.Fragment key={job.id}>
                  <tr className="hover:bg-zinc-50/70">
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
                  </React.Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {historyTab !== 'dlq' && (
        <div className="p-4 border-t border-zinc-100 bg-zinc-50/50 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <p className="text-[11px] text-zinc-500">
            {historyTab === 'events'
              ? `Showing ${pagedIncremental.length} of ${incrementalRows.length} events.`
              : `Showing ${historyJobs.length} of ${historyTotal} jobs.`}
            {historyTab === 'events' && eventJobTotal > historyJobs.length
              ? ` Latest ${historyJobs.length} of ${eventJobTotal} jobs.`
              : ''}
            {brokerBacked ? ' Ready (pending): ' : ' Deferred: '}
            {waitingCount}
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
              Page {historyPage + 1} / {Math.max(1, historyTab === 'events' ? incrementalPages : historyPages)}
            </span>
            {historyTab === 'events' && eventJobTotal > historyJobs.length && (
              <button
                type="button"
                onClick={() => setEventLimit((limit) => limit + 100)}
                className="px-2 py-1 rounded-lg border border-zinc-200 text-[11px] font-medium"
              >
                Load older jobs
              </button>
            )}
            <button
              onClick={() => setHistoryPage((p) => p + 1)}
              disabled={historyPage + 1 >= (historyTab === 'events' ? incrementalPages : historyPages)}
              className="p-1 rounded-lg border border-zinc-200 disabled:opacity-40"
            >
              <ChevronRight className="w-3.5 h-3.5" />
            </button>
            {supportsPurge && (
              confirmPurge ? (
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
                  disabled={waitingCount === 0 && queuedTotal === 0}
                  className="inline-flex items-center space-x-1.5 px-2.5 py-1.5 rounded-lg border border-rose-200 text-rose-700 text-xs font-medium disabled:opacity-40"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                  <span>Purge execution queues</span>
                </button>
              )
            )}
          </div>
        </div>
        )}
      </div>

      {(supportsDlq || supportsInbound) && (
      <div className={`grid grid-cols-1 ${supportsDlq && supportsInbound ? 'sm:grid-cols-2' : ''} gap-4`}>
        {supportsDlq && (
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
        )}
        {supportsInbound && (
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
        )}
      </div>
      )}

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

type WebhookRecordRow = {
  id: string;
  repoFullName?: string;
  repoUrl?: string;
  branch?: string;
  eventType?: string;
  schemaVersion?: string;
  discardReason?: string;
  details?: string;
  commitSha?: string;
  payloadJson?: string;
  receivedAt?: string;
};

const WebhookRecordTable: React.FC<{
  rows: WebhookRecordRow[];
  sourceRecordId: string | null;
  idPrefix?: string;
  onToggle: (id: string) => void;
}> = ({ rows, sourceRecordId, idPrefix = '', onToggle }) => (
  <div className="border-b border-zinc-100">
    <div className="hidden md:block">
      <table className="w-full table-fixed text-left text-xs">
        <thead className="bg-zinc-50/70 text-zinc-500 border-b border-zinc-100">
          <tr>
            <th className="py-2.5 px-3 font-medium w-[32%]">Repository</th>
            <th className="py-2.5 px-3 font-medium w-[18%]">Event</th>
            <th className="py-2.5 px-3 font-medium w-[28%]">Outcome</th>
            <th className="py-2.5 px-3 font-medium w-[14%]">When</th>
            <th className="py-2.5 px-3 font-medium w-[8%] text-right">Message</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-zinc-100">
          {rows.map((row) => {
            const recordId = `${idPrefix}${row.id}`;
            const open = sourceRecordId === recordId;
            return (
              <React.Fragment key={recordId}>
                <tr className="hover:bg-zinc-50/70">
                  <td className="py-3 px-3">
                    <div className="font-medium text-zinc-900 truncate" title={row.repoFullName || row.repoUrl || ''}>
                      {row.repoFullName || row.repoUrl || 'Unknown repo'}
                    </div>
                    <div className="font-mono text-[11px] text-zinc-500 truncate">
                      {row.branch || '—'}
                      {row.commitSha ? ` · ${row.commitSha.slice(0, 7)}` : ''}
                    </div>
                  </td>
                  <td className="py-3 px-3">
                    <div className="text-zinc-800 truncate">{row.eventType || '—'}</div>
                    <div className="text-[11px] text-zinc-500 truncate">{row.schemaVersion || '—'}</div>
                  </td>
                  <td className="py-3 px-3">
                    <ReasonBadge reason={row.discardReason} />
                    <div className="text-[11px] text-zinc-500 truncate mt-0.5" title={row.details || ''}>{row.details || '—'}</div>
                  </td>
                  <td className="py-3 px-3 text-zinc-500">{shortWhen(row.receivedAt)}</td>
                  <td className="py-3 px-3 text-right">
                    <KafkaMessageButton present={Boolean(row.payloadJson)} open={open} onClick={() => onToggle(recordId)} />
                  </td>
                </tr>
                {open && row.payloadJson && (
                  <tr className="bg-zinc-50/80">
                    <td colSpan={5} className="px-3 py-2">
                      <PayloadBlock value={row.payloadJson} />
                    </td>
                  </tr>
                )}
              </React.Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
    <ul className="md:hidden divide-y divide-zinc-100">
      {rows.map((row) => {
        const recordId = `${idPrefix}${row.id}`;
        const open = sourceRecordId === recordId;
        return (
          <li key={recordId} className="p-3 text-xs">
            <div className="flex items-start justify-between gap-2">
              <div className="min-w-0">
                <div className="font-medium text-zinc-900 truncate">{row.repoFullName || row.repoUrl || 'Unknown repo'}</div>
                <div className="font-mono text-[11px] text-zinc-500 truncate">
                  {row.branch || '—'}
                  {row.commitSha ? ` · ${row.commitSha.slice(0, 7)}` : ''}
                </div>
              </div>
              <ReasonBadge reason={row.discardReason} />
            </div>
            <p className="mt-1 text-[11px] text-zinc-600">{row.eventType || '—'} · {row.schemaVersion || '—'}</p>
            <p className="mt-0.5 text-[11px] text-zinc-500">{row.details || '—'}</p>
            <div className="mt-2 flex items-center justify-between">
              <span className="text-[11px] text-zinc-400">{shortWhen(row.receivedAt)}</span>
              <KafkaMessageButton present={Boolean(row.payloadJson)} open={open} onClick={() => onToggle(recordId)} />
            </div>
            {open && row.payloadJson && <div className="mt-2"><PayloadBlock value={row.payloadJson} /></div>}
          </li>
        );
      })}
    </ul>
  </div>
);

const IncrementalFeed: React.FC<{
  rows: IncrementalRow[];
  sourceRecordId: string | null;
  expandedId: string | null;
  selectedIds: Set<string>;
  dispatchableIds: string[];
  allDispatchableSelected: boolean;
  busy: string | null;
  onToggleSource: (id: string) => void;
  onToggleExpanded: (id: string) => void;
  onToggleSelected: (id: string) => void;
  onToggleSelectDispatchable: () => void;
  onLogs: (job: SyncJob) => void;
  onCancel: (job: SyncJob) => void;
  onResume: (job: SyncJob) => void;
}> = ({
  rows,
  sourceRecordId,
  expandedId,
  selectedIds,
  dispatchableIds,
  allDispatchableSelected,
  busy,
  onToggleSource,
  onToggleExpanded,
  onToggleSelected,
  onToggleSelectDispatchable,
  onLogs,
  onCancel,
  onResume,
}) => {
  const dispatchable = new Set(dispatchableIds);
  return (
    <div>
      <div className="hidden md:block">
        <table className="w-full table-fixed text-left text-xs">
          <thead className="bg-zinc-50/70 text-zinc-500 border-b border-zinc-100">
            <tr>
              <th className="py-2.5 px-3 font-medium w-[34%]">
                <span className="inline-flex items-center gap-2">
                  {dispatchableIds.length > 0 && (
                    <input type="checkbox" checked={allDispatchableSelected} onChange={onToggleSelectDispatchable} />
                  )}
                  Repository
                </span>
              </th>
              <th className="py-2.5 px-3 font-medium w-[16%]">Event</th>
              <th className="py-2.5 px-3 font-medium w-[26%]">Outcome</th>
              <th className="py-2.5 px-3 font-medium w-[14%]">When</th>
              <th className="py-2.5 px-3 font-medium w-[10%] text-right"> </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-100">
            {rows.map((row) => (
              <IncrementalDesktopRow
                key={row.id}
                row={row}
                sourceOpen={sourceRecordId === row.id}
                expanded={expandedId === row.id}
                selected={row.kind === 'job' && selectedIds.has(row.job.id)}
                dispatchable={row.kind === 'job' && dispatchable.has(row.job.id)}
                busy={busy}
                onToggleSource={() => onToggleSource(row.id)}
                onToggleExpanded={() => onToggleExpanded(row.id)}
                onToggleSelected={() => row.kind === 'job' && onToggleSelected(row.job.id)}
                onLogs={onLogs}
                onCancel={onCancel}
                onResume={onResume}
              />
            ))}
          </tbody>
        </table>
      </div>
      <ul className="md:hidden divide-y divide-zinc-100">
        {rows.map((row) => (
          <IncrementalMobileCard
            key={row.id}
            row={row}
            sourceOpen={sourceRecordId === row.id}
            expanded={expandedId === row.id}
            selected={row.kind === 'job' && selectedIds.has(row.job.id)}
            dispatchable={row.kind === 'job' && dispatchable.has(row.job.id)}
            busy={busy}
            onToggleSource={() => onToggleSource(row.id)}
            onToggleExpanded={() => onToggleExpanded(row.id)}
            onToggleSelected={() => row.kind === 'job' && onToggleSelected(row.job.id)}
            onLogs={onLogs}
            onCancel={onCancel}
            onResume={onResume}
          />
        ))}
      </ul>
    </div>
  );
};

type FeedHandlers = {
  row: IncrementalRow;
  sourceOpen: boolean;
  expanded: boolean;
  selected: boolean;
  dispatchable: boolean;
  busy: string | null;
  onToggleSource: () => void;
  onToggleExpanded: () => void;
  onToggleSelected: () => void;
  onLogs: (job: SyncJob) => void;
  onCancel: (job: SyncJob) => void;
  onResume: (job: SyncJob) => void;
};

const IncrementalDesktopRow: React.FC<FeedHandlers> = (props) => {
  const view = feedView(props.row);
  return (
    <React.Fragment>
      <tr className="hover:bg-zinc-50/70">
        <td className="py-3 px-3">
          <div className="flex items-start gap-2 min-w-0">
            {props.dispatchable ? (
              <input type="checkbox" className="mt-0.5" checked={props.selected} onChange={props.onToggleSelected} />
            ) : (
              <span className="w-3.5 shrink-0" />
            )}
            <div className="min-w-0">
              <div className="font-medium text-zinc-900 truncate" title={view.repo}>{view.repo}</div>
              <div className="font-mono text-[11px] text-zinc-500 truncate">{view.branchLine}</div>
            </div>
          </div>
        </td>
        <td className="py-3 px-3">
          <div className="text-zinc-800 truncate">{view.eventType}</div>
          <div className="text-[11px] text-zinc-500 truncate">{view.adapter}</div>
        </td>
        <td className="py-3 px-3">
          {view.job ? <StatusPill status={view.job.status} /> : <ReasonBadge reason={view.reason} />}
          <div className="text-[11px] text-zinc-500 truncate mt-0.5" title={view.detail}>{view.detail}</div>
        </td>
        <td className="py-3 px-3 text-zinc-500">{shortWhen(view.when)}</td>
        <td className="py-3 px-3">
          <div className="flex items-center justify-end gap-1">
            <KafkaMessageButton present={Boolean(view.payload)} open={props.sourceOpen} onClick={props.onToggleSource} />
            <button
              type="button"
              title={props.expanded ? 'Hide details' : 'Show details'}
              aria-label={props.expanded ? 'Hide details' : 'Show details'}
              aria-expanded={props.expanded}
              onClick={props.onToggleExpanded}
              className="inline-flex p-1.5 rounded-md border border-zinc-200 text-zinc-600 hover:bg-zinc-50"
            >
              <ChevronDown className={`w-3.5 h-3.5 transition-transform ${props.expanded ? 'rotate-180' : ''}`} />
            </button>
          </div>
        </td>
      </tr>
      {(props.expanded || props.sourceOpen) && (
        <tr className="bg-zinc-50/80">
          <td colSpan={5} className="px-3 py-2">
            <FeedExtra {...props} />
          </td>
        </tr>
      )}
    </React.Fragment>
  );
};

const IncrementalMobileCard: React.FC<FeedHandlers> = (props) => {
  const view = feedView(props.row);
  return (
    <li className="p-3 text-xs">
      <div className="flex items-start justify-between gap-2">
        <div className="flex items-start gap-2 min-w-0">
          {props.dispatchable && (
            <input type="checkbox" className="mt-0.5" checked={props.selected} onChange={props.onToggleSelected} />
          )}
          <div className="min-w-0">
            <div className="font-medium text-zinc-900 truncate">{view.repo}</div>
            <div className="font-mono text-[11px] text-zinc-500 truncate">{view.branchLine}</div>
          </div>
        </div>
        {view.job ? <StatusPill status={view.job.status} /> : <ReasonBadge reason={view.reason} />}
      </div>
      <p className="mt-1 text-[11px] text-zinc-600">{view.eventType} · {view.adapter}</p>
      <p className="mt-0.5 text-[11px] text-zinc-500 truncate">{view.detail}</p>
      <div className="mt-2 flex items-center justify-between gap-2">
        <span className="text-[11px] text-zinc-400">{shortWhen(view.when)}</span>
        <span className="inline-flex items-center gap-1">
          <KafkaMessageButton present={Boolean(view.payload)} open={props.sourceOpen} onClick={props.onToggleSource} />
          <button
            type="button"
            aria-expanded={props.expanded}
            onClick={props.onToggleExpanded}
            className="inline-flex items-center gap-1 px-2 py-1 rounded-md border border-zinc-200 text-[11px] font-medium text-zinc-700"
          >
            Details
            <ChevronDown className={`w-3.5 h-3.5 transition-transform ${props.expanded ? 'rotate-180' : ''}`} />
          </button>
        </span>
      </div>
      {(props.expanded || props.sourceOpen) && (
        <div className="mt-2">
          <FeedExtra {...props} />
        </div>
      )}
    </li>
  );
};

const FeedExtra: React.FC<FeedHandlers> = ({ row, sourceOpen, expanded, busy, onLogs, onCancel, onResume }) => {
  const view = feedView(row);
  return (
    <div className="space-y-2 text-[11px] text-zinc-600">
      {expanded && (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          {view.job && <span className="font-mono text-zinc-700">Job #{view.job.id}</span>}
          {view.job && <span>{view.job.triggerType}</span>}
          <span>{view.when ? new Date(view.when).toLocaleString() : '—'}</span>
          {view.commit && <span className="font-mono">{view.commit}</span>}
          {view.detail !== '—' && <span>{view.detail}</span>}
        </div>
      )}
      {expanded && view.job && (
        <div className="flex flex-wrap gap-1">
          <button
            type="button"
            onClick={() => onLogs(view.job!)}
            className="px-2.5 py-1 rounded-lg bg-white border border-zinc-200 text-[11px] font-medium"
          >
            Logs
          </button>
          {(view.job.status === 'QUEUED' || view.job.status === 'IN_PROGRESS') && (
            <button
              type="button"
              onClick={() => onCancel(view.job!)}
              disabled={busy === `cancel-${view.job.id}`}
              className="px-2.5 py-1 rounded-lg bg-white border border-zinc-200 hover:text-rose-700 text-[11px] font-medium disabled:opacity-50"
            >
              Cancel
            </button>
          )}
          {isDispatchableStatus(view.job.status) && view.job.status !== 'QUEUED' && (
            <button
              type="button"
              onClick={() => onResume(view.job!)}
              disabled={busy === `resume-${view.job.id}`}
              className="px-2.5 py-1 rounded-lg bg-emerald-50 text-emerald-800 text-[11px] font-medium disabled:opacity-50"
            >
              Resume
            </button>
          )}
        </div>
      )}
      {sourceOpen && view.payload && <PayloadBlock value={view.payload} />}
    </div>
  );
};

function feedView(row: IncrementalRow) {
  if (row.kind === 'job') {
    const job = row.job;
    const commit = job.commitSha?.slice(0, 10);
    return {
      job,
      repo: job.pairName || 'Unknown pair',
      branchLine: `${job.branch || '*'}${commit ? ` · ${commit}` : ''}`,
      eventType: job.webhookEventType || '—',
      adapter: job.schemaVersion || '—',
      reason: undefined as string | undefined,
      detail: job.errorMessage || job.summaryMessage || job.triggerType || '—',
      when: job.completedAt || job.createdAt,
      commit: job.commitSha,
      payload: job.sourceMessage,
    };
  }
  const skip = row.skip;
  const commit = skip.commitSha?.slice(0, 7);
  return {
    job: undefined as SyncJob | undefined,
    repo: skip.repoFullName || skip.repoUrl || 'Unknown repo',
    branchLine: `${skip.branch || '—'}${commit ? ` · ${commit}` : ''}`,
    eventType: skip.eventType || '—',
    adapter: skip.schemaVersion || '—',
    reason: skip.discardReason,
    detail: skip.details || '—',
    when: skip.receivedAt,
    commit: skip.commitSha,
    payload: skip.payloadJson,
  };
}

const ReasonBadge: React.FC<{ reason?: string }> = ({ reason }) => {
  if (!reason) return <span className="text-zinc-400">—</span>;
  return (
    <span className="inline-flex max-w-full truncate px-2 py-0.5 rounded-full text-[10px] font-medium bg-amber-50 text-amber-800 border border-amber-200" title={reason}>
      {reason}
    </span>
  );
};

const PayloadBlock: React.FC<{ value: string }> = ({ value }) => (
  <pre className="max-h-48 overflow-auto rounded bg-white border border-zinc-100 p-2 text-[10px] text-zinc-700 whitespace-pre-wrap">{formatPayload(value)}</pre>
);

function shortWhen(value?: string): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
}

const KafkaMessageButton: React.FC<{
  present: boolean;
  open: boolean;
  onClick: () => void;
}> = ({ present, open, onClick }) => {
  if (!present) {
    return <span className="text-zinc-300">—</span>;
  }
  return (
    <button
      type="button"
      title={open ? 'Hide Kafka message' : 'Show Kafka message'}
      aria-label={open ? 'Hide Kafka message' : 'Show Kafka message'}
      aria-pressed={open}
      onClick={onClick}
      className={`inline-flex p-1.5 rounded-md border ${open ? 'bg-zinc-900 text-white border-zinc-900' : 'border-zinc-200 text-zinc-600 hover:bg-zinc-50'}`}
    >
      <Braces className="w-3.5 h-3.5" />
    </button>
  );
};

function formatPayload(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

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
