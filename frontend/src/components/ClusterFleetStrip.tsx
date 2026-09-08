import React from 'react';
import { Link } from 'react-router';
import { Activity, Server } from 'lucide-react';
import { ClusterRuntimeMetrics, InstallApiUsage } from '../types';

type Props = {
  cluster: ClusterRuntimeMetrics | null;
  compact?: boolean;
  showInstallUsage?: boolean;
};

const quotaLabel = (row: InstallApiUsage) => {
  if (row.rateLimitRemaining == null || row.rateLimitLimit == null) return '—';
  return `${row.rateLimitRemaining} / ${row.rateLimitLimit}`;
};

export const ClusterFleetStrip: React.FC<Props> = ({
  cluster,
  compact = false,
  showInstallUsage = true,
}) => {
  if (!cluster) {
    return (
      <div className="rounded-xl border border-dashed border-zinc-200 bg-zinc-50/60 px-4 py-3 text-sm text-zinc-500">
        Waiting for cluster heartbeats…
      </div>
    );
  }

  const installs = cluster.apiUsageByInstall ?? [];

  return (
    <div className="space-y-3">
      <div className={`rounded-xl border border-zinc-200 bg-white ${compact ? 'px-3 py-2.5' : 'p-4'}`}>
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
          <div className="flex items-center gap-2 min-w-0">
            <Server className="w-4 h-4 text-zinc-500 shrink-0" />
            <div>
              <p className="text-[11px] uppercase tracking-wide text-zinc-400 font-medium">Hub pods</p>
              <p className="text-sm font-semibold text-zinc-900 tabular-nums">
                {cluster.liveInstanceCount}
                <span className="text-zinc-400 font-normal"> / {cluster.instanceCount} known</span>
              </p>
            </div>
          </div>
          <div className="h-8 w-px bg-zinc-100 hidden sm:block" />
          <div>
            <p className="text-[11px] uppercase tracking-wide text-zinc-400 font-medium">Threads (live)</p>
            <p className="text-sm font-semibold text-zinc-900 tabular-nums">
              {cluster.totals?.threadsLive ?? '—'}
              <span className="text-zinc-400 font-normal text-xs ml-1">
                daemon {cluster.totals?.threadsDaemon ?? '—'}
              </span>
            </p>
          </div>
          <div className="flex flex-wrap gap-1.5 flex-1 min-w-[12rem]">
            {cluster.instances.map((inst) => (
              <span
                key={inst.instanceId}
                className={`inline-flex items-center gap-1 px-2 py-1 rounded-md text-[11px] font-mono border ${
                  inst.stale
                    ? 'border-amber-200 bg-amber-50 text-amber-800'
                    : 'border-emerald-200 bg-emerald-50 text-emerald-800'
                }`}
                title={inst.stale ? 'Stale heartbeat' : 'Live'}
              >
                <span className={`w-1.5 h-1.5 rounded-full ${inst.stale ? 'bg-amber-500' : 'bg-emerald-500'}`} />
                {inst.instanceId}
                {!inst.stale && inst.metrics?.threads != null && (
                  <span className="text-zinc-500 font-sans">· {inst.metrics.threads.live} thr</span>
                )}
              </span>
            ))}
          </div>
          {!compact && (
            <Link
              to="/observability/internals"
              className="text-xs font-medium text-zinc-600 hover:text-zinc-900 inline-flex items-center gap-1"
            >
              <Activity className="w-3.5 h-3.5" />
              Internals
            </Link>
          )}
        </div>
      </div>

      {showInstallUsage && (
        <div className="rounded-xl border border-zinc-200 bg-white overflow-hidden">
          <div className="px-4 py-2.5 border-b border-zinc-100">
            <h3 className="text-sm font-semibold text-zinc-900">External API usage by install</h3>
            <p className="text-xs text-zinc-500 mt-0.5">
              Fleet roll-up across live pods (shared GitHub App quotas use the freshest remaining).
            </p>
          </div>
          {installs.length === 0 ? (
            <p className="px-4 py-3 text-sm text-zinc-500">
              No install keys yet — configure a GitHub App install id or run REST traffic.
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-[11px] uppercase tracking-wide text-zinc-400 border-b border-zinc-100 bg-zinc-50/80">
                    <th className="px-3 py-2 font-medium">Install</th>
                    <th className="px-3 py-2 font-medium">REST / min</th>
                    <th className="px-3 py-2 font-medium">Quota left</th>
                    <th className="px-3 py-2 font-medium">429s</th>
                    <th className="px-3 py-2 font-medium">Pods</th>
                  </tr>
                </thead>
                <tbody>
                  {installs.map((row) => (
                    <tr key={row.installKey} className="border-b border-zinc-50 last:border-0">
                      <td className="px-3 py-2">
                        <p className="font-medium text-zinc-900">{row.label || row.installKey}</p>
                        <p className="text-[11px] font-mono text-zinc-400 mt-0.5">{row.installKey}</p>
                      </td>
                      <td className="px-3 py-2 tabular-nums">
                        {row.restCallsPerMinute?.toFixed?.(1) ?? row.restCallsPerMinute}
                        <span className="text-zinc-400 text-xs ml-1">({row.restCallCount} total)</span>
                      </td>
                      <td className="px-3 py-2 tabular-nums">{quotaLabel(row)}</td>
                      <td className={`px-3 py-2 tabular-nums ${row.rateLimit429Count > 0 ? 'text-amber-700 font-medium' : ''}`}>
                        {row.rateLimit429Count}
                      </td>
                      <td className="px-3 py-2 text-xs text-zinc-600">
                        {(row.reportingInstances ?? []).join(', ') || '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </div>
  );
};
