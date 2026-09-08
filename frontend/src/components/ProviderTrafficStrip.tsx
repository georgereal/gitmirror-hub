import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Activity, Maximize2, ZoomIn, ZoomOut, X } from 'lucide-react';
import { JobTrafficSample, JobTrafficSeries, ProviderTraffic, SyncJob } from '../types';

interface ProviderTrafficStripProps {
  traffic?: ProviderTraffic | null;
  compact?: boolean;
  pushBatchDetail?: string | null;
}

type ChartMode = 'cumulative' | 'rate';

interface ChartPoint {
  t: number;
  rest: number;
  graphql: number;
  lfs: number;
  git: number;
}

const SERIES_META = [
  { key: 'rest' as const, label: 'REST', stroke: '#3f3f46', swatch: 'bg-zinc-700' },
  { key: 'graphql' as const, label: 'GraphQL', stroke: '#7c3aed', swatch: 'bg-violet-600' },
  { key: 'lfs' as const, label: 'LFS HTTP', stroke: '#059669', swatch: 'bg-emerald-600' },
  { key: 'git' as const, label: 'Git fetch+push', stroke: '#2563eb', swatch: 'bg-blue-600' },
];

function num(value?: number | null): string {
  if (value == null || Number.isNaN(value)) return '—';
  return Number.isInteger(value) ? value.toLocaleString() : value.toFixed(1);
}

function maxNum(a?: number | null, b?: number | null): number {
  const av = a ?? 0;
  const bv = b ?? 0;
  return Math.max(av, bv);
}

function formatElapsed(ms: number): string {
  const totalSec = Math.max(0, Math.round(ms / 1000));
  if (totalSec < 60) return `${totalSec}s`;
  const m = Math.floor(totalSec / 60);
  const s = totalSec % 60;
  if (m < 60) return s ? `${m}m ${s}s` : `${m}m`;
  const h = Math.floor(m / 60);
  return `${h}h ${m % 60}m`;
}

function formatAxisCount(value: number): string {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (value >= 10_000) return `${Math.round(value / 1000)}k`;
  if (value >= 1000) return `${(value / 1000).toFixed(1)}k`;
  if (Number.isInteger(value) || Math.abs(value - Math.round(value)) < 0.05) {
    return Math.round(value).toLocaleString();
  }
  return value.toFixed(1);
}

/** Nice tick values from 0..maxInclusive. */
function niceTicks(maxInclusive: number, tickCount = 4): number[] {
  const max = Math.max(1, maxInclusive);
  const rough = max / Math.max(1, tickCount - 1);
  const pow = 10 ** Math.floor(Math.log10(rough));
  const candidates = [1, 2, 2.5, 5, 10].map((m) => m * pow);
  const step = candidates.find((c) => c >= rough) ?? candidates[candidates.length - 1];
  const top = Math.ceil(max / step) * step;
  const ticks: number[] = [];
  for (let v = 0; v <= top + step * 0.001; v += step) {
    ticks.push(Number(v.toPrecision(12)));
  }
  return ticks;
}

function sampleToPoint(s: JobTrafficSample): ChartPoint {
  return {
    t: s.t,
    rest: s.rest ?? 0,
    graphql: s.graphql ?? 0,
    lfs: (s.lfsApi ?? 0) + (s.lfsHttp ?? 0),
    git: (s.gitFetch ?? 0) + (s.gitPush ?? 0),
  };
}

function toCumulativePoints(samples: JobTrafficSample[]): ChartPoint[] {
  return samples.map(sampleToPoint);
}

/** Calls per minute in each sampling interval (time-slice rate). */
function toRatePoints(samples: JobTrafficSample[]): ChartPoint[] {
  const points: ChartPoint[] = [];
  for (let i = 1; i < samples.length; i++) {
    const prev = sampleToPoint(samples[i - 1]);
    const cur = sampleToPoint(samples[i]);
    const dtMin = Math.max((cur.t - prev.t) / 60_000, 1 / 60);
    points.push({
      t: cur.t,
      rest: Math.max(0, cur.rest - prev.rest) / dtMin,
      graphql: Math.max(0, cur.graphql - prev.graphql) / dtMin,
      lfs: Math.max(0, cur.lfs - prev.lfs) / dtMin,
      git: Math.max(0, cur.git - prev.git) / dtMin,
    });
  }
  return points;
}

function parseSeries(raw?: string | JobTrafficSeries | null): JobTrafficSeries | null {
  if (!raw) return null;
  if (typeof raw !== 'string') {
    return Array.isArray(raw.samples) ? raw : null;
  }
  try {
    const parsed = JSON.parse(raw) as JobTrafficSeries;
    if (!parsed || !Array.isArray(parsed.samples)) return null;
    return parsed;
  } catch {
    return null;
  }
}

function mergeSeries(
  a?: JobTrafficSeries | null,
  b?: JobTrafficSeries | null
): JobTrafficSeries | null {
  const aLen = a?.samples?.length ?? 0;
  const bLen = b?.samples?.length ?? 0;
  if (bLen >= aLen) return b ?? a ?? null;
  return a ?? b ?? null;
}

