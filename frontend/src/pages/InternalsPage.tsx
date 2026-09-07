import React, { useEffect, useState } from 'react';
import { Activity, AlertTriangle, Cpu, Gauge, GitBranch, Layers, RefreshCw } from 'lucide-react';
import { RuntimeMetrics, ScmQuotas } from '../types';
import { ClusterFleetStrip } from '../components/ClusterFleetStrip';
import { useClusterRuntimeMetrics } from '../hooks/useClusterRuntimeMetrics';
import { getScmQuotas } from '../services/api';

const formatBytes = (n: number) => {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`;
  return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`;
};

type ActionMeta = {
  action: string;
  detail: string;
  config?: string;
  sort: number;
};

const POOL_ACTIONS: Record<string, ActionMeta> = {
  'gitmirror.lfs.discovery': {
    action: 'LFS discovery',
    detail: 'Parallel scan of commit trees for LFS pointers',
    config: 'GIT_LFS_DISCOVERY_THREADS',
    sort: 10,
  },
  'gitmirror.lfs.transfer': {
    action: 'LFS transfer',
    detail: 'Parallel download/upload of LFS binary blobs',
    config: 'GIT_LFS_TRANSFER_CONCURRENCY',
    sort: 20,
  },
  'gitmirror.pr.create': {
    action: 'PR create',
    detail: 'Parallel create of mirrored pull requests on destination',
    config: 'GIT_PR_CREATE_CONCURRENCY',
    sort: 30,
  },
  'gitmirror.sync': {
    action: 'Async helpers',
    detail: 'Spring @Async work (e.g. enterprise log sinks) — not Git branch sync',
    config: 'syncTaskExecutor (fixed 4–10)',
    sort: 90,
  },
};

const LANE_ACTIONS: Record<string, ActionMeta> = {
  FULL: {
    action: 'Full mirror / Git sync',
    detail: 'Bare fetch + push for full-clone jobs (*); may run PR/LFS/releases stages',
    config: 'git.sync.queue · concurrency 1',
    sort: 10,
  },
  INCREMENTAL: {
    action: 'Branch sync',
    detail: 'Webhook / Sync main / overwrite — Git (+ LFS) on a specific ref',
    config: 'git.sync.incremental.queue · concurrency 1',
    sort: 20,
  },
  INBOUND: {
    action: 'Webhook ingest',
    detail: 'Edge → Hub inbound buffer before routing to an execution lane',
    config: 'git.sync.inbound.queue',
    sort: 30,
  },
};

const poolMeta = (name: string): ActionMeta =>
  POOL_ACTIONS[name] ?? {
    action: name.replace(/^gitmirror\./, ''),
    detail: 'Metered executor pool',
    sort: 100,
  };

const laneMeta = (lane: string): ActionMeta =>
  LANE_ACTIONS[lane] ?? {
    action: lane,
    detail: 'Rabbit consumer lane',
    sort: 100,
  };

const ActionCell: React.FC<{ meta: ActionMeta; meterId?: string }> = ({ meta, meterId }) => (
  <div className="min-w-[14rem]">
    <p className="font-medium text-zinc-900">{meta.action}</p>
    <p className="text-xs text-zinc-500 mt-0.5 leading-snug">{meta.detail}</p>
    <p className="text-[11px] text-zinc-400 mt-1 font-mono">
      {meta.config ?? meterId}
      {meterId && meta.config ? ` · ${meterId.replace(/^gitmirror\./, '')}` : ''}
    </p>
  </div>
);

