import React from 'react';
import { JobProgress } from '../types';
import { formatDuration } from '../utils/format';
import { formatEta } from '../utils/liveJobLogs';

interface JobProgressBarProps {
  progress?: JobProgress | null;
  compact?: boolean;
  elapsedMs?: number | null;
}

export const JobProgressBar: React.FC<JobProgressBarProps> = ({ progress, compact, elapsedMs }) => {
  if (!progress && (elapsedMs == null || elapsedMs < 0)) return null;

  const knownTotal = !!progress && progress.total > 0;
  const percent = knownTotal
    ? Math.min(100, progress.percent ?? Math.round((100 * progress.current) / progress.total))
    : 0;
  const label = progress?.message
    || (progress
      ? [progress.phase, knownTotal ? `${progress.current.toLocaleString()}/${progress.total.toLocaleString()} objects` : progress.current > 0 ? `${progress.current.toLocaleString()} objects` : null]
          .filter(Boolean)
          .join(' · ')
      : '');
  const eta = formatEta(progress?.etaMs);
  const elapsed = elapsedMs != null && elapsedMs >= 0 ? formatDuration(elapsedMs) : '';
  const timing = [elapsed ? `elapsed ${elapsed}` : '', eta].filter(Boolean).join(' · ');

  return (
    <div className={compact ? 'mt-1.5 min-w-[9rem]' : 'mt-2'}>
      {!compact && (label || timing) && (
        <div className="text-[10px] font-mono text-blue-700 mb-1 truncate" title={[label, timing].filter(Boolean).join(' · ')}>
          {label}{timing ? ` · ${timing}` : ''}
        </div>
      )}
      <div className={`w-full rounded-full bg-blue-100 overflow-hidden ${compact ? 'h-1.5' : 'h-2'}`}>
        {knownTotal ? (
          <div
            className="h-full rounded-full bg-blue-600 transition-all duration-300"
            style={{ width: `${percent}%` }}
          />
        ) : (
          <div className="h-full w-1/3 rounded-full bg-blue-500 animate-pulse" />
        )}
      </div>
      {compact && (label || timing) && (
        <div className="text-[10px] font-mono text-blue-600 mt-0.5 truncate" title={[label, timing].filter(Boolean).join(' · ')}>
          {knownTotal ? `${percent}% · ${label}` : label}
          {timing ? ` · ${timing}` : ''}
        </div>
      )}
    </div>
  );
};