/** Keep the higher cumulative totals when merging live progress updates. */
export function mergeProviderTraffic(
  prev?: ProviderTraffic | null,
  next?: ProviderTraffic | null
): ProviderTraffic | null {
  if (!next && !prev) return null;
  if (!next) return prev ?? null;
  if (!prev) return next;
  return {
    restCallCount: maxNum(prev.restCallCount, next.restCallCount),
    restCallsPerMinute: Math.max(prev.restCallsPerMinute ?? 0, next.restCallsPerMinute ?? 0),
    graphqlCallCount: maxNum(prev.graphqlCallCount, next.graphqlCallCount),
    graphqlPointsUsed: maxNum(prev.graphqlPointsUsed, next.graphqlPointsUsed),
    graphql429Count: maxNum(prev.graphql429Count, next.graphql429Count),
    lfsApiCallCount: maxNum(prev.lfsApiCallCount, next.lfsApiCallCount),
    lfsTransferHttpCount: maxNum(prev.lfsTransferHttpCount, next.lfsTransferHttpCount),
    gitHttpFetchCount: maxNum(prev.gitHttpFetchCount, next.gitHttpFetchCount),
    gitHttpPushBatchCount: maxNum(prev.gitHttpPushBatchCount, next.gitHttpPushBatchCount),
    gitPushPerMinute: Math.max(prev.gitPushPerMinute ?? 0, next.gitPushPerMinute ?? 0),
    gitFetchPerMinute: Math.max(prev.gitFetchPerMinute ?? 0, next.gitFetchPerMinute ?? 0),
    gitPushPerMinutePeak: Math.max(prev.gitPushPerMinutePeak ?? 0, next.gitPushPerMinutePeak ?? 0),
    gitFetchPerMinutePeak: Math.max(prev.gitFetchPerMinutePeak ?? 0, next.gitFetchPerMinutePeak ?? 0),
    gitHttpThrottleCount: maxNum(prev.gitHttpThrottleCount, next.gitHttpThrottleCount),
    gitPushRateGuideline: next.gitPushRateGuideline ?? prev.gitPushRateGuideline ?? 6,
    rateLimit429Count: maxNum(prev.rateLimit429Count, next.rateLimit429Count),
    gitReadBytes: maxNum(prev.gitReadBytes, next.gitReadBytes),
    gitWriteBytes: maxNum(prev.gitWriteBytes, next.gitWriteBytes),
    lfsBytes: maxNum(prev.lfsBytes, next.lfsBytes),
    provider: next.provider ?? prev.provider,
    series: mergeSeries(prev.series, next.series),
  };
}

type SeriesKey = Exclude<keyof ChartPoint, 't'>;

type SeriesVisibility = Record<SeriesKey, boolean>;

const DEFAULT_VISIBILITY: SeriesVisibility = {
  rest: true,
  graphql: true,
  lfs: true,
  git: true,
};

const MIN_VIEW_MS = 10_000;

function seriesMax(points: ChartPoint[], key: SeriesKey): number {
  if (points.length === 0) return 0;
  return Math.max(0, ...points.map((p) => p[key]));
}

function seriesEnd(points: ChartPoint[], key: SeriesKey): number {
  return points[points.length - 1]?.[key] ?? 0;
}

function clamp(n: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, n));
}

function nearestPoint(points: ChartPoint[], t: number): ChartPoint | null {
  if (points.length === 0) return null;
  let best = points[0];
  let bestDist = Math.abs(best.t - t);
  for (let i = 1; i < points.length; i++) {
    const d = Math.abs(points[i].t - t);
    if (d < bestDist) {
      best = points[i];
      bestDist = d;
    }
  }
  return best;
}

