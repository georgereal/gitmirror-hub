import React from 'react';
import { SyncJob, SyncPipeline } from '../types';
import { formatBytes, formatDuration } from '../utils/format';
import { resolveJobElapsedMs } from '../utils/jobTiming';
import { pipelineStageLabel } from './SyncPipelineStepper';

interface JobExecutionSummaryProps {
  job: SyncJob;
  pipeline?: SyncPipeline | null;
  elapsedMs?: number | null;
}

function Row({ label, value, title }: { label: string; value: React.ReactNode; title?: string }) {
  return (
    <div className="flex justify-between gap-2 py-0.5 border-b border-zinc-100 last:border-0">
      <span className="text-zinc-400 font-sans shrink-0">{label}</span>
      <span className="font-semibold text-zinc-800 truncate max-w-[180px] text-right" title={title}>
        {value}
      </span>
    </div>
  );
}

export const JobExecutionSummary: React.FC<JobExecutionSummaryProps> = ({
  job,
  pipeline,
  elapsedMs,
}) => {
  const duration = elapsedMs ?? resolveJobElapsedMs(job, Date.now(), null);
  const gitRead = job.gitReadBytes ?? 0;
  const gitWrite = job.gitWriteBytes ?? 0;
  const lfsBytes = job.lfsBytes ?? 0;
  const totalBytes = job.bytesTransferred ?? (gitRead + gitWrite + lfsBytes);
  const lfsSynced = job.lfsSyncedCount;
  const lfsDiscovered = job.lfsObjectsCount;
  const stages = pipeline?.stages ?? [];

  return (
    <div className="p-3.5 bg-white border border-zinc-200 rounded-xl space-y-2.5 text-xs">
      <div className="font-semibold text-zinc-800 flex items-center justify-between">
        <span>Execution Summary</span>
        <span className="text-[10px] font-mono text-zinc-400">ID #{job.id}</span>
      </div>

      <div className="space-y-1.5 font-mono text-[11px] text-zinc-600">
        <Row label="Mapping" value={job.pairName} title={job.pairName} />
        <Row
          label="Direction"
          value={`${hostLabel(job.sourceRepo)} → ${hostLabel(job.targetRepo)}`}
          title={`${job.sourceRepo} → ${job.targetRepo}`}
        />
        <Row label="Branch ref" value={job.branch || '*'} />
        <Row
          label="Trigger"
          value={(job.triggerType || 'MANUAL').toLowerCase().replace(/_/g, ' ')}
        />
        {job.attemptCount != null && (
          <Row label="Attempt" value={`${job.attemptCount}/${job.maxAttempts ?? 3}`} />
        )}
        {job.sourceAccessMode && (
          <Row
            label="Source access"
            value={job.sourceAccessMode === 'PUBLIC' ? 'Public HTTPS' : job.sourceAccessMode}
          />
        )}
        <Row
          label="Started"
          value={job.startedAt
            ? new Date(job.startedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
            : '—'}
        />
        {job.completedAt && (
          <Row
            label="Completed"
            value={new Date(job.completedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
          />
        )}
        {duration != null && <Row label="Duration" value={formatDuration(duration)} />}
      </div>

      <div className="grid grid-cols-2 gap-1.5">
        <Metric label="Git download" value={gitRead > 0 ? formatBytes(gitRead) : '—'} />
        <Metric label="Git upload" value={gitWrite > 0 ? formatBytes(gitWrite) : '—'} />
        <Metric label="LFS bytes" value={lfsBytes > 0 ? formatBytes(lfsBytes) : '—'} />
        <Metric label="Total transferred" value={totalBytes > 0 ? formatBytes(totalBytes) : '—'} />
        <Metric label="Git objects" value={job.objectsReceived ? job.objectsReceived.toLocaleString() : '—'} />
        <Metric
          label="LFS blobs"
          value={
            lfsDiscovered
              ? lfsSynced != null
                ? `${lfsSynced}/${lfsDiscovered}`
                : String(lfsDiscovered)
              : '—'
          }
        />
        <Metric label="Branches" value={job.branchesCount != null ? String(job.branchesCount) : '—'} />
        <Metric label="Tags" value={job.tagsCount != null ? String(job.tagsCount) : '—'} />
        <Metric label="PRs" value={job.prsSyncedCount != null ? String(job.prsSyncedCount) : '—'} />
        <Metric label="Releases" value={job.releasesCount != null ? String(job.releasesCount) : '—'} />
      </div>

      {stages.length > 0 && (
        <div className="pt-1 border-t border-zinc-100">
          <div className="text-[10px] uppercase font-bold text-zinc-500 mb-1">Stage timings</div>
          <ol className="space-y-0.5">
            {stages.map((stage) => (
              <li key={stage.id} className="flex items-center justify-between gap-2 text-[10px] font-mono text-zinc-600">
                <span className="truncate text-zinc-700">
                  {pipelineStageLabel(stage.id, pipeline)}
                  {stage.status === 'skipped' ? ' · skipped' : ''}
                  {stage.status === 'failed' ? ' · failed' : ''}
                </span>
                <span className="tabular-nums text-zinc-400 shrink-0">
                  {stage.durationMs != null ? formatDuration(stage.durationMs) : stage.status === 'pending' ? '—' : ''}
                </span>
              </li>
            ))}
          </ol>
        </div>
      )}
    </div>
  );
};

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="p-1.5 rounded-lg bg-zinc-50 border border-zinc-100">
      <div className="text-[9px] uppercase font-bold text-zinc-400 tracking-wide">{label}</div>
      <div className="font-mono text-[11px] font-semibold text-zinc-800 mt-0.5">{value}</div>
    </div>
  );
}

function hostLabel(url?: string): string {
  if (!url) return '—';
  try {
    const u = new URL(url);
    const path = u.pathname.replace(/\.git$/, '').replace(/^\//, '');
    return path || u.host;
  } catch {
    return url;
  }
}
