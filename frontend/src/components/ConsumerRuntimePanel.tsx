import React from 'react';
import { ConsumerLaneStatus, CurrentWork } from '../types';

interface ConsumerRuntimePanelProps {
  lanes?: ConsumerLaneStatus[];
  compact?: boolean;
}

const formatElapsed = (ms?: number) => {
  if (ms == null) return '';
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  const minutes = Math.floor(ms / 60_000);
  const seconds = Math.floor((ms % 60_000) / 1000);
  return `${minutes}m ${seconds}s`;
};

const workLabel = (work: CurrentWork) => {
  const job = work.jobId != null ? `job #${work.jobId}` : 'message';
  const pair = work.pairName ? ` · ${work.pairName}` : '';
  const ref = work.ref ? ` · ${work.ref}` : '';
  return `${job}${pair}${ref}`;
};

export const ConsumerRuntimePanel: React.FC<ConsumerRuntimePanelProps> = ({ lanes, compact }) => {
  const rows = lanes && lanes.length > 0 ? lanes : [];
  if (rows.length === 0) {
    return (
      <div className="rounded-2xl border border-zinc-200/90 bg-white p-5 text-xs text-zinc-400">
        Consumer runtime not available yet.
      </div>
    );
  }

  return (
    <div className={`grid gap-4 ${compact ? 'grid-cols-1 md:grid-cols-3' : 'grid-cols-1 lg:grid-cols-3'}`}>
      {rows.map((lane) => {
        const status = lane.dead ? 'Dead' : lane.paused ? 'Paused' : lane.running ? 'Active' : 'Stopped';
        const statusClass = lane.dead
          ? 'bg-rose-50 text-rose-700 border-rose-200'
          : lane.paused
            ? 'bg-amber-50 text-amber-700 border-amber-200'
            : lane.running
              ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
              : 'bg-zinc-100 text-zinc-600 border-zinc-200';
        return (
          <div key={lane.lane} className="rounded-2xl border border-zinc-200/90 bg-white p-5 shadow-sm space-y-3">
            <div className="flex items-start justify-between gap-2">
              <div>
                <div className="text-xs font-semibold text-zinc-900">{lane.label}</div>
                <p className="text-[10px] text-zinc-400 font-mono truncate">{lane.queueName}</p>
              </div>
              <span className={`text-[11px] px-2 py-0.5 rounded-full border font-medium ${statusClass}`}>{status}</span>
            </div>
            <div className="grid grid-cols-2 gap-2">
              <div>
                <div className="text-[10px] uppercase tracking-wide text-zinc-400">Ready (pending)</div>
                <div className={`text-xl font-bold ${lane.readyCount > 0 ? 'text-amber-800' : 'text-zinc-900'}`}>
                  {lane.readyCount}
                </div>
              </div>
              <div>
                <div className="text-[10px] uppercase tracking-wide text-zinc-400">Unacked (in-flight)</div>
                <div className={`text-xl font-bold ${lane.unackedCount > 0 ? 'text-blue-800' : 'text-zinc-900'}`}>
                  {lane.unackedCount}
                </div>
              </div>
            </div>
            <div className="text-[11px] text-zinc-600">
              Threads {lane.activeConsumers}/{lane.configuredConcurrency}
              {lane.maxConcurrency > lane.configuredConcurrency ? ` (max ${lane.maxConcurrency})` : ''}
              {lane.idleThreads > 0 ? ` · ${lane.idleThreads} idle` : ''}
              {lane.unusedSlots > 0 ? ` · ${lane.unusedSlots} unused` : ''}
              {lane.dead ? ' · listener dead' : ''}
            </div>
            <div className="space-y-1">
              {lane.currentWork.length === 0 ? (
                <p className="text-[11px] text-zinc-400">Idle — no unacked message</p>
              ) : (
                lane.currentWork.map((work, idx) => (
                  <div key={`${work.threadName}-${idx}`} className="text-[11px] text-zinc-700">
                    <span className="font-medium">
                      {work.state === 'SKIPPING' ? 'Skipping' : 'Processing'} {workLabel(work)}
                    </span>
                    <div className="text-zinc-400 font-mono truncate">
                      {work.threadName}
                      {work.threadAlive === false ? ' (dead thread)' : ''}
                      {work.elapsedMs != null ? ` · ${formatElapsed(work.elapsedMs)}` : ''}
                    </div>
                  </div>
                ))
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
};