function CallVolumeSvg({
  points,
  allPoints,
  mode,
  width,
  height,
  margin,
  visible,
  viewStart,
  viewEnd,
  interactive,
}: {
  points: ChartPoint[];
  allPoints: ChartPoint[];
  mode: ChartMode;
  width: number;
  height: number;
  margin: { top: number; right: number; bottom: number; left: number };
  visible: SeriesVisibility;
  viewStart: number;
  viewEnd: number;
  interactive?: boolean;
}) {
  const svgRef = useRef<SVGSVGElement | null>(null);
  const [hover, setHover] = useState<{
    point: ChartPoint;
    clientX: number;
    clientY: number;
  } | null>(null);

  const plotW = Math.max(1, width - margin.left - margin.right);
  const plotH = Math.max(1, height - margin.top - margin.bottom);
  // Always anchor the job timeline at t=0; zoom only changes the visible window.
  const t0 = viewStart;
  const t1 = Math.max(viewEnd, viewStart + 1);
  const tSpan = Math.max(1, t1 - t0);

  const windowPoints =
    points.length === 0
      ? []
      : points.filter((p) => p.t >= t0 - 1 && p.t <= t1 + 1);
  // Include one point on each side of the window so lines don't vanish at edges.
  const drawPoints = (() => {
    if (points.length === 0) return [] as ChartPoint[];
    let lo = 0;
    let hi = points.length - 1;
    while (lo < points.length && points[lo].t < t0) lo++;
    while (hi > 0 && points[hi].t > t1) hi--;
    const from = Math.max(0, lo - 1);
    const to = Math.min(points.length - 1, hi + 1);
    return points.slice(from, to + 1);
  })();

  const scaleSource = windowPoints.length > 0 ? windowPoints : points;
  const active = SERIES_META.filter((s) => visible[s.key]);
  const scaleMax: Record<SeriesKey, number> = {
    rest: 1,
    graphql: 1,
    lfs: 1,
    git: 1,
  };
  for (const s of SERIES_META) {
    const raw = seriesMax(scaleSource, s.key);
    const ticks = niceTicks(Math.max(raw, 1e-9), 4);
    scaleMax[s.key] = ticks[ticks.length - 1] ?? Math.max(1, raw);
  }

  const ranked = [...active].sort((a, b) => scaleMax[b.key] - scaleMax[a.key]);
  const leftMeta = ranked[0] ?? null;
  const rightMeta = ranked.length > 1 ? ranked[1] : null;

  const leftTicks = leftMeta ? niceTicks(scaleMax[leftMeta.key], 5) : [0, 1];
  const rightTicks = rightMeta ? niceTicks(scaleMax[rightMeta.key], 5) : [];

  const xTicks: number[] = niceTicks(tSpan, width >= 480 ? 6 : 4)
    .map((v) => t0 + v)
    .filter((t) => t <= t1 + 1);
  if (xTicks.length === 0 || xTicks[0] !== t0) xTicks.unshift(t0);
  if (xTicks[xTicks.length - 1] < t1) xTicks.push(t1);

  const xOf = (t: number) => margin.left + ((t - t0) / tSpan) * plotW;
  const yOf = (value: number, key: SeriesKey) =>
    margin.top + (1 - value / scaleMax[key]) * plotH;

  const pathFor = (key: SeriesKey) =>
    drawPoints
      .map((p, i) => {
        const x = xOf(p.t);
        const y = yOf(p[key], key);
        return `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`;
      })
      .join(' ');

  const yLabel = mode === 'rate' ? 'calls / min' : 'calls';

  const clientToTime = (clientX: number): number | null => {
    const svg = svgRef.current;
    if (!svg) return null;
    const rect = svg.getBoundingClientRect();
    const xInSvg = ((clientX - rect.left) / rect.width) * width;
    const t = t0 + ((xInSvg - margin.left) / plotW) * tSpan;
    if (xInSvg < margin.left || xInSvg > width - margin.right) return null;
    return clamp(t, t0, t1);
  };

  const onMove = (e: React.MouseEvent<SVGSVGElement>) => {
    if (!interactive) return;
    const t = clientToTime(e.clientX);
    if (t == null) {
      setHover(null);
      return;
    }
    const point = nearestPoint(allPoints.length ? allPoints : points, t);
    if (!point) {
      setHover(null);
      return;
    }
    setHover({ point, clientX: e.clientX, clientY: e.clientY });
  };

  return (
    <>
      <svg
        ref={svgRef}
        width="100%"
        height={height}
        viewBox={`0 0 ${width} ${height}`}
        className={`block select-none ${interactive ? 'cursor-crosshair' : ''}`}
        role="img"
        aria-label="Call volume chart, multi-scale Y, zoomable time window"
        onMouseMove={onMove}
        onMouseLeave={() => setHover(null)}
      >
        {leftTicks.map((tick) => {
          const y = leftMeta ? yOf(tick, leftMeta.key) : margin.top + plotH;
          return (
            <line
              key={`yg-${tick}`}
              x1={margin.left}
              x2={width - margin.right}
              y1={y}
              y2={y}
              stroke="#e4e4e7"
              strokeWidth={1}
            />
          );
        })}
        {xTicks.map((tick) => (
          <line
            key={`xg-${tick}`}
            x1={xOf(tick)}
            x2={xOf(tick)}
            y1={margin.top}
            y2={height - margin.bottom}
            stroke="#f4f4f5"
            strokeWidth={1}
          />
        ))}

        <line
          x1={margin.left}
          x2={margin.left}
          y1={margin.top}
          y2={height - margin.bottom}
          stroke={leftMeta?.stroke ?? '#a1a1aa'}
          strokeWidth={1.25}
        />
        <line
          x1={margin.left}
          x2={width - margin.right}
          y1={height - margin.bottom}
          y2={height - margin.bottom}
          stroke="#a1a1aa"
          strokeWidth={1}
        />
        {rightMeta && (
          <line
            x1={width - margin.right}
            x2={width - margin.right}
            y1={margin.top}
            y2={height - margin.bottom}
            stroke={rightMeta.stroke}
            strokeWidth={1.25}
          />
        )}

        {active.map((s) => (
          <path key={s.key} d={pathFor(s.key)} fill="none" stroke={s.stroke} strokeWidth={1.75} />
        ))}

        {hover && (
          <>
            <line
              x1={xOf(hover.point.t)}
              x2={xOf(hover.point.t)}
              y1={margin.top}
              y2={height - margin.bottom}
              stroke="#a1a1aa"
              strokeWidth={1}
              strokeDasharray="3 3"
            />
            {active.map((s) => (
              <circle
                key={`h-${s.key}`}
                cx={xOf(hover.point.t)}
                cy={yOf(hover.point[s.key], s.key)}
                r={3.5}
                fill={s.stroke}
                stroke="#fff"
                strokeWidth={1}
              />
            ))}
          </>
        )}

        {leftMeta &&
          leftTicks.map((tick) => (
            <text
              key={`yl-${tick}`}
              x={margin.left - 6}
              y={yOf(tick, leftMeta.key) + 3}
              textAnchor="end"
              fill={leftMeta.stroke}
              style={{ fontSize: 9, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}
            >
              {formatAxisCount(tick)}
            </text>
          ))}
        {rightMeta &&
          rightTicks.map((tick) => (
            <text
              key={`yr-${tick}`}
              x={width - margin.right + 6}
              y={yOf(tick, rightMeta.key) + 3}
              textAnchor="start"
              fill={rightMeta.stroke}
              style={{ fontSize: 9, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}
            >
              {formatAxisCount(tick)}
            </text>
          ))}

        <text
          x={12}
          y={margin.top + plotH / 2}
          textAnchor="middle"
          transform={`rotate(-90 12 ${margin.top + plotH / 2})`}
          fill={leftMeta?.stroke ?? '#71717a'}
          style={{ fontSize: 9, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}
        >
          {leftMeta ? `${leftMeta.label} · ${yLabel}` : yLabel}
        </text>
        {rightMeta && (
          <text
            x={width - 12}
            y={margin.top + plotH / 2}
            textAnchor="middle"
            transform={`rotate(90 ${width - 12} ${margin.top + plotH / 2})`}
            fill={rightMeta.stroke}
            style={{ fontSize: 9, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}
          >
            {rightMeta.label} · {yLabel}
          </text>
        )}

        {xTicks.map((tick, i) => (
          <text
            key={`xl-${tick}-${i}`}
            x={xOf(tick)}
            y={height - margin.bottom + 14}
            textAnchor="middle"
            className="fill-zinc-500"
            style={{ fontSize: 9, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}
          >
            {formatElapsed(tick)}
          </text>
        ))}
        <text
          x={margin.left + plotW / 2}
          y={height - 4}
          textAnchor="middle"
          className="fill-zinc-500"
          style={{ fontSize: 9, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}
        >
          elapsed time
        </text>
      </svg>

      {hover &&
        createPortal(
          <div
            className="pointer-events-none fixed z-[90] px-2.5 py-2 rounded-lg border border-zinc-200 bg-white shadow-lg text-[10px] font-mono text-zinc-700 min-w-[140px]"
            style={{
              left: Math.min(hover.clientX + 14, window.innerWidth - 180),
              top: Math.max(8, hover.clientY - 12),
            }}
          >
            <div className="font-semibold text-zinc-900 mb-1">{formatElapsed(hover.point.t)}</div>
            {SERIES_META.filter((s) => visible[s.key]).map((s) => (
              <div key={s.key} className="flex items-center justify-between gap-3">
                <span className="inline-flex items-center gap-1.5">
                  <span className={`w-2 h-2 rounded-sm ${s.swatch}`} />
                  {s.label}
                </span>
                <span>
                  {formatAxisCount(hover.point[s.key])}
                  {mode === 'rate' ? '/min' : ''}
                </span>
              </div>
            ))}
          </div>,
          document.body
        )}
    </>
  );
}

function ModeToggle({
  mode,
  onChange,
}: {
  mode: ChartMode;
  onChange: (m: ChartMode) => void;
}) {
  return (
    <div className="inline-flex rounded-md border border-zinc-200 bg-zinc-50 p-0.5 text-[10px] font-semibold">
      <button
        type="button"
        onClick={() => onChange('cumulative')}
        className={`px-2 py-0.5 rounded ${
          mode === 'cumulative' ? 'bg-white text-zinc-800 shadow-xs' : 'text-zinc-500'
        }`}
      >
        Cumulative
      </button>
      <button
        type="button"
        onClick={() => onChange('rate')}
        className={`px-2 py-0.5 rounded ${
          mode === 'rate' ? 'bg-white text-zinc-800 shadow-xs' : 'text-zinc-500'
        }`}
      >
        Rate
      </button>
    </div>
  );
}

function TimeZoomControls({
  viewStart,
  viewEnd,
  fullEnd,
  onZoomIn,
  onZoomOut,
  onReset,
  onPan,
}: {
  viewStart: number;
  viewEnd: number;
  fullEnd: number;
  onZoomIn: () => void;
  onZoomOut: () => void;
  onReset: () => void;
  onPan: (dir: -1 | 1) => void;
}) {
  const full = viewStart <= 0 && viewEnd >= fullEnd - 1;
  return (
    <div className="inline-flex items-center gap-1 text-[10px] font-mono">
      <span className="text-zinc-400 mr-1 hidden sm:inline">Time window</span>
      <button
        type="button"
        onClick={() => onPan(-1)}
        className="px-1.5 py-0.5 rounded border border-zinc-200 text-zinc-600 hover:bg-zinc-50 disabled:opacity-40"
        disabled={viewStart <= 0}
        title="Pan earlier"
      >
        ←
      </button>
      <button
        type="button"
        onClick={onZoomOut}
        className="p-1 rounded border border-zinc-200 text-zinc-600 hover:bg-zinc-50 disabled:opacity-40"
        disabled={full}
        title="Zoom out (compress — show more of the run)"
      >
        <ZoomOut className="w-3 h-3" />
      </button>
      <button
        type="button"
        onClick={onZoomIn}
        className="p-1 rounded border border-zinc-200 text-zinc-600 hover:bg-zinc-50"
        title="Zoom in (expand — focus a shorter slice)"
      >
        <ZoomIn className="w-3 h-3" />
      </button>
      <button
        type="button"
        onClick={() => onPan(1)}
        className="px-1.5 py-0.5 rounded border border-zinc-200 text-zinc-600 hover:bg-zinc-50 disabled:opacity-40"
        disabled={viewEnd >= fullEnd}
        title="Pan later"
      >
        →
      </button>
      <button
        type="button"
        onClick={onReset}
        className="px-1.5 py-0.5 rounded border border-zinc-200 text-zinc-600 hover:bg-zinc-50 disabled:opacity-40"
        disabled={full}
        title="Show full job duration from 0"
      >
        Full
      </button>
      <span className="text-zinc-400 ml-1">
        {formatElapsed(viewStart)}–{formatElapsed(viewEnd)}
      </span>
    </div>
  );
}

function ChartLegend({
  points,
  mode,
  visible,
  onToggle,
}: {
  points: ChartPoint[];
  mode: ChartMode;
  visible: SeriesVisibility;
  onToggle: (key: SeriesKey) => void;
}) {
  const unit = mode === 'rate' ? '/min' : '';
  return (
    <div className="flex flex-wrap gap-1.5">
      {SERIES_META.map((s) => {
        const max = seriesMax(points, s.key);
        const end = seriesEnd(points, s.key);
        const on = visible[s.key];
        return (
          <button
            key={s.key}
            type="button"
            onClick={() => onToggle(s.key)}
            title={on ? `Hide ${s.label}` : `Show ${s.label}`}
            className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md border text-[10px] font-mono transition-colors ${
              on
                ? 'border-zinc-200 bg-white text-zinc-700'
                : 'border-zinc-100 bg-zinc-50 text-zinc-400 line-through'
            }`}
          >
            <span className={`inline-block w-2 h-2 rounded-sm ${s.swatch} ${on ? '' : 'opacity-40'}`} />
            <span>{s.label}</span>
            <span className="text-zinc-400">
              {formatAxisCount(end)}
              {unit}
              {' · scale 0–'}
              {formatAxisCount(Math.max(max, 1))}
            </span>
          </button>
        );
      })}
    </div>
  );
}

function JobCallVolumeChart({ series }: { series: JobTrafficSeries }) {
  const [open, setOpen] = useState(false);
  const [enlarged, setEnlarged] = useState(false);
  const [mode, setMode] = useState<ChartMode>('cumulative');
  const [visible, setVisible] = useState<SeriesVisibility>(DEFAULT_VISIBILITY);
  const [viewStart, setViewStart] = useState(0);
  const [viewEnd, setViewEnd] = useState(1);

  const samples = useMemo(
    () => series.samples.filter((s) => s && typeof s.t === 'number').sort((a, b) => a.t - b.t),
    [series.samples]
  );

  const fullEnd = Math.max(1, samples[samples.length - 1]?.t ?? 1);

  // Keep the visible window covering the full run until the user zooms.
  useEffect(() => {
    setViewStart((prev) => (prev <= 0 ? 0 : Math.min(prev, fullEnd)));
    setViewEnd((prev) => {
      if (prev <= 1 || prev >= fullEnd * 0.98) return fullEnd;
      return Math.min(prev, fullEnd);
    });
  }, [fullEnd]);

  useEffect(() => {
    if (!enlarged) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setEnlarged(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [enlarged]);

  if (samples.length < 2) {
    return (
      <p className="text-[10px] text-zinc-400">
        Need a few samples during this run to graph call volume.
      </p>
    );
  }

  const points = mode === 'rate' ? toRatePoints(samples) : toCumulativePoints(samples);
  if (points.length < 2) {
    return (
      <p className="text-[10px] text-zinc-400">
        Need another sample to plot rates.
      </p>
    );
  }

  const duration = fullEnd;
  const anyVisible = SERIES_META.some((s) => visible[s.key]);

  const toggleSeries = (key: SeriesKey) => {
    setVisible((prev) => {
      const next = { ...prev, [key]: !prev[key] };
      if (!SERIES_META.some((s) => next[s.key])) return prev;
      return next;
    });
  };

  const resetView = () => {
    setViewStart(0);
    setViewEnd(fullEnd);
  };

  const zoomIn = () => {
    const span = viewEnd - viewStart;
    const next = Math.max(MIN_VIEW_MS, span / 2);
    const center = (viewStart + viewEnd) / 2;
    let a = center - next / 2;
    let b = center + next / 2;
    if (a < 0) {
      b -= a;
      a = 0;
    }
    if (b > fullEnd) {
      a -= b - fullEnd;
      b = fullEnd;
      a = Math.max(0, a);
    }
    setViewStart(a);
    setViewEnd(b);
  };

  const zoomOut = () => {
    const span = viewEnd - viewStart;
    const next = Math.min(fullEnd, span * 2);
    const center = (viewStart + viewEnd) / 2;
    let a = center - next / 2;
    let b = center + next / 2;
    if (a < 0) {
      b -= a;
      a = 0;
    }
    if (b > fullEnd) {
      a -= b - fullEnd;
      b = fullEnd;
      a = Math.max(0, a);
    }
    if (next >= fullEnd) {
      setViewStart(0);
      setViewEnd(fullEnd);
      return;
    }
    setViewStart(a);
    setViewEnd(b);
  };

  const pan = (dir: -1 | 1) => {
    const span = viewEnd - viewStart;
    const delta = span * 0.35 * dir;
    let a = viewStart + delta;
    let b = viewEnd + delta;
    if (a < 0) {
      b -= a;
      a = 0;
    }
    if (b > fullEnd) {
      a -= b - fullEnd;
      b = fullEnd;
      a = Math.max(0, a);
    }
    setViewStart(a);
    setViewEnd(b);
  };

  const openEnlarge = () => {
    setOpen(true);
    setEnlarged(true);
  };

  const chartMarginInline = { top: 12, right: 44, bottom: 28, left: 44 };
  const chartMarginLarge = { top: 16, right: 56, bottom: 36, left: 56 };

  const chartProps = {
    points,
    allPoints: points,
    mode,
    visible,
    viewStart,
    viewEnd,
    interactive: true as const,
  };

  return (
    <div className="pt-1.5 border-t border-zinc-100 mt-1.5">
      <div className="flex items-center justify-between gap-2 mb-1 flex-wrap">
        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          className="text-left min-w-0"
          title={open ? 'Hide chart' : 'Show chart'}
        >
          <span className="text-[10px] uppercase font-bold text-zinc-500 tracking-wide">
            This run · {mode === 'rate' ? 'call rate' : 'cumulative call volume'}
          </span>
        </button>
        <div className="flex items-center gap-2 shrink-0 flex-wrap justify-end">
          {open && <ModeToggle mode={mode} onChange={setMode} />}
          {open && (
            <TimeZoomControls
              viewStart={viewStart}
              viewEnd={viewEnd}
              fullEnd={fullEnd}
              onZoomIn={zoomIn}
              onZoomOut={zoomOut}
              onReset={resetView}
              onPan={pan}
            />
          )}
          <button
            type="button"
            onClick={open ? openEnlarge : () => setOpen(true)}
            className="text-[10px] font-mono text-zinc-400 hover:text-zinc-700 inline-flex items-center gap-1"
            title="Enlarge chart"
          >
            {open ? (
              <>
                <Maximize2 className="w-3 h-3" />
                Enlarge
              </>
            ) : (
              'Show graph'
            )}
          </button>
          {open && (
            <button
              type="button"
              onClick={() => setOpen(false)}
              className="text-[10px] font-mono text-zinc-400 hover:text-zinc-700"
            >
              Hide
            </button>
          )}
        </div>
      </div>

      {open && (
        <>
          <div
            className="w-full rounded-lg border border-zinc-100 bg-zinc-50/80 hover:border-zinc-300 transition-colors p-1.5"
            onDoubleClick={openEnlarge}
            title="Hover for values · double-click to enlarge"
          >
            {anyVisible ? (
              <CallVolumeSvg
                {...chartProps}
                width={320}
                height={160}
                margin={chartMarginInline}
              />
            ) : (
              <p className="text-[10px] text-zinc-400 p-4">Select at least one series.</p>
            )}
          </div>
          <div className="mt-1.5 flex flex-col gap-1.5 sm:flex-row sm:items-start sm:justify-between">
            <ChartLegend points={points} mode={mode} visible={visible} onToggle={toggleSeries} />
            <span className="text-[10px] font-mono text-zinc-400 shrink-0">
              {samples.length} pts · full {formatElapsed(duration)}
            </span>
          </div>
          <p className="mt-1 text-[10px] text-zinc-400 leading-snug">
            X starts at 0 and spans the job · hover for values · zoom in/out to expand or compress the time
            window · multi-scale Y
          </p>
        </>
      )}

      {enlarged &&
        createPortal(
          <div
            className="fixed inset-0 z-[80] bg-black/50 backdrop-blur-sm flex items-center justify-center p-4"
            onClick={() => setEnlarged(false)}
            role="presentation"
          >
            <div
              className="bg-white border border-zinc-200 rounded-2xl shadow-2xl w-full max-w-4xl max-h-[90vh] overflow-auto"
              onClick={(e) => e.stopPropagation()}
              role="dialog"
              aria-modal="true"
              aria-label="Call volume chart"
            >
              <div className="px-5 py-3.5 border-b border-zinc-100 flex items-center justify-between gap-3 sticky top-0 bg-white z-10 flex-wrap">
                <div className="min-w-0">
                  <h4 className="text-sm font-bold text-zinc-900">
                    This run · {mode === 'rate' ? 'call rate' : 'cumulative call volume'}
                  </h4>
                  <p className="text-[11px] text-zinc-500 mt-0.5">
                    Full duration {formatElapsed(duration)} · hover any point for exact values
                  </p>
                </div>
                <div className="flex items-center gap-2 shrink-0 flex-wrap">
                  <ModeToggle mode={mode} onChange={setMode} />
                  <TimeZoomControls
                    viewStart={viewStart}
                    viewEnd={viewEnd}
                    fullEnd={fullEnd}
                    onZoomIn={zoomIn}
                    onZoomOut={zoomOut}
                    onReset={resetView}
                    onPan={pan}
                  />
                  <button
                    type="button"
                    onClick={() => setEnlarged(false)}
                    className="p-1.5 rounded-lg text-zinc-500 hover:bg-zinc-100 hover:text-zinc-800"
                    aria-label="Close"
                  >
                    <X className="w-4 h-4" />
                  </button>
                </div>
              </div>
              <div className="p-5 space-y-3">
                {anyVisible ? (
                  <CallVolumeSvg
                    {...chartProps}
                    width={860}
                    height={360}
                    margin={chartMarginLarge}
                  />
                ) : (
                  <p className="text-sm text-zinc-400">Select at least one series.</p>
                )}
                <ChartLegend points={points} mode={mode} visible={visible} onToggle={toggleSeries} />
                <p className="text-[11px] text-zinc-500 leading-relaxed">
                  X is elapsed time from job start (0 → full duration). Use zoom in to expand a shorter time
                  slice, zoom out / Full to compress back to the whole run, and ← → to pan. Hover shows each
                  series&apos; value at that moment. Each series keeps its own Y scale.
                  {mode === 'rate' ? ' Rate mode plots calls/min in each sample interval.' : ''}
                </p>
              </div>
            </div>
          </div>,
          document.body
        )}
    </div>
  );
}

export const ProviderTrafficStrip: React.FC<ProviderTrafficStripProps> = ({
  traffic,
  compact,
  pushBatchDetail,
}) => {
  const rest = traffic?.restCallCount ?? 0;
  const gql = traffic?.graphqlCallCount ?? 0;
  const gqlPts = traffic?.graphqlPointsUsed ?? 0;
  const rate = traffic?.restCallsPerMinute ?? 0;
  const lfsApi = traffic?.lfsApiCallCount ?? 0;
  const lfsHttp = traffic?.lfsTransferHttpCount ?? 0;
  const fetches = traffic?.gitHttpFetchCount ?? 0;
  const batches = traffic?.gitHttpPushBatchCount ?? 0;
  const pushAvg = traffic?.gitPushPerMinute ?? 0;
  const fetchAvg = traffic?.gitFetchPerMinute ?? 0;
  const pushPeak = traffic?.gitPushPerMinutePeak ?? traffic?.series?.gitPushPerMinutePeak;
  const pushGuideline = traffic?.gitPushRateGuideline ?? 6;
  const gitThrottles = traffic?.gitHttpThrottleCount ?? 0;
  const tooMany = traffic?.rateLimit429Count ?? 0;
  const gql429 = traffic?.graphql429Count ?? 0;
  const provider = traffic?.provider;
  const series = traffic?.series;

  const pushRateHot = (pushPeak ?? pushAvg) >= pushGuideline || gitThrottles > 0;

  if (compact) {
    return (
      <span className="font-mono text-[10px] text-zinc-500" title="This job’s cumulative REST, GraphQL, LFS, and Git traffic">
        REST {rest} · GQL {gql} · LFS {lfsApi + lfsHttp} · Git {batches} push{batches === 1 ? '' : 'es'}
      </span>
    );
  }

  return (
    <div className="p-3.5 bg-white border border-zinc-200 rounded-xl space-y-2.5 shadow-2xs">
      <div className="flex items-center justify-between text-xs font-bold text-zinc-800">
        <div className="flex items-center space-x-1.5">
          <Activity className="w-3.5 h-3.5 text-zinc-500" />
          <span>This run · API &amp; Git traffic</span>
        </div>
        {provider && (
          <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-zinc-100 text-zinc-600 uppercase font-semibold">
            {provider}
          </span>
        )}
      </div>
      <p className="text-[10px] text-zinc-400 leading-snug">
        Cumulative totals for this job so far (not a rolling window). Token remaining is shared — see Internals.
      </p>

      <div className="grid grid-cols-1 gap-2 text-[11px]">
        <div className="p-2.5 rounded-lg bg-zinc-50 border border-zinc-100 space-y-0.5">
          <div className="flex items-center justify-between">
            <span className="text-[10px] uppercase font-bold text-zinc-500 font-sans tracking-wide">
              REST API
            </span>
            <span className="font-mono font-bold text-zinc-800">
              {rest.toLocaleString()} call{rest === 1 ? '' : 's'}
            </span>
          </div>
          <div className="text-zinc-500 font-mono text-[10px] flex items-center justify-between">
            <span>{num(rate)} /min run avg</span>
            {tooMany > 0 && (
              <span className="text-rose-600 font-semibold">{tooMany}× REST 429</span>
            )}
          </div>
        </div>

        <div className="p-2.5 rounded-lg bg-zinc-50 border border-zinc-100 space-y-0.5">
          <div className="flex items-center justify-between">
            <span className="text-[10px] uppercase font-bold text-zinc-500 font-sans tracking-wide">
              GraphQL
            </span>
            <span className="font-mono font-bold text-zinc-800">
              {gql.toLocaleString()} call{gql === 1 ? '' : 's'}
            </span>
          </div>
          <div className="text-zinc-500 font-mono text-[10px] flex items-center justify-between">
            <span>{gqlPts.toLocaleString()} pts · run total</span>
            {gql429 > 0 && (
              <span className="text-rose-600 font-semibold">{gql429}× GQL 429</span>
            )}
          </div>
        </div>

        <div className="p-2.5 rounded-lg bg-zinc-50 border border-zinc-100 space-y-0.5">
          <div className="flex items-center justify-between">
            <span className="text-[10px] uppercase font-bold text-zinc-500 font-sans tracking-wide">
              Git LFS HTTP
            </span>
            <span className="font-mono font-bold text-zinc-800">
              {lfsApi.toLocaleString()} batch · {lfsHttp.toLocaleString()} object
            </span>
          </div>
          <div className="text-[10px] text-zinc-400">
            Batch API + media download/upload for this run (separate from REST quota)
          </div>
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
              pushRateHot ? 'text-rose-600' : 'text-zinc-500'
            }`}
          >
            <span>
              Push {num(pushAvg)} /min run avg
              {pushPeak != null && pushPeak > 0 ? ` · peak ${num(pushPeak)}` : ''}
              {` / ${pushGuideline} guideline`}
            </span>
            {gitThrottles > 0 && <span>{gitThrottles}× throttled</span>}
          </div>
          <div className="text-[10px] text-zinc-400">
            Fetch {num(fetchAvg)} /min run avg · JGit Smart HTTP, separate from REST quota
          </div>
        </div>
      </div>

      {series && <JobCallVolumeChart series={series} />}
    </div>
  );
};

export function trafficFromJobAndProgress(
  job?: (Partial<SyncJob> & {
    providerTrafficProvider?: string;
    providerTrafficSeriesJson?: string;
  }) | null,
  live?: ProviderTraffic | null
): ProviderTraffic {
  const jobSeries = parseSeries(job?.providerTrafficSeriesJson);
  const liveSeries = live?.series ?? null;
  const series = mergeSeries(jobSeries, liveSeries);
  return {
    restCallCount: maxNum(live?.restCallCount, job?.restCallCount),
    restCallsPerMinute: Math.max(live?.restCallsPerMinute ?? 0, job?.restCallsPerMinute ?? 0),
    graphqlCallCount: maxNum(live?.graphqlCallCount, job?.graphqlCallCount),
    graphqlPointsUsed: maxNum(live?.graphqlPointsUsed, job?.graphqlPointsUsed),
    graphql429Count: maxNum(live?.graphql429Count, job?.graphql429Count),
    lfsApiCallCount: maxNum(live?.lfsApiCallCount, job?.lfsApiCallCount),
    lfsTransferHttpCount: maxNum(live?.lfsTransferHttpCount, job?.lfsTransferHttpCount),
    gitHttpFetchCount: maxNum(live?.gitHttpFetchCount, job?.gitHttpFetchCount),
    gitHttpPushBatchCount: maxNum(live?.gitHttpPushBatchCount, job?.gitHttpPushBatchCount),
    gitPushPerMinute: Math.max(live?.gitPushPerMinute ?? 0, job?.gitPushPerMinute ?? 0),
    gitFetchPerMinute: Math.max(live?.gitFetchPerMinute ?? 0, job?.gitFetchPerMinute ?? 0),
    gitPushPerMinutePeak: Math.max(live?.gitPushPerMinutePeak ?? 0, series?.gitPushPerMinutePeak ?? 0),
    gitFetchPerMinutePeak: Math.max(live?.gitFetchPerMinutePeak ?? 0, series?.gitFetchPerMinutePeak ?? 0),
    gitHttpThrottleCount: maxNum(live?.gitHttpThrottleCount, job?.gitHttpThrottleCount),
    gitPushRateGuideline: live?.gitPushRateGuideline ?? 6,
    rateLimit429Count: maxNum(live?.rateLimit429Count, job?.rateLimit429Count),
    gitReadBytes: maxNum(live?.gitReadBytes, job?.gitReadBytes),
    gitWriteBytes: maxNum(live?.gitWriteBytes, job?.gitWriteBytes),
    lfsBytes: maxNum(live?.lfsBytes, job?.lfsBytes),
    provider: live?.provider ?? job?.providerTrafficProvider,
    series,
  };
}