const InstanceDetail: React.FC<{ metrics: RuntimeMetrics }> = ({ metrics }) => {
  const cbOpen = metrics.circuitBreaker?.state === 'OPEN';
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-3">
        <div className="rounded-lg border border-zinc-200 bg-zinc-50/50 p-3">
          <p className="text-[11px] uppercase tracking-wide text-zinc-400 font-medium">Heap</p>
          <p className="text-xl font-semibold text-zinc-900 mt-1 tabular-nums">
            {metrics.jvm?.heapUsedPercent?.toFixed(1) ?? '—'}%
          </p>
          <p className="text-xs text-zinc-500 mt-1">
            {formatBytes(metrics.jvm?.heapUsedBytes ?? 0)} / {formatBytes(metrics.jvm?.heapMaxBytes ?? 0)}
          </p>
        </div>
        <div className="rounded-lg border border-zinc-200 bg-zinc-50/50 p-3">
          <p className="text-[11px] uppercase tracking-wide text-zinc-400 font-medium">JVM threads</p>
          <p className="text-xl font-semibold text-zinc-900 mt-1 tabular-nums">
            {metrics.threads?.live ?? '—'}
          </p>
          <p className="text-xs text-zinc-500 mt-1">
            daemon {metrics.threads?.daemon ?? '—'} · peak {metrics.threads?.peak ?? '—'}
          </p>
        </div>
        <div className={`rounded-lg border p-3 ${cbOpen ? 'border-red-300 bg-red-50' : 'border-zinc-200 bg-zinc-50/50'}`}>
          <p className="text-[11px] uppercase tracking-wide text-zinc-400 font-medium">Circuit breaker</p>
          <p className={`text-xl font-semibold mt-1 ${cbOpen ? 'text-red-700' : 'text-zinc-900'}`}>
            {metrics.circuitBreaker?.state ?? '—'}
          </p>
        </div>
        <div className="rounded-lg border border-zinc-200 bg-zinc-50/50 p-3">
          <p className="text-[11px] uppercase tracking-wide text-zinc-400 font-medium">Consumers</p>
          <p className="text-xl font-semibold text-zinc-900 mt-1">
            {metrics.consumerPaused ? 'Paused' : 'Running'}
          </p>
        </div>
        <div className="rounded-lg border border-zinc-200 bg-zinc-50/50 p-3">
          <p className="text-[11px] uppercase tracking-wide text-zinc-400 font-medium">Actions cancels</p>
          <p className="text-xl font-semibold text-zinc-900 mt-1 tabular-nums">
            {Math.round(metrics.actionsCancelsTotal ?? 0)}
          </p>
        </div>
      </div>

      <div className="overflow-x-auto rounded-lg border border-zinc-200">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-[11px] uppercase tracking-wide text-zinc-400 border-b border-zinc-100 bg-zinc-50/80">
              <th className="px-3 py-2 font-medium">Executor action</th>
              <th className="px-3 py-2 font-medium">Active</th>
              <th className="px-3 py-2 font-medium">Queued</th>
              <th className="px-3 py-2 font-medium">Pool</th>
            </tr>
          </thead>
          <tbody>
            {[...(metrics.executors ?? [])]
              .sort((a, b) => poolMeta(a.name).sort - poolMeta(b.name).sort)
              .map((ex) => (
                <tr key={ex.name} className="border-b border-zinc-50 last:border-0 align-top">
                  <td className="px-3 py-2"><ActionCell meta={poolMeta(ex.name)} meterId={ex.name} /></td>
                  <td className="px-3 py-2 tabular-nums">{Math.round(ex.active)}</td>
                  <td className={`px-3 py-2 tabular-nums ${ex.queued > 0 ? 'text-amber-700 font-medium' : ''}`}>
                    {Math.round(ex.queued)}
                  </td>
                  <td className="px-3 py-2 tabular-nums">
                    {Math.round(ex.poolSize)}
                    {ex.maxPoolSize != null ? ` / ${Math.round(ex.maxPoolSize)}` : ''}
                  </td>
                </tr>
              ))}
            {(metrics.executors ?? []).length === 0 && (
              <tr>
                <td colSpan={4} className="px-3 py-4 text-center text-zinc-500">No executor meters yet on this pod.</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="overflow-x-auto rounded-lg border border-zinc-200">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-[11px] uppercase tracking-wide text-zinc-400 border-b border-zinc-100 bg-zinc-50/80">
              <th className="px-3 py-2 font-medium">Lane action</th>
              <th className="px-3 py-2 font-medium">Unacked</th>
              <th className="px-3 py-2 font-medium">Consumers</th>
              <th className="px-3 py-2 font-medium">Listener</th>
            </tr>
          </thead>
          <tbody>
            {[...(metrics.lanes ?? [])]
              .sort((a, b) => laneMeta(a.lane).sort - laneMeta(b.lane).sort)
              .map((lane) => (
                <tr key={lane.lane} className="border-b border-zinc-50 last:border-0 align-top">
                  <td className="px-3 py-2"><ActionCell meta={laneMeta(lane.lane)} meterId={lane.lane} /></td>
                  <td className="px-3 py-2 tabular-nums">{lane.unacked}</td>
                  <td className="px-3 py-2 tabular-nums">
                    {lane.activeConsumers} / {lane.configuredConsumers}
                  </td>
                  <td className="px-3 py-2">
                    <span className={`text-xs font-medium ${lane.running ? 'text-emerald-700' : 'text-amber-700'}`}>
                      {lane.running ? 'Running' : 'Stopped'}
                    </span>
                  </td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>

      {(metrics.jobOutcomes ?? []).length > 0 && (
        <div className="overflow-x-auto rounded-lg border border-zinc-200">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-[11px] uppercase tracking-wide text-zinc-400 border-b border-zinc-100 bg-zinc-50/80">
                <th className="px-3 py-2 font-medium">Job outcomes (this pod)</th>
                <th className="px-3 py-2 font-medium">Status</th>
                <th className="px-3 py-2 font-medium">Count</th>
                <th className="px-3 py-2 font-medium">Mean ms</th>
              </tr>
            </thead>
            <tbody>
              {metrics.jobOutcomes.map((row) => (
                <tr key={`${row.lane}-${row.status}`} className="border-b border-zinc-50 last:border-0">
                  <td className="px-3 py-2 font-mono text-xs">{row.lane}</td>
                  <td className="px-3 py-2">{row.status}</td>
                  <td className="px-3 py-2 tabular-nums">{Math.round(row.count)}</td>
                  <td className="px-3 py-2 tabular-nums">
                    {row.meanDurationMs != null ? row.meanDurationMs.toFixed(1) : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {(metrics.apiUsageByInstall ?? []).length > 0 && (
        <div className="overflow-x-auto rounded-lg border border-zinc-200">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-[11px] uppercase tracking-wide text-zinc-400 border-b border-zinc-100 bg-zinc-50/80">
                <th className="px-3 py-2 font-medium">Install (this pod)</th>
                <th className="px-3 py-2 font-medium">REST / min</th>
                <th className="px-3 py-2 font-medium">Quota</th>
                <th className="px-3 py-2 font-medium">429s</th>
              </tr>
            </thead>
            <tbody>
              {metrics.apiUsageByInstall!.map((row) => (
                <tr key={row.installKey} className="border-b border-zinc-50 last:border-0">
                  <td className="px-3 py-2">
                    <p className="font-medium text-zinc-900">{row.label || row.installKey}</p>
                    <p className="text-[11px] font-mono text-zinc-400">{row.installKey}</p>
                  </td>
                  <td className="px-3 py-2 tabular-nums">
                    {row.restCallsPerMinute?.toFixed?.(1) ?? row.restCallsPerMinute}
                  </td>
                  <td className="px-3 py-2 tabular-nums">
                    {row.rateLimitRemaining != null && row.rateLimitLimit != null
                      ? `${row.rateLimitRemaining} / ${row.rateLimitLimit}`
                      : '—'}
                  </td>
                  <td className="px-3 py-2 tabular-nums">{row.rateLimit429Count}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

type QuotaSeriesPoint = NonNullable<ScmQuotas['installations'][number]['series']>[number];

const RestSparkline: React.FC<{
  series?: QuotaSeriesPoint[];
  expanded?: boolean;
  suspect?: boolean;
  onToggle?: () => void;
}> = ({ series, expanded, suspect, onToggle }) => {
  const points = (series ?? [])
    .filter((s) => s.restRemaining != null)
    .map((s) => ({ at: s.at, v: s.restRemaining as number, app: s.appRestCalls }));
  if (points.length < 2) {
    return (
      <button
        type="button"
        disabled
        title="Need a few samples — keep making API calls"
        className="inline-flex items-center justify-center w-14 h-7 rounded border border-dashed border-zinc-200 text-[10px] text-zinc-400"
      >
        ···
      </button>
    );
  }
  const w = expanded ? 280 : 56;
  const h = expanded ? 72 : 28;
  const pad = 2;
  const min = Math.min(...points.map((p) => p.v));
  const max = Math.max(...points.map((p) => p.v));
  const span = Math.max(1, max - min);
  const path = points
    .map((p, i) => {
      const x = pad + (i / (points.length - 1)) * (w - pad * 2);
      const y = pad + (1 - (p.v - min) / span) * (h - pad * 2);
      return `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
  const stroke = suspect ? '#b45309' : '#3f3f46';
  return (
    <button
      type="button"
      onClick={onToggle}
      title={expanded ? 'Collapse chart' : 'Expand REST remaining chart'}
      className={`inline-flex items-center rounded border bg-zinc-50 hover:bg-zinc-100 transition-colors ${
        expanded ? 'border-zinc-300 p-1' : 'border-zinc-200 p-0.5'
      } ${suspect ? 'border-amber-300 bg-amber-50' : ''}`}
    >
      <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`} className="block" aria-hidden>
        <path d={path} fill="none" stroke={stroke} strokeWidth={expanded ? 1.5 : 1.25} strokeLinejoin="round" />
      </svg>
    </button>
  );
};

const QuotaBar: React.FC<{ remaining?: number | null; limit?: number | null; low?: boolean }> = ({
  remaining,
  limit,
  low,
}) => {
  if (remaining == null || limit == null || limit <= 0) {
    return <div className="h-1.5 rounded-full bg-zinc-100 w-full max-w-[10rem]" />;
  }
  const pct = Math.max(0, Math.min(100, (remaining / limit) * 100));
  return (
    <div className="h-1.5 rounded-full bg-zinc-100 w-full max-w-[10rem] overflow-hidden">
      <div
        className={`h-full rounded-full ${low ? 'bg-amber-500' : 'bg-zinc-700'}`}
        style={{ width: `${pct}%` }}
      />
    </div>
  );
};

type InstallRow = ScmQuotas['installations'][number];

const InstallQuotaCard: React.FC<{
  row: InstallRow;
  expanded: boolean;
  onToggleExpand: () => void;
}> = ({ row, expanded, onToggleExpand }) => {
  const suspect = (row.externalRestSuspect ?? 0) > 0;
  const restLow =
    row.restLimit != null &&
    row.restRemaining != null &&
    row.restLimit > 0 &&
    row.restRemaining / row.restLimit < 0.2;
  const gqlLow =
    row.graphqlLimit != null &&
    row.graphqlRemaining != null &&
    row.graphqlLimit > 0 &&
    row.graphqlRemaining / row.graphqlLimit < 0.2;

  const restLabel =
    row.restRemaining != null && row.restLimit != null
      ? `${row.restRemaining} / ${row.restLimit}`
      : row.restCalls > 0
        ? 'unknown'
        : '—';
  const gqlLabel =
    row.graphqlRemaining != null && row.graphqlLimit != null
      ? `${row.graphqlRemaining} / ${row.graphqlLimit}`
      : '—';

  return (
    <div className={`rounded-xl border bg-white overflow-hidden ${suspect ? 'border-amber-300' : 'border-zinc-200'}`}>
      <div className="px-4 py-3 border-b border-zinc-100 flex items-start justify-between gap-3">
        <div>
          <p className="text-sm font-semibold text-zinc-900">{row.provider}</p>
          <p className="text-xs font-mono text-zinc-500 mt-0.5">{row.installationKey}</p>
          {suspect && (
            <p className="text-[11px] text-amber-700 mt-1 font-medium">
              ~{row.externalRestSuspect} REST calls unexplained (other client or pod?)
            </p>
          )}
        </div>
        <RestSparkline
          series={row.series}
          expanded={false}
          suspect={suspect}
          onToggle={onToggleExpand}
        />
      </div>

      <div className="divide-y divide-zinc-100">
        <div className="px-4 py-3 flex flex-col sm:flex-row sm:items-center gap-2 sm:gap-6">
          <div className="sm:w-28 shrink-0">
            <p className="text-xs font-medium text-zinc-800">REST</p>
            <p className="text-[11px] text-zinc-400">requests / hour</p>
          </div>
          <div className="flex-1 min-w-0 space-y-1.5">
            <div className="flex flex-wrap items-baseline gap-x-3 gap-y-0.5">
              <span className={`text-sm tabular-nums font-medium ${restLow ? 'text-amber-700' : 'text-zinc-900'}`}>
                {restLabel}
              </span>
              <span className="text-xs tabular-nums text-zinc-500">
                {row.restCalls} observed
                {row.rest429Count > 0 && (
                  <span className="text-red-600 font-medium"> · {row.rest429Count}×429</span>
                )}
              </span>
              {row.restResetAt && (
                <span className="text-[11px] text-zinc-400">
                  reset {new Date(row.restResetAt).toLocaleTimeString()}
                </span>
              )}
            </div>
            <QuotaBar remaining={row.restRemaining} limit={row.restLimit} low={restLow} />
            {row.restRemaining == null && row.restCalls > 0 && (
              <p className="text-[11px] text-zinc-400">No rate-limit headers on observed calls yet</p>
            )}
          </div>
        </div>

        <div className="px-4 py-3 flex flex-col sm:flex-row sm:items-center gap-2 sm:gap-6">
          <div className="sm:w-28 shrink-0">
            <p className="text-xs font-medium text-zinc-800">GraphQL</p>
            <p className="text-[11px] text-zinc-400">points / hour</p>
          </div>
          <div className="flex-1 min-w-0 space-y-1.5">
            <div className="flex flex-wrap items-baseline gap-x-3 gap-y-0.5">
              <span className={`text-sm tabular-nums font-medium ${gqlLow ? 'text-amber-700' : 'text-zinc-900'}`}>
                {gqlLabel}
              </span>
              <span className="text-xs tabular-nums text-zinc-500">
                {row.graphqlCalls} calls · {row.graphqlPointsUsed} pts
                {row.graphql429Count > 0 && (
                  <span className="text-red-600 font-medium"> · {row.graphql429Count}×429</span>
                )}
              </span>
            </div>
            <QuotaBar remaining={row.graphqlRemaining} limit={row.graphqlLimit} low={gqlLow} />
          </div>
        </div>
      </div>

      {expanded && (
        <div className="px-4 py-3 border-t border-zinc-100 bg-zinc-50/80 flex flex-col sm:flex-row sm:items-start gap-4">
          <div>
            <p className="text-xs font-medium text-zinc-700 mb-1">REST remaining trend</p>
            <RestSparkline
              series={row.series}
              expanded
              suspect={suspect}
              onToggle={onToggleExpand}
            />
          </div>
          <div className="text-xs text-zinc-600 space-y-1.5 max-w-xl">
            <p>
              GitHub REST consumed in sampled window:{' '}
              <span className="font-medium tabular-nums text-zinc-900">{row.githubRestConsumed ?? 0}</span>
              {' · '}
              This JVM:{' '}
              <span className="font-medium tabular-nums text-zinc-900">{row.appRestConsumed ?? 0}</span>
            </p>
            <p className={suspect ? 'text-amber-800' : 'text-zinc-500'}>
              {suspect
                ? `About ${row.externalRestSuspect} remaining drops were not matched by this JVM — another tool or pod may be using the same App installation / token.`
                : 'No unexplained REST drop yet. If remaining falls faster than our call count, that delta shows up here.'}
            </p>
            <p className="text-zinc-400">
              {(row.series?.length ?? 0)} samples · in-memory only · resets on process restart
            </p>
          </div>
        </div>
      )}
    </div>
  );
};

export const InternalsPage: React.FC = () => {
  const { cluster, error: clusterError, loading: clusterLoading, refresh } = useClusterRuntimeMetrics(3000);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [quotas, setQuotas] = useState<ScmQuotas | null>(null);
  const [quotaError, setQuotaError] = useState<string | null>(null);
  const [quotaLoading, setQuotaLoading] = useState(false);
  const [expandedQuotaKey, setExpandedQuotaKey] = useState<string | null>(null);

  const loadQuotas = async () => {
    setQuotaLoading(true);
    try {
      const scm = await getScmQuotas();
      setQuotas(scm);
      setQuotaError(null);
    } catch (e) {
      console.error('Failed to load SCM quotas:', e);
      setQuotaError('Could not load SCM quotas');
    } finally {
      setQuotaLoading(false);
    }
  };

  useEffect(() => {
    if (!cluster) return;
    setSelectedId((prev) => {
      if (prev && cluster.instances.some((i) => i.instanceId === prev)) return prev;
      const live = cluster.instances.find((i) => !i.stale) ?? cluster.instances[0];
      return live?.instanceId ?? null;
    });
  }, [cluster]);

  useEffect(() => {
    loadQuotas();
    const timer = setInterval(loadQuotas, 3000);
    return () => clearInterval(timer);
  }, []);

  const selected = cluster?.instances.find((i) => i.instanceId === selectedId) ?? null;
  const totals = cluster?.totals;
  const error = clusterError || quotaError;
  const loading = clusterLoading || quotaLoading;

  const onRefresh = () => {
    refresh();
    void loadQuotas();
  };

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-xl font-semibold text-zinc-900 tracking-tight flex items-center gap-2">
            <Cpu className="w-5 h-5 text-zinc-700" />
            Internals
          </h1>
          <p className="text-sm text-zinc-500 mt-1 max-w-2xl">
            Cluster-wide Micrometer view across Hub pods (heartbeats), plus SCM quotas and rate
            gauges for stall triage. Job-scoped detail stays on Observability / Queues.
          </p>
        </div>
        <button
          type="button"
          onClick={onRefresh}
          disabled={loading}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg border border-zinc-200 bg-white text-zinc-700 hover:bg-zinc-50 disabled:opacity-50"
        >
          <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
          Refresh
        </button>
      </div>

      {error && (
        <div className="flex items-center gap-2 text-sm text-amber-800 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
          <AlertTriangle className="w-4 h-4 shrink-0" />
          {error}
        </div>
      )}

      <ClusterFleetStrip cluster={cluster} showInstallUsage />

      {!cluster ? (
        <p className="text-sm text-zinc-500">{loading ? 'Loading…' : 'No metrics yet.'}</p>
      ) : (
        <>
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3">
            <div className="rounded-xl border border-zinc-200 bg-white p-3">
              <p className="text-[11px] uppercase tracking-wide text-zinc-400 font-medium">Pods</p>
              <p className="text-xl font-semibold text-zinc-900 mt-1 tabular-nums">
                {cluster.liveInstanceCount}/{cluster.instanceCount}
              </p>
              <p className="text-xs text-zinc-500">live / known</p>
            </div>
            <div className="rounded-xl border border-zinc-200 bg-white p-3">
              <p className="text-[11px] uppercase tracking-wide text-zinc-400 font-medium">Threads live</p>
              <p className="text-xl font-semibold text-zinc-900 mt-1 tabular-nums">
                {totals?.threadsLive ?? 0}
              </p>
            </div>
            <div className="rounded-xl border border-zinc-200 bg-white p-3">
              <p className="text-[11px] uppercase tracking-wide text-zinc-400 font-medium">Exec active</p>
              <p className="text-xl font-semibold text-zinc-900 mt-1 tabular-nums">
                {Math.round(totals?.executorActive ?? 0)}
              </p>
            </div>
            <div className="rounded-xl border border-zinc-200 bg-white p-3">
              <p className="text-[11px] uppercase tracking-wide text-zinc-400 font-medium">Exec queued</p>
              <p className={`text-xl font-semibold mt-1 tabular-nums ${(totals?.executorQueued ?? 0) > 0 ? 'text-amber-700' : 'text-zinc-900'}`}>
                {Math.round(totals?.executorQueued ?? 0)}
              </p>
            </div>
            <div className="rounded-xl border border-zinc-200 bg-white p-3">
              <p className="text-[11px] uppercase tracking-wide text-zinc-400 font-medium">Lane unacked</p>
              <p className="text-xl font-semibold text-zinc-900 mt-1 tabular-nums">
                {totals?.laneUnacked ?? 0}
              </p>
            </div>
            <div className="rounded-xl border border-zinc-200 bg-white p-3">
              <p className="text-[11px] uppercase tracking-wide text-zinc-400 font-medium">CB open</p>
              <p className={`text-xl font-semibold mt-1 tabular-nums ${(totals?.openCircuitInstances ?? 0) > 0 ? 'text-red-700' : 'text-zinc-900'}`}>
                {totals?.openCircuitInstances ?? 0}
              </p>
            </div>
          </div>

          <section className="space-y-3">
            <div className="flex flex-col gap-0.5 px-0.5">
              <div className="flex items-center gap-2">
                <Gauge className="w-4 h-4 text-zinc-500" />
                <h2 className="text-sm font-semibold text-zinc-900">SCM quotas (App / token)</h2>
              </div>
              <p className="text-xs text-zinc-500 pl-6 max-w-2xl">
                One card per installation (this process). REST and GraphQL are separate GitHub limit buckets —
                sparkline tracks REST remaining; expand for external-usage delta.
              </p>
            </div>
            {!quotas || quotas.installations.length === 0 ? (
              <div className="rounded-xl border border-zinc-200 bg-white px-4 py-6 text-sm text-zinc-500 text-center">
                No SCM traffic observed yet — run a sync or Refresh Diff to populate quotas.
              </div>
            ) : (
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
                {quotas.installations.map((row) => {
                  const key = `${row.provider}|${row.installationKey}`;
                  return (
                    <InstallQuotaCard
                      key={key}
                      row={row}
                      expanded={expandedQuotaKey === key}
                      onToggleExpand={() => setExpandedQuotaKey(expandedQuotaKey === key ? null : key)}
                    />
                  );
                })}
              </div>
            )}
          </section>

          <section className="rounded-xl border border-zinc-200 bg-white overflow-hidden">
            <div className="px-4 py-3 border-b border-zinc-100 flex flex-col gap-0.5">
              <div className="flex items-center gap-2">
                <GitBranch className="w-4 h-4 text-zinc-500" />
                <h2 className="text-sm font-semibold text-zinc-900">Hottest repos (Git / GraphQL)</h2>
              </div>
              <p className="text-xs text-zinc-500 pl-6">
                Per-repo traffic under the installs above — Git smart-HTTP plus GraphQL fan-out for this process.
              </p>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-[11px] uppercase tracking-wide text-zinc-400 border-b border-zinc-100">
                    <th className="px-4 py-2 font-medium">Repo</th>
                    <th className="px-4 py-2 font-medium">Git fetch / push</th>
                    <th className="px-4 py-2 font-medium">Git throttles</th>
                    <th className="px-4 py-2 font-medium">GraphQL</th>
                    <th className="px-4 py-2 font-medium">Heat</th>
                  </tr>
                </thead>
                <tbody>
                  {!quotas || quotas.hottestRepos.length === 0 ? (
                    <tr>
                      <td colSpan={5} className="px-4 py-6 text-zinc-500 text-center">
                        No per-repo Git or GraphQL activity yet.
                      </td>
                    </tr>
                  ) : (
                    quotas.hottestRepos.map((row) => (
                      <tr key={`${row.provider}|${row.repoFullName}`} className="border-b border-zinc-50 last:border-0">
                        <td className="px-4 py-2.5">
                          <p className="font-medium text-zinc-900">{row.repoFullName}</p>
                          <p className="text-xs text-zinc-500 mt-0.5">{row.provider}</p>
                        </td>
                        <td className="px-4 py-2.5 tabular-nums text-zinc-700">
                          {row.gitFetches} / {row.gitPushes}
                        </td>
                        <td className={`px-4 py-2.5 tabular-nums ${row.gitThrottles > 0 ? 'text-amber-700 font-medium' : 'text-zinc-700'}`}>
                          {row.gitThrottles}
                        </td>
                        <td className="px-4 py-2.5 tabular-nums text-zinc-700">
                          {row.graphqlCalls} · {row.graphqlPointsUsed} pts
                          {row.graphql429Count > 0 && (
                            <span className="text-red-600 font-medium"> · {row.graphql429Count}×429</span>
                          )}
                        </td>
                        <td className="px-4 py-2.5 tabular-nums font-medium text-zinc-800">{row.heatScore}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </section>

          <section className="rounded-xl border border-zinc-200 bg-white overflow-hidden">
            <div className="px-4 py-3 border-b border-zinc-100 flex items-center gap-2">
              <Layers className="w-4 h-4 text-zinc-500" />
              <h2 className="text-sm font-semibold text-zinc-900">Hub instances</h2>
            </div>
            <div className="flex flex-wrap gap-2 p-3 border-b border-zinc-100">
              {cluster.instances.length === 0 ? (
                <p className="text-sm text-zinc-500 px-1">No heartbeats yet — wait a few seconds after startup.</p>
              ) : (
                cluster.instances.map((inst) => (
                  <button
                    key={inst.instanceId}
                    type="button"
                    onClick={() => setSelectedId(inst.instanceId)}
                    className={`px-3 py-1.5 rounded-lg text-xs font-medium border transition-colors ${
                      selectedId === inst.instanceId
                        ? 'bg-zinc-900 text-white border-zinc-900'
                        : 'bg-white text-zinc-700 border-zinc-200 hover:bg-zinc-50'
                    }`}
                  >
                    <span className="font-mono">{inst.instanceId}</span>
                    {!inst.stale && inst.metrics?.threads != null && (
                      <span className="ml-2 opacity-80">{inst.metrics.threads.live} thr</span>
                    )}
                    {inst.stale && <span className="ml-2 text-amber-600">stale</span>}
                  </button>
                ))
              )}
            </div>
            <div className="p-4">
              {!selected ? (
                <p className="text-sm text-zinc-500">Select a pod to inspect.</p>
              ) : selected.stale ? (
                <div className="flex items-center gap-2 text-sm text-amber-800">
                  <AlertTriangle className="w-4 h-4" />
                  Heartbeat stale
                  {selected.updatedAt ? ` (last ${new Date(selected.updatedAt).toLocaleTimeString()})` : ''}.
                  Pod may be down or partitioned.
                </div>
              ) : selected.metrics ? (
                <>
                  <div className="flex items-center gap-2 mb-3 text-xs text-zinc-500">
                    <Activity className="w-3.5 h-3.5" />
                    <span className="font-mono text-zinc-700">{selected.instanceId}</span>
                    <span>· captured {selected.updatedAt ? new Date(selected.updatedAt).toLocaleTimeString() : '—'}</span>
                  </div>
                  <InstanceDetail metrics={selected.metrics} />
                </>
              ) : (
                <p className="text-sm text-zinc-500">No payload for this instance.</p>
              )}
            </div>
          </section>
        </>
      )}
    </div>
  );
};
