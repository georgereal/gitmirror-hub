import React from 'react';
import { Activity } from 'lucide-react';
import { ProviderTraffic } from '../types';

interface ProviderTrafficStripProps {
  traffic?: ProviderTraffic | null;
  compact?: boolean;
  pushBatchDetail?: string | null;
}

function num(value?: number | null): string {
  if (value == null || Number.isNaN(value)) return '—';
  return Number.isInteger(value) ? value.toLocaleString() : value.toFixed(1);
}

function maxNum(a?: number | null, b?: number | null): number {
  const av = a ?? 0;
  const bv = b ?? 0;
  return Math.max(av, bv);
}

export const ProviderTrafficStrip: React.FC<ProviderTrafficStripProps> = ({
  traffic,
  compact,
  pushBatchDetail,
}) => {
  const rest = traffic?.restCallCount ?? 0;
  const rate = traffic?.restCallsPerMinute ?? 0;
  const fetches = traffic?.gitHttpFetchCount ?? 0;
  const batches = traffic?.gitHttpPushBatchCount ?? 0;
  const pushPerMin = traffic?.gitPushPerMinute ?? 0;
  const pushGuideline = traffic?.gitPushRateGuideline ?? 6;
  const gitThrottles = traffic?.gitHttpThrottleCount ?? 0;
  const remaining = traffic?.rateLimitRemaining;
  const limit = traffic?.rateLimitLimit;
  const tooMany = traffic?.rateLimit429Count ?? 0;
  const provider = traffic?.provider;

  const pushRateHot = pushPerMin >= pushGuideline || gitThrottles > 0;
  const pushRateWarn = !pushRateHot && pushPerMin >= Math.max(1, pushGuideline - 1);

  const quota =
    remaining != null && limit != null && limit > 0
      ? `${remaining.toLocaleString()} / ${limit.toLocaleString()} REST quota`
      : remaining != null
        ? `${remaining.toLocaleString()} REST remaining`
        : null;

  if (compact) {
    return (
      <span className="font-mono text-[10px] text-zinc-500" title="REST auth/metadata vs Git packfile transfers">
        REST {rest} · Git {batches} push{batches === 1 ? '' : 'es'}
      </span>
    );
  }

  return (
    <div className="p-3.5 bg-white border border-zinc-200 rounded-xl space-y-2.5 shadow-2xs">
      <div className="flex items-center justify-between text-xs font-bold text-zinc-800">
        <div className="flex items-center space-x-1.5">
          <Activity className="w-3.5 h-3.5 text-zinc-500" />
          <span>Provider Traffic</span>
        </div>
        {provider && (
          <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-zinc-100 text-zinc-600 uppercase font-semibold">
            {provider}
          </span>
        )}
      </div>

      <div className="grid grid-cols-1 gap-2 text-[11px]">
        <div className="p-2.5 rounded-lg bg-zinc-50 border border-zinc-100 space-y-0.5">
          <div className="flex items-center justify-between">
            <span className="text-[10px] uppercase font-bold text-zinc-500 font-sans tracking-wide">
              REST API (auth &amp; metadata)
            </span>
            <span className="font-mono font-bold text-zinc-800">
              {rest.toLocaleString()} call{rest === 1 ? '' : 's'}
            </span>
          </div>
          <div className="text-zinc-500 font-mono text-[10px] flex items-center justify-between">
            <span>{num(rate)} calls/min</span>
            {tooMany > 0 && (
              <span className="text-rose-600 font-semibold">{tooMany}× 429</span>
            )}
          </div>
          <div className="text-[10px] text-zinc-400">Tokens, repo checks, PR/LFS APIs — not packfile transfers</div>
          {quota && (
            <div className="text-zinc-400 font-mono text-[10px] truncate pt-0.5 border-t border-zinc-200/50 mt-1">
              {quota}
            </div>
          )}
        </div>

        <div className="p-2.5 rounded-lg bg-zinc-50 border border-zinc-100 space-y-0.5">
          <div className="flex items-center justify-between">
            <span className="text-[10px] uppercase font-bold text-zinc-500 font-sans tracking-wide">
              Git packfile transfers
            </span>
            <span className="font-mono text-zinc-700 font-medium">
              {fetches} fetch{fetches === 1 ? '' : 'es'} · {batches} push{batches === 1 ? '' : 'es'}
            </span>
          </div>
          {pushBatchDetail && (
            <div className="text-[10px] font-mono text-blue-700 truncate">{pushBatchDetail}</div>
          )}
          <div
            className={`text-[10px] font-mono flex items-center justify-between ${
              pushRateHot ? 'text-rose-600' : pushRateWarn ? 'text-amber-600' : 'text-zinc-500'
            }`}
          >
            <span>
              Push rate: {num(pushPerMin)} / {pushGuideline} min (guideline)
            </span>
            {gitThrottles > 0 && <span>{gitThrottles}× throttled</span>}
          </div>
          <div className="text-[10px] text-zinc-400">JGit Smart HTTP — separate from REST quota</div>
        </div>
      </div>
    </div>
  );
};

export function trafficFromJobAndProgress(
  job?: {
    restCallCount?: number;
    restCallsPerMinute?: number;
    gitHttpFetchCount?: number;
    gitHttpPushBatchCount?: number;
    gitPushPerMinute?: number;
    gitFetchPerMinute?: number;
    gitHttpThrottleCount?: number;
    rateLimitRemaining?: number;
    rateLimitLimit?: number;
    rateLimit429Count?: number;
    providerTrafficProvider?: string;
  } | null,
  live?: ProviderTraffic | null
): ProviderTraffic {
  const merged: ProviderTraffic = {
    restCallCount: maxNum(live?.restCallCount, job?.restCallCount),
    restCallsPerMinute: Math.max(live?.restCallsPerMinute ?? 0, job?.restCallsPerMinute ?? 0),
    gitHttpFetchCount: maxNum(live?.gitHttpFetchCount, job?.gitHttpFetchCount),
    gitHttpPushBatchCount: maxNum(live?.gitHttpPushBatchCount, job?.gitHttpPushBatchCount),
    gitPushPerMinute: Math.max(live?.gitPushPerMinute ?? 0, job?.gitPushPerMinute ?? 0),
    gitFetchPerMinute: Math.max(live?.gitFetchPerMinute ?? 0, job?.gitFetchPerMinute ?? 0),
    gitHttpThrottleCount: maxNum(live?.gitHttpThrottleCount, job?.gitHttpThrottleCount),
    gitPushRateGuideline: live?.gitPushRateGuideline ?? 6,
    rateLimitRemaining: live?.rateLimitRemaining ?? job?.rateLimitRemaining,
    rateLimitLimit: live?.rateLimitLimit ?? job?.rateLimitLimit,
    rateLimit429Count: maxNum(live?.rateLimit429Count, job?.rateLimit429Count),
    provider: live?.provider ?? job?.providerTrafficProvider,
  };
  return merged;
}
