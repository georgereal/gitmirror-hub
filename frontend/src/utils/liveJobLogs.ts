import { JobProgress, SyncAuditLog } from '../types';
import { formatDuration } from './format';

const PROGRESS_LINE = /objects\s*\(\d+%\)|:\s*[\d,]+\s*objects|Source fetch|Destination push|Fetch:|Push:/i;
const STALE_ZERO_PERCENT = /:(\s*)(\d+)\/(\d+)\s+objects\s+\(0%\)/;

/** Rewrite completed-phase audit rows that were persisted at 0% before endTask logging. */
export function finalizeStaleProgressLines(logs: SyncAuditLog[]): SyncAuditLog[] {
  const progressIndexes: number[] = [];
  logs.forEach((log, index) => {
    if (PROGRESS_LINE.test(log.message)) {
      progressIndexes.push(index);
    }
  });
  if (progressIndexes.length <= 1) {
    return logs;
  }
  const next = logs.slice();
  for (let i = 0; i < progressIndexes.length - 1; i++) {
    const idx = progressIndexes[i];
    const message = next[idx].message;
    if (STALE_ZERO_PERCENT.test(message)) {
      next[idx] = {
        ...next[idx],
        message: message.replace(STALE_ZERO_PERCENT, (_match, space, _current, total) =>
          `:${space}${total}/${total} objects (100%)`
        ),
      };
    }
  }
  return next;
}

const REF_OK_LINE = /^Ref refs\/\S+ -> refs\/\S+ \[(OK|UP_TO_DATE)\]/;

/** Collapse per-ref OK spam; keep batch summaries, errors, and phase changes. */
export function prepareAuditDisplayLogs(logs: SyncAuditLog[], compactRefOk = true): SyncAuditLog[] {
  if (!compactRefOk || logs.length === 0) {
    return logs;
  }
  const out: SyncAuditLog[] = [];
  let refOkRun = 0;
  let refOkAnchor: SyncAuditLog | null = null;

  const flushRefOkRun = () => {
    if (refOkRun <= 0 || !refOkAnchor) {
      refOkRun = 0;
      refOkAnchor = null;
      return;
    }
    if (refOkRun === 1) {
      out.push(refOkAnchor);
    } else {
      out.push({
        ...refOkAnchor,
        message: `↳ ${refOkRun} refs pushed OK`,
      });
    }
    refOkRun = 0;
    refOkAnchor = null;
  };

  for (const log of logs) {
    if (REF_OK_LINE.test(log.message)) {
      refOkRun += 1;
      if (!refOkAnchor) {
        refOkAnchor = log;
      }
      continue;
    }
    flushRefOkRun();
    out.push(log);
  }
  flushRefOkRun();
  return out;
}

export function overlayLiveProgress(
  logs: SyncAuditLog[],
  progress?: JobProgress | null,
  extraSuffix?: string
): SyncAuditLog[] {
  if (!progress?.message) {
    return prepareAuditDisplayLogs(finalizeStaleProgressLines(logs));
  }
  const liveMessage = extraSuffix ? `${progress.message} · ${extraSuffix}` : progress.message;
  const progressIndexes: number[] = [];
  logs.forEach((log, index) => {
    if (PROGRESS_LINE.test(log.message)) {
      progressIndexes.push(index);
    }
  });

  let next = logs.slice();
  if (progressIndexes.length > 0) {
    const lastIdx = progressIndexes[progressIndexes.length - 1];
    next[lastIdx] = { ...next[lastIdx], message: liveMessage };
    for (let i = 0; i < progressIndexes.length - 1; i++) {
      const idx = progressIndexes[i];
      const message = next[idx].message;
      if (STALE_ZERO_PERCENT.test(message)) {
        next[idx] = {
          ...next[idx],
          message: message.replace(STALE_ZERO_PERCENT, (_match, space, _current, total) =>
            `:${space}${total}/${total} objects (100%)`
          ),
        };
      }
    }
    return prepareAuditDisplayLogs(next);
  }

  return prepareAuditDisplayLogs([
    ...next,
    {
      id: -1,
      jobId: progress.jobId,
      level: 'INFO',
      message: liveMessage,
      timestamp: new Date().toISOString(),
    },
  ]);
}

export function formatEta(etaMs?: number | null): string {
  if (etaMs == null || etaMs < 0 || Number.isNaN(etaMs)) {
    return '';
  }
  const formatted = formatDuration(etaMs);
  return formatted ? `ETA ${formatted}` : '';
}

export function parseRejectedRefs(blob?: string | null): string[] {
  if (!blob) return [];
  return blob
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
}
