import React, { useEffect, useState } from 'react';
import { Check, Circle, AlertCircle, Minus, SkipForward } from 'lucide-react';
import { JobProgress, SyncJob, SyncPipeline, PipelineStage, SyncStatus } from '../types';
import { formatDuration } from '../utils/format';

interface SyncPipelineStepperProps {
  pipeline?: SyncPipeline | null;
  compact?: boolean;
  allowSkip?: boolean;
  skippingStageId?: string | null;
  onSkipStage?: (stageId: string) => void | Promise<void>;
}

export const PIPELINE_STAGE_LABELS: Record<string, string> = {
  prepare: 'Prepare local mirror',
  compare_branches: 'Compare branches & refs',
  fast_path: 'Fast-path check',
  verify_dest: 'Verify destination write',
  fetch_source: 'Fetch source refs and objects',
  inspect_dest: 'Inspect destination refs',
  conflict_check: 'Conflict / fast-forward check',
  push_dest: 'Push to destination',
  lfs: 'Git LFS',
  pr_metadata: 'PR metadata',
  releases: 'Releases / CI',
};

export function pipelineStageLabel(stageId?: string | null, pipeline?: SyncPipeline | null): string {
  if (!stageId) return 'Unknown';
  const fromPipeline = pipeline?.stages?.find((s) => s.id === stageId)?.label;
  return fromPipeline || PIPELINE_STAGE_LABELS[stageId] || stageId;
}

export function canSkipJobStages(status?: SyncStatus | string | null): boolean {
  return status === 'PAUSED'
    || status === 'INTERRUPTED'
    || status === 'FAILED'
    || status === 'DEAD_LETTERED'
    || status === 'QUEUED';
}

function isSkippableStage(status: PipelineStage['status']): boolean {
  return status === 'pending' || status === 'current' || status === 'failed';
}

function parsePipelineJson(raw?: string | null): SyncPipeline | null {
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as SyncPipeline;
    if (!parsed || !Array.isArray(parsed.stages)) return null;
    return parsed;
  } catch {
    return null;
  }
}

const STAGE_RANK: Record<PipelineStage['status'], number> = {
  pending: 0,
  current: 1,
  skipped: 2,
  done: 3,
  failed: 4,
};

function isTerminalJob(job?: SyncJob | null): boolean {
  return !!job?.status && job.status !== 'IN_PROGRESS' && job.status !== 'QUEUED';
}

export function isLiveSyncStatus(status?: string | null): boolean {
  return status === 'IN_PROGRESS' || status === 'QUEUED';
}

function mergePipelines(persisted: SyncPipeline | null, live: SyncPipeline): SyncPipeline {
  if (!persisted?.stages?.length) {
    return live;
  }
  const byId = new Map(persisted.stages.map((stage) => [stage.id, stage]));
  const stages = live.stages.map((liveStage) => {
    const saved = byId.get(liveStage.id);
    if (!saved) {
      return liveStage;
    }
    return (STAGE_RANK[saved.status] ?? 0) >= (STAGE_RANK[liveStage.status] ?? 0)
      ? { ...liveStage, ...saved }
      : liveStage;
  });
  const currentStageId =
    stages.find((stage) => stage.status === 'current' || stage.status === 'failed')?.id
    ?? persisted.currentStageId
    ?? live.currentStageId;
  return { currentStageId, stages };
}

export function pipelineFromJobAndProgress(job?: SyncJob | null, progress?: JobProgress | null): SyncPipeline | null {
  const persisted = parsePipelineJson(job?.pipelineJson);
  const live = progress?.pipeline?.stages?.length ? progress.pipeline : null;
  if (isTerminalJob(job) || !live) {
    return persisted ?? live;
  }
  return mergePipelines(persisted, live);
}

function StageIcon({ status }: { status: PipelineStage['status'] }) {
  if (status === 'done') {
    return (
      <div className="w-4 h-4 rounded-full bg-emerald-100 border border-emerald-300 flex items-center justify-center text-emerald-700">
        <Check className="w-2.5 h-2.5 stroke-[3]" />
      </div>
    );
  }
  if (status === 'failed') {
    return (
      <div className="w-4 h-4 rounded-full bg-rose-100 border border-rose-300 flex items-center justify-center text-rose-700">
        <AlertCircle className="w-2.5 h-2.5 stroke-[3]" />
      </div>
    );
  }
  if (status === 'skipped') {
    return (
      <div className="w-4 h-4 rounded-full bg-zinc-100 border border-zinc-200 flex items-center justify-center text-zinc-400">
        <Minus className="w-2.5 h-2.5" />
      </div>
    );
  }
  if (status === 'current') {
    return (
      <div className="w-4 h-4 rounded-full bg-blue-100 border border-blue-400 flex items-center justify-center text-blue-600 relative">
        <span className="w-2 h-2 rounded-full bg-blue-600 animate-ping absolute inset-0 m-auto" />
        <span className="w-1.5 h-1.5 rounded-full bg-blue-600 relative z-10" />
      </div>
    );
  }
  return (
    <div className="w-4 h-4 rounded-full bg-zinc-50 border border-zinc-200 flex items-center justify-center">
      <Circle className="w-1.5 h-1.5 text-zinc-300 fill-zinc-300" />
    </div>
  );
}

