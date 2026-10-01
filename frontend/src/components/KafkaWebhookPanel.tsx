import React, { useEffect, useRef, useState } from 'react';
import {
  getWebhookBus,
  redriveWebhookBus,
  seekWebhookBusOffsets,
  KafkaOffsetSeekMode,
  WebhookBusStatus,
} from '../services/api';

const POLL_MS = 60_000;
const HISTORY = 36;

/**
 * Incremental Kafka consumer group. The page polls about once a minute.
 * Refresh asks the broker immediately. The admin client stays open and closes after five quiet minutes.
 */
export const KafkaWebhookPanel: React.FC = () => {
  const [status, setStatus] = useState<WebhookBusStatus | null>(null);
  const [history, setHistory] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  const [seeking, setSeeking] = useState(false);
  const [rewindBy, setRewindBy] = useState(50);
  const [refreshing, setRefreshing] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const historyRef = useRef<number[]>([]);
  const inFlight = useRef(false);

  const load = (fresh = false) => {
    if (inFlight.current) return;
    inFlight.current = true;
    if (fresh) setRefreshing(true);
    getWebhookBus(fresh)
      .then((next) => {
        setStatus(next);
        if (typeof next.pending === 'number') {
          const points = [...historyRef.current, next.pending].slice(-HISTORY);
          historyRef.current = points;
          setHistory(points);
        }
      })
      .catch(() => setNote('Status request failed'))
      .finally(() => {
        inFlight.current = false;
        setRefreshing(false);
      });
  };

  useEffect(() => {
    load();
    const timer = window.setInterval(load, POLL_MS);
    return () => window.clearInterval(timer);
  }, []);

  if (!status) {
    return <p className="text-xs text-zinc-500">Loading Kafka status…</p>;
  }

  if (status.provider !== 'kafka') {
    return (
      <p className="text-xs text-zinc-500">
        The incremental webhook bus is {status.provider}. This page is for <span className="font-mono">GIT_WEBHOOK_BUS_PROVIDER=kafka</span>.
      </p>
    );
  }

  const pending = status.pending ?? status.lag;
  const processed = status.processed;
  const failures = status.storedFailures ?? [];
  const failureCount = status.storedFailureCount ?? failures.length;
  const partitions = status.partitions ?? [];
  const maxPending = partitions.reduce((max, part) => Math.max(max, part.pending), 0);

  const replay = async () => {
    setBusy(true);
    setNote(null);
    try {
      const result = await redriveWebhookBus(10);
      setNote(`Replayed ${result.redriven} stored failure${result.redriven === 1 ? '' : 's'}.`);
      load(true);
    } catch (err) {
      setNote(err instanceof Error ? err.message : 'Replay failed');
    } finally {
      setBusy(false);
    }
  };

  const seek = async (mode: KafkaOffsetSeekMode) => {
    if (mode === 'earliest' && !window.confirm('Reset this consumer group to the earliest offset on every partition? Hub will re-read retained topic history.')) {
      return;
    }
    if (mode === 'latest' && !window.confirm('Skip to the latest offset on every partition? Pending records for this group will be skipped.')) {
      return;
    }
    setSeeking(true);
    setNote(null);
    try {
      const result = await seekWebhookBusOffsets({
        mode,
        rewindBy: mode === 'rewind' ? Math.max(1, Math.min(rewindBy || 50, 10_000)) : undefined,
      });
      const count = result.offsets?.length ?? 0;
      setNote(
        mode === 'rewind'
          ? `Rewound ${result.rewindBy ?? rewindBy} on ${count} partition${count === 1 ? '' : 's'}.`
          : `Seeked to ${mode} on ${count} partition${count === 1 ? '' : 's'}.`
      );
      load(true);
    } catch (err) {
      const axiosErr = err as { response?: { data?: { error?: string } }; message?: string };
      setNote(axiosErr.response?.data?.error || axiosErr.message || 'Offset seek failed');
    } finally {
      setSeeking(false);
    }
  };

  const stateLabel = status.paused
    ? 'Listener paused'
    : status.lagError
      ? 'Broker unreachable'
      : status.groupState === 'EMPTY'
        ? 'Group empty'
        : 'Consuming';

  return (
    <div className="space-y-4">
      <div className="flex flex-col sm:flex-row sm:items-start justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold text-zinc-900">Incremental topic</h3>
          <p className="text-xs text-zinc-500 mt-0.5">
            {status.sourceId ? (
              <>
                source <span className="font-mono text-zinc-700">{status.sourceId}</span>
                {' · '}
              </>
            ) : null}
            <span className="font-mono text-zinc-700">{status.topic}</span>
            {' · group '}
            <span className="font-mono text-zinc-700">{status.groupId || 'git-mirror-hub'}</span>
            {status.groupState ? ` · ${status.groupState}` : ''}
            {status.memberCount != null ? ` · ${status.memberCount} member${status.memberCount === 1 ? '' : 's'}` : ''}
            {status.partitionCount != null ? ` · ${status.partitionCount} partitions` : ''}
          </p>
          {status.bootstrapServers ? (
            <p className="text-[11px] text-zinc-400 mt-0.5 font-mono truncate" title={status.bootstrapServers}>
              {status.bootstrapServers}
            </p>
          ) : null}
          <p className="text-[11px] text-zinc-400 mt-1">
            Refreshes about once a minute. Pending is uncommitted records. Committed includes skips and stored failures.
            {status.sampledAt ? ` Sampled ${status.sampledAt}.` : ''}
            {status.stale ? ' Showing the last successful sample.' : ''}
          </p>
        </div>
        <div className="flex items-center gap-2 self-start">
          <button
            type="button"
            onClick={() => load(true)}
            disabled={refreshing}
            className="px-3 py-1 rounded-lg border border-zinc-200 text-xs font-medium hover:bg-zinc-50 disabled:opacity-50"
          >
            {refreshing ? 'Refreshing…' : 'Refresh'}
          </button>
        <span className={`inline-flex items-center space-x-1.5 px-2 py-0.5 rounded-full text-[11px] font-medium ${
          status.paused
            ? 'bg-amber-50 text-amber-700 border border-amber-200'
            : status.lagError
              ? 'bg-rose-50 text-rose-700 border border-rose-200'
              : 'bg-emerald-50 text-emerald-700 border border-emerald-200'
        }`}>
          <span className={`w-1.5 h-1.5 rounded-full ${
            status.paused ? 'bg-amber-500' : status.lagError ? 'bg-rose-500' : 'bg-emerald-500'
          }`} />
          <span>{stateLabel}</span>
        </span>
        </div>
      </div>

      {status.lagError && (
        <div className="rounded-xl border border-rose-200 bg-rose-50 p-3 text-xs text-rose-800">
          {status.lagError}
        </div>
      )}

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        <Metric label="Active" value={pending} hint="Not committed" emphasize={pending != null && pending > 0} />
        <Metric label="Completed" value={processed} hint="Committed by this group" />
        <Metric label="Dead-letter" value={failureCount} hint="Stored, not applied" emphasize={failureCount > 0} danger />
        <Metric label="Partitions" value={status.partitionCount ?? partitions.length} hint="Configured topic" />
      </div>

      <div className="rounded-2xl border border-zinc-200/90 bg-white shadow-sm p-4">
        <div className="flex items-baseline justify-between gap-3">
          <div className="text-xs font-semibold text-zinc-900">Active records</div>
          <div className="text-[11px] text-zinc-400">Last {Math.max(history.length, 1)} samples · 1 min apart</div>
        </div>
        <ActiveChart values={history} />
      </div>

      <div className="rounded-2xl border border-zinc-200/90 bg-white shadow-sm p-4 space-y-3">
        <div>
          <div className="text-xs font-semibold text-zinc-900">Replay from Kafka</div>
          <p className="text-[11px] text-zinc-500 mt-0.5">
            Moves this consumer group&apos;s committed offsets so Hub re-reads topic records.
            Stop other Hub pods on the same group first. Distinct from replaying stored dead-letter rows below.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <label className="flex items-center gap-1.5 text-xs text-zinc-600">
            Rewind
            <input
              type="number"
              min={1}
              max={10000}
              value={rewindBy}
              onChange={(e) => setRewindBy(Number(e.target.value) || 50)}
              className="w-20 rounded-md border border-zinc-200 px-2 py-1 font-mono text-xs"
            />
          </label>
          <button
            type="button"
            onClick={() => seek('rewind')}
            disabled={seeking || !!status.lagError}
            className="px-3 py-1.5 rounded-lg border border-zinc-200 text-xs font-medium hover:bg-zinc-50 disabled:opacity-50"
          >
            {seeking ? 'Seeking…' : 'Rewind'}
          </button>
          <button
            type="button"
            onClick={() => seek('earliest')}
            disabled={seeking || !!status.lagError}
            className="px-3 py-1.5 rounded-lg border border-zinc-200 text-xs font-medium hover:bg-zinc-50 disabled:opacity-50"
          >
            To earliest
          </button>
          <button
            type="button"
            onClick={() => seek('latest')}
            disabled={seeking || !!status.lagError}
            className="px-3 py-1.5 rounded-lg border border-zinc-200 text-xs font-medium hover:bg-zinc-50 disabled:opacity-50"
          >
            To latest
          </button>
        </div>
        {note && <p className="text-[11px] text-zinc-500">{note}</p>}
      </div>

      <div className="rounded-2xl border border-zinc-200/90 bg-white shadow-sm overflow-hidden">
        <div className="px-4 py-3 border-b border-zinc-100 text-xs font-semibold text-zinc-900">
          Partition depth
        </div>
        {partitions.length === 0 ? (
          <p className="px-4 py-4 text-xs text-zinc-500">No partition offsets yet.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead className="text-zinc-500">
                <tr className="border-b border-zinc-100">
                  <th className="text-left font-medium px-4 py-2">Partition</th>
                  <th className="text-left font-medium px-4 py-2 w-[34%]">Active</th>
                  <th className="text-right font-medium px-4 py-2">Pending</th>
                  <th className="text-right font-medium px-4 py-2">Committed</th>
                  <th className="text-right font-medium px-4 py-2">End</th>
                </tr>
              </thead>
              <tbody>
                {partitions.map((part) => {
                  const width = maxPending > 0 ? Math.max(4, (part.pending / maxPending) * 100) : 0;
                  return (
                    <tr key={part.partition} className="border-b border-zinc-50 last:border-0">
                      <td className="px-4 py-2 font-mono text-zinc-800">{part.partition}</td>
                      <td className="px-4 py-2">
                        <div className="h-1.5 rounded-full bg-zinc-100 overflow-hidden">
                          <div
                            className={`h-1.5 rounded-full ${part.pending > 0 ? 'bg-amber-500' : 'bg-transparent'}`}
                            style={{ width: part.pending > 0 ? `${width}%` : '0%' }}
                          />
                        </div>
                      </td>
                      <td className={`px-4 py-2 text-right font-mono ${part.pending > 0 ? 'text-amber-700' : 'text-zinc-600'}`}>
                        {part.pending}
                      </td>
                      <td className="px-4 py-2 text-right font-mono text-zinc-600">
                        {part.committed == null ? '—' : part.committed}
                      </td>
                      <td className="px-4 py-2 text-right font-mono text-zinc-600">{part.end}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="rounded-2xl border border-zinc-200/90 bg-white shadow-sm">
        <div className="px-4 py-3 border-b border-zinc-100 flex items-center justify-between gap-3">
          <div>
            <div className="text-xs font-semibold text-zinc-900">Dead-letter</div>
            <p className="text-[11px] text-zinc-500 mt-0.5">
              Rows Hub could not apply. They stay in the unmapped webhook store until replay.
            </p>
          </div>
          <button
            type="button"
            onClick={replay}
            disabled={busy || failureCount === 0}
            className="px-3 py-1.5 rounded-lg border border-zinc-200 text-xs font-medium hover:bg-zinc-50 disabled:opacity-50 shrink-0"
          >
            {busy ? 'Replaying…' : 'Replay stored failures'}
          </button>
        </div>
        {failures.length === 0 ? (
          <p className="px-4 py-4 text-xs text-zinc-500">No stored failures.</p>
        ) : (
          <ul className="divide-y divide-zinc-100">
            {failures.map((row) => (
              <li key={row.id} className="px-4 py-3 text-xs">
                <div className="font-medium text-zinc-900">
                  {row.repoFullName || row.repoUrl || 'Unknown repo'}
                  {row.branch ? ` · ${row.branch}` : ''}
                </div>
                <p className="text-zinc-600 mt-0.5">{row.details || row.eventType || 'Failure'}</p>
                <p className="text-[11px] text-zinc-400 mt-0.5 font-mono">
                  {row.commitSha ? `${row.commitSha.slice(0, 10)} · ` : ''}
                  {row.receivedAt || ''}
                </p>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
};

const Metric: React.FC<{
  label: string;
  value: number | null | undefined;
  hint: string;
  emphasize?: boolean;
  danger?: boolean;
}> = ({ label, value, hint, emphasize, danger }) => (
  <div className={`rounded-2xl border bg-white p-4 shadow-sm space-y-1 ${
    danger && emphasize ? 'border-rose-200' : 'border-zinc-200/90'
  }`}>
    <div className="text-[11px] uppercase tracking-wide text-zinc-400 font-medium">{label}</div>
    <div className={`text-2xl font-bold tabular-nums ${
      danger && emphasize ? 'text-rose-700' : emphasize ? 'text-amber-700' : 'text-zinc-900'
    }`}>
      {value ?? '—'}
    </div>
    <p className="text-[11px] text-zinc-400">{hint}</p>
  </div>
);

const ActiveChart: React.FC<{ values: number[] }> = ({ values }) => {
  if (values.length < 2) {
    return <p className="text-xs text-zinc-400 mt-3">Collecting samples…</p>;
  }
  const width = 560;
  const height = 72;
  const max = Math.max(...values, 1);
  const min = Math.min(...values);
  const span = Math.max(1, max - min);
  const points = values.map((value, index) => {
    const x = (index / (values.length - 1)) * width;
    const y = height - ((value - min) / span) * (height - 8) - 4;
    return `${x},${y}`;
  }).join(' ');
  return (
    <div className="mt-2">
      <svg viewBox={`0 0 ${width} ${height}`} className="w-full h-16 text-amber-600" role="img" aria-label="Active record count">
        <polyline fill="none" stroke="currentColor" strokeWidth="2" points={points} />
      </svg>
      <div className="flex justify-between text-[11px] text-zinc-400 tabular-nums">
        <span>{min}</span>
        <span>now {values[values.length - 1]}</span>
        <span>{max}</span>
      </div>
    </div>
  );
};
