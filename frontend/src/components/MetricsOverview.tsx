import React from 'react';
import { GitBranch, CheckCircle2, Inbox, AlertTriangle } from 'lucide-react';
import { DashboardStats, QueueStatus } from '../types';

interface MetricsOverviewProps {
  stats: DashboardStats | null;
  queueStatus: QueueStatus | null;
}

export const MetricsOverview: React.FC<MetricsOverviewProps> = ({ stats, queueStatus }) => {
  const activePairs = stats?.activePairs ?? 0;
  const totalSyncs24h = stats?.totalSyncs24h ?? 0;
  const successRate = stats?.successRate ?? 100;
  const readyCount = (queueStatus?.mainQueueMessageCount ?? 0) + (queueStatus?.incrementalQueueMessageCount ?? 0);
  const unackedCount = (queueStatus?.mainQueueUnackedCount ?? 0) + (queueStatus?.incrementalQueueUnackedCount ?? 0);
  const processing = (queueStatus?.consumers || [])
    .flatMap((lane) => lane.currentWork || [])
    .find((work) => work.state === 'PROCESSING');
  const dlqCount = queueStatus?.dlqMessageCount ?? (stats?.deadLetterCount ?? 0);

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
      {/* Active Pairs */}
      <div className="bg-slate-900 border border-slate-800/80 rounded-xl p-4 shadow-sm relative overflow-hidden">
        <div className="flex items-center justify-between">
          <span className="text-xs font-medium text-slate-400">Active Mirror Pairs</span>
          <div className="p-2 bg-blue-500/10 text-blue-400 rounded-lg">
            <GitBranch className="w-4 h-4" />
          </div>
        </div>
        <div className="mt-2 flex items-baseline space-x-2">
          <span className="text-2xl font-bold tracking-tight text-white">{activePairs}</span>
          <span className="text-xs text-slate-400">Configured</span>
        </div>
        <div className="mt-2 text-xs text-slate-500 flex items-center space-x-1">
          <span className="text-emerald-400">● 100% active</span>
        </div>
      </div>

      {/* 24h Total Syncs & Success Rate */}
      <div className="bg-slate-900 border border-slate-800/80 rounded-xl p-4 shadow-sm relative overflow-hidden">
        <div className="flex items-center justify-between">
          <span className="text-xs font-medium text-slate-400">Syncs (Last 24h)</span>
          <div className="p-2 bg-emerald-500/10 text-emerald-400 rounded-lg">
            <CheckCircle2 className="w-4 h-4" />
          </div>
        </div>
        <div className="mt-2 flex items-baseline space-x-2">
          <span className="text-2xl font-bold tracking-tight text-white">{totalSyncs24h}</span>
          <span className="text-xs text-emerald-400 font-medium">({successRate}% success)</span>
        </div>
        <div className="mt-2 text-xs text-slate-500">
          {stats?.successSyncs24h ?? 0} successful completions
        </div>
      </div>

      {/* Main Queue */}
      <div className="bg-slate-900 border border-slate-800/80 rounded-xl p-4 shadow-sm relative overflow-hidden">
        <div className="flex items-center justify-between">
          <span className="text-xs font-medium text-slate-400">Ready / in-flight</span>
          <div className="p-2 bg-purple-500/10 text-purple-400 rounded-lg">
            <Inbox className="w-4 h-4" />
          </div>
        </div>
        <div className="mt-2 flex items-baseline space-x-2">
          <span className="text-2xl font-bold tracking-tight text-white">{readyCount}</span>
          <span className="text-xs text-slate-400">ready · {unackedCount} unacked</span>
        </div>
        <div className="mt-2 text-xs text-slate-500 flex items-center space-x-1.5">
          <span className={`w-1.5 h-1.5 rounded-full ${
            queueStatus?.consumerRunning === false && !queueStatus?.consumerPaused
              ? 'bg-rose-400'
              : queueStatus?.consumerPaused
                ? 'bg-amber-400'
                : 'bg-emerald-400'
          }`} />
          <span className={
            queueStatus?.consumerRunning === false && !queueStatus?.consumerPaused
              ? 'text-rose-400'
              : queueStatus?.consumerPaused
                ? 'text-amber-400'
                : 'text-slate-400'
          }>
            {queueStatus?.consumerRunning === false && !queueStatus?.consumerPaused
              ? 'Consumer stopped'
              : queueStatus?.consumerPaused
                ? 'Consumer paused (buffering)'
                : processing?.jobId != null
                  ? `Processing job #${processing.jobId}`
                  : 'Consumers idle'}
          </span>
        </div>
      </div>

      {/* DLQ Count */}
      <div className={`bg-slate-900 border rounded-xl p-4 shadow-sm relative overflow-hidden transition-colors ${
        dlqCount > 0 ? 'border-rose-500/40 bg-rose-950/10' : 'border-slate-800/80'
      }`}>
        <div className="flex items-center justify-between">
          <span className="text-xs font-medium text-slate-400">Dead Letter Queue (DLQ)</span>
          <div className={`p-2 rounded-lg ${dlqCount > 0 ? 'bg-rose-500/20 text-rose-400' : 'bg-slate-800 text-slate-400'}`}>
            <AlertTriangle className="w-4 h-4" />
          </div>
        </div>
        <div className="mt-2 flex items-baseline space-x-2">
          <span className={`text-2xl font-bold tracking-tight ${dlqCount > 0 ? 'text-rose-400' : 'text-white'}`}>
            {dlqCount}
          </span>
          <span className="text-xs text-slate-400">Failed / Retried</span>
        </div>
        <div className="mt-2 text-xs text-slate-500">
          {dlqCount > 0 ? (
            <span className="text-rose-400 font-medium">Needs Attention / Replay</span>
          ) : (
            <span className="text-emerald-400">No poisoned messages</span>
          )}
        </div>
      </div>
    </div>
  );
};