function stageElapsedMs(stage: PipelineStage, nowMs: number): number | null {
  if (stage.durationMs != null && stage.durationMs >= 0) {
    return stage.durationMs;
  }
  if (stage.status === 'current' && stage.startedAtMs != null) {
    return Math.max(0, nowMs - stage.startedAtMs);
  }
  return null;
}

function StageTiming({ stage, nowMs }: { stage: PipelineStage; nowMs: number }) {
  const elapsed = stageElapsedMs(stage, nowMs);
  if (elapsed == null) return null;
  const isLive = stage.status === 'current';
  return (
    <span
      className={`text-[9px] font-mono shrink-0 tabular-nums ${
        isLive ? 'text-blue-600' : 'text-zinc-400'
      }`}
    >
      {formatDuration(elapsed)}
    </span>
  );
}

export const SyncPipelineStepper: React.FC<SyncPipelineStepperProps> = ({
  pipeline,
  compact,
  allowSkip = false,
  skippingStageId = null,
  onSkipStage,
}) => {
  const [now, setNow] = useState(Date.now());
  const hasLiveStage = pipeline?.stages?.some((s) => s.status === 'current') ?? false;

  useEffect(() => {
    if (!hasLiveStage) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [hasLiveStage]);

  if (!pipeline?.stages?.length) return null;

  if (compact) {
    const current = pipeline.stages.find((s) => s.status === 'current')
      || pipeline.stages.find((s) => s.status === 'failed')
      || pipeline.stages.find((s) => s.id === pipeline.currentStageId);
    if (!current) return null;
    return (
      <div className="text-[10px] font-medium text-blue-700 truncate" title={current.detail || current.label}>
        {current.label}{current.detail ? ` · ${current.detail}` : ''}
      </div>
    );
  }

  return (
    <div className="p-3.5 bg-white border border-zinc-200 rounded-xl space-y-2.5 shadow-2xs">
      <div className="flex items-center justify-between text-xs font-bold text-zinc-800">
        <span>Execution Pipeline</span>
        <span className="text-[10px] font-mono text-zinc-400 font-normal">
          {pipeline.stages.filter((s) => s.status === 'done' || s.status === 'skipped').length}/{pipeline.stages.length} complete
        </span>
      </div>

      <ol className="space-y-2 relative">
        {pipeline.stages.map((stage, idx) => {
          const isCurrent = stage.status === 'current';
          const isFailed = stage.status === 'failed';
          const isDone = stage.status === 'done';

          return (
            <li
              key={stage.id}
              className={`flex items-start space-x-2.5 text-[11px] p-1.5 -mx-1.5 rounded-lg transition-colors ${
                isCurrent
                  ? 'bg-blue-50/80 border border-blue-100'
                  : isFailed
                  ? 'bg-rose-50/80 border border-rose-100'
                  : ''
              }`}
            >
              <div className="mt-0.5 shrink-0">
                <StageIcon status={stage.status} />
              </div>
              <div className="min-w-0 flex-1">
                <div className="flex items-center justify-between gap-1">
                  <span
                    className={`font-semibold ${
                      isFailed
                        ? 'text-rose-800'
                        : isCurrent
                        ? 'text-blue-900 font-bold'
                        : isDone
                        ? 'text-zinc-800'
                        : 'text-zinc-500'
                    }`}
                  >
                    {stage.label}
                  </span>
                  <div className="flex items-center gap-1.5 shrink-0">
                    <StageTiming stage={stage} nowMs={now} />
                    {allowSkip && isSkippableStage(stage.status) && onSkipStage && (
                      <button
                        type="button"
                        title={`Skip "${stage.label}" and advance resume cursor`}
                        disabled={skippingStageId === stage.id}
                        onClick={() => {
                          void onSkipStage(stage.id);
                        }}
                        className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded text-[9px] font-semibold uppercase tracking-wide border border-amber-200 bg-amber-50 text-amber-800 hover:bg-amber-100 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                      >
                        <SkipForward className="w-2.5 h-2.5" />
                        <span>{skippingStageId === stage.id ? '…' : 'Skip'}</span>
                      </button>
                    )}
                    {stage.status === 'skipped' && (
                      <span className="text-[9px] font-mono uppercase px-1 rounded bg-zinc-100 text-zinc-500">
                        Skipped
                      </span>
                    )}
                  </div>
                </div>

                {stage.detail && (
                  <div
                    className={`font-mono text-[10px] truncate mt-0.5 ${
                      isFailed
                        ? 'text-rose-700 font-medium'
                        : isCurrent
                        ? 'text-blue-700 font-medium'
                        : 'text-zinc-500'
                    }`}
                    title={stage.detail}
                  >
                    {stage.detail}
                  </div>
                )}
              </div>
            </li>
          );
        })}
      </ol>
    </div>
  );
};
