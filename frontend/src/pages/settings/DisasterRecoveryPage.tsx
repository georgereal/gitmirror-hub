import React, { useEffect, useState } from 'react';
import { ChevronDown, GitFork, Globe, Lock, RefreshCw, Server, Layers } from 'lucide-react';
import { DrLane, DrLaneRuleset } from '../../types';
import { activateDrLane, failBackDrLane, listDrLanes, probeDrLane } from '../../services/api';
import { providerLabel } from '../../utils/pairHealthFlags';

function ProviderMark({ provider }: { provider?: string }) {
  switch (provider?.toUpperCase()) {
    case 'GITLAB':
      return <Layers className="w-4 h-4 text-orange-500 shrink-0" />;
    case 'BITBUCKET':
      return <Globe className="w-4 h-4 text-blue-500 shrink-0" />;
    case 'ORIGIN':
      return <Lock className="w-4 h-4 text-purple-500 shrink-0" />;
    case 'GHES':
    case 'GITHUB_ENTERPRISE':
      return <Server className="w-4 h-4 text-indigo-500 shrink-0" />;
    default:
      return <GitFork className="w-4 h-4 text-zinc-900 shrink-0" />;
  }
}

function reachLabel(reachable?: string) {
  if (reachable === 'UP') return 'Up';
  if (reachable === 'DOWN') return 'Down';
  return 'Not checked';
}

function roleLabel(role?: string) {
  if (role === 'replica') return 'DR replica';
  if (role === 'primary') return 'Primary';
  return null;
}

function displayHost(host?: string) {
  if (!host || host === 'http' || host === 'https') return null;
  return host;
}

function ProviderBox({
  provider,
  host,
  reachable,
  role,
}: {
  provider: string;
  host?: string;
  reachable?: string;
  role?: string;
}) {
  const up = reachable === 'UP';
  const down = reachable === 'DOWN';
  return (
    <div className={`min-w-0 flex-1 rounded-xl border px-4 py-3 ${
      down ? 'border-rose-200 bg-rose-50/50' : up ? 'border-emerald-200 bg-white' : 'border-zinc-200 bg-white'
    }`}>
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2 min-w-0">
          <span className="inline-flex h-7 w-7 items-center justify-center rounded-lg border border-zinc-200 bg-zinc-50">
            <ProviderMark provider={provider} />
          </span>
          <span className="text-sm font-semibold text-zinc-900 truncate">{providerLabel(provider) || provider}</span>
        </div>
        {down ? (
          <span className="inline-flex h-4 w-4 items-center justify-center rounded-full bg-rose-600 text-white shrink-0" title="Down">
            <svg viewBox="0 0 12 12" className="h-2.5 w-2.5" aria-hidden>
              <path d="M3 3 L9 9 M9 3 L3 9" stroke="currentColor" strokeWidth="1.8" />
            </svg>
          </span>
        ) : (
          <span
            className={`h-2.5 w-2.5 rounded-full shrink-0 ${up ? 'bg-emerald-500' : 'bg-zinc-300'}`}
            title={reachLabel(reachable)}
          />
        )}
      </div>
      {displayHost(host) && <p className="mt-2 text-[11px] text-zinc-500 truncate">{displayHost(host)}</p>}
      <p className={`text-[11px] font-medium text-zinc-700 ${displayHost(host) ? 'mt-1' : 'mt-2'}`}>
        {[roleLabel(role), reachLabel(reachable)].filter(Boolean).join(' · ')}
      </p>
    </div>
  );
}

function DirectionStroke({
  toward,
  targetDown,
}: {
  toward: 'source' | 'target';
  targetDown: boolean;
}) {
  const toRight = toward === 'target';
  const color = targetDown ? '#e11d48' : '#059669';
  const y = toRight ? 12 : 28;
  const x1 = toRight ? 2 : 70;
  const x2 = toRight ? (targetDown ? 48 : 50) : (targetDown ? 24 : 22);
  const head = toRight ? '50,8 66,12 50,16' : '22,24 6,28 22,32';
  const cross = toRight
    ? 'M52 6 L66 18 M66 6 L52 18'
    : 'M6 22 L20 34 M20 22 L6 34';
  return (
    <g>
      <line
        x1={x1}
        y1={y}
        x2={x2}
        y2={y}
        stroke={color}
        strokeWidth="1.6"
        strokeDasharray="5 4"
        className={targetDown ? undefined : toRight ? 'dr-flow-forward' : 'dr-flow-back'}
      />
      {targetDown ? (
        <path d={cross} stroke={color} strokeWidth="1.8" strokeLinecap="round" />
      ) : (
        <polygon points={head} fill={color} />
      )}
    </g>
  );
}

function LaneArrows({ sourceDown, targetDown }: { sourceDown: boolean; targetDown: boolean }) {
  const bothUp = !sourceDown && !targetDown;
  return (
    <div className="w-[4.5rem] shrink-0 flex flex-col items-center justify-center gap-1">
      <svg viewBox="0 0 72 40" className="w-[4.5rem] h-10" aria-hidden>
        <DirectionStroke toward="target" targetDown={targetDown} />
        <DirectionStroke toward="source" targetDown={sourceDown} />
      </svg>
      <span className={`text-[10px] font-medium ${bothUp ? 'text-emerald-700' : 'text-rose-700'}`}>
        {bothUp ? 'Linked' : 'Broken'}
      </span>
    </div>
  );
}

function rulesetSummary(rollup?: string) {
  if (rollup === 'green') return { label: 'Rulesets applied', className: 'text-emerald-800 bg-emerald-50 border-emerald-200' };
  if (rollup === 'yellow') return { label: 'Rulesets partial', className: 'text-amber-800 bg-amber-50 border-amber-200' };
  if (rollup === 'red') return { label: 'Rulesets waiting', className: 'text-rose-800 bg-rose-50 border-rose-200' };
  return { label: 'No ruleset written yet', className: 'text-zinc-600 bg-zinc-50 border-zinc-200' };
}

function stateLabel(state?: string) {
  if (state === 'applied') return 'Applied';
  if (state === 'pending') return 'Waiting';
  if (state === 'open') return 'Write open';
  return 'Not written';
}

function stateDot(state?: string) {
  if (state === 'applied') return 'bg-emerald-500';
  if (state === 'pending') return 'bg-amber-500';
  if (state === 'open') return 'bg-sky-500';
  return 'bg-zinc-300';
}

function reachFromPairs(lane: DrLane, side: 'A' | 'B') {
  const values = (lane.pairs || []).map((pair) => (side === 'A' ? pair.reachableA : pair.reachableB));
  if (values.some((value) => value === 'DOWN')) return 'DOWN';
  if (values.some((value) => value === 'UP')) return 'UP';
  return 'UNKNOWN';
}

function overallSentence(lane: DrLane) {
  const source = providerLabel(lane.sourceProvider) || 'Source';
  const target = providerLabel(lane.targetProvider) || 'Destination';
  const sourceDown = (lane.sourceReachable || reachFromPairs(lane, 'A')) === 'DOWN';
  const targetDown = (lane.targetReachable || reachFromPairs(lane, 'B')) === 'DOWN';
  const held = lane.parkedCount > 0 ? ` ${lane.parkedCount} event${lane.parkedCount === 1 ? '' : 's'} held.` : '';
  if (lane.phase === 'FAILOVER' || lane.phase === 'FAILBACK') {
    const where = sourceDown ? `${source} is down.` : targetDown ? `${target} is down.` : 'Both providers are up.';
    return `DR is ${lane.phase === 'FAILBACK' ? 'failing back' : 'active'}. ${where}${held}`;
  }
  if (sourceDown && targetDown) {
    return `Both providers are down. Incremental events stay held until they answer.${held}`;
  }
  if (sourceDown || targetDown) {
    const down = sourceDown ? source : target;
    return `${down} is down. Incremental events stay held until both providers answer.${held}`;
  }
  if (lane.processing === 'PAUSED') {
    return `Processing is paused.${held}`;
  }
  return `Both providers are up. Incremental sync is live across ${lane.pairCount} pair${lane.pairCount === 1 ? '' : 's'}.`;
}

function displayRulesets(lane: DrLane): DrLaneRuleset[] {
  if (lane.rulesets && lane.rulesets.length > 0) {
    return lane.rulesets;
  }
  const orgs = (lane.orgs || []).map((org) => ({
    scope: 'org' as const,
    side: org.side,
    name: org.name,
    state: 'none' as const,
    detail: 'No organization ruleset written yet.',
    pairCount: org.pairCount,
  }));
  const enterprises = (lane.enterprises || []).map((row) => ({
    scope: 'enterprise' as const,
    side: row.side,
    name: row.name,
    state: 'none' as const,
    detail: 'No enterprise ruleset written yet.',
    pairCount: row.pairCount,
  }));
  const repos = (lane.pairs || []).map((pair) => ({
    scope: 'repo' as const,
    side: 'pair',
    name: pair.name,
    state: 'none' as const,
    detail: 'Repository ruleset is the fallback when an organization or enterprise lock cannot be written.',
    pairCount: 1,
  }));
  return [...enterprises, ...orgs, ...repos];
}

function RulesetGroup({ title, rows }: { title: string; rows: DrLaneRuleset[] }) {
  if (!rows.length) return null;
  return (
    <div>
      <p className="text-[10px] font-semibold uppercase tracking-wide text-zinc-400 mb-1.5">{title}</p>
      <ul className="divide-y divide-zinc-100 border border-zinc-100 rounded-lg">
        {rows.map((row) => (
          <li key={`${row.scope}:${row.side}:${row.name}`} className="flex items-start justify-between gap-3 px-3 py-2">
            <div className="min-w-0">
              <p className="text-[12px] font-medium text-zinc-900 truncate">{row.name}</p>
              <p className="text-[10px] text-zinc-500">
                {row.side === 'B' ? 'Destination' : row.side === 'A' ? 'Source' : 'Pair'}
                {row.pairCount > 0 ? ` · ${row.pairCount} pair${row.pairCount === 1 ? '' : 's'}` : ''}
                {row.detail ? ` · ${row.detail}` : ''}
              </p>
            </div>
            <span className="inline-flex items-center gap-1.5 shrink-0 text-[11px] text-zinc-700">
              <span className={`h-1.5 w-1.5 rounded-full ${stateDot(row.state)}`} />
              {stateLabel(row.state)}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

export const DisasterRecoveryPage: React.FC = () => {
  const [lanes, setLanes] = useState<DrLane[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [openKey, setOpenKey] = useState<string | null>(null);

  const load = async () => {
    setLanes(await listDrLanes());
  };

  useEffect(() => {
    void load().catch((e) => setNote(e.response?.data?.error || e.message));
  }, []);

  const run = async (lane: DrLane, kind: 'probe' | 'activate' | 'fail-back') => {
    if (kind === 'activate' && !window.confirm(
      'Activate DR for this provider pair? Every pair in the lane switches together. Hub locks the old source at enterprise or org scope when the App can, and falls back to a repository ruleset only when those scopes are unavailable.'
    )) {
      return;
    }
    if (kind === 'fail-back' && !window.confirm('Fail back this provider pair? Both providers must be up.')) {
      return;
    }
    setBusy(`${kind}:${lane.laneKey}`);
    setNote(null);
    try {
      const next = kind === 'probe'
        ? await probeDrLane(lane.laneKey)
        : kind === 'activate'
          ? await activateDrLane(lane.laneKey)
          : await failBackDrLane(lane.laneKey);
      setLanes((prev) => (prev || []).map((row) => (row.laneKey === next.laneKey ? next : row)));
    } catch (e: any) {
      setNote(e.response?.data?.error || e.response?.data?.message || e.message);
    } finally {
      setBusy(null);
    }
  };

  if (!lanes) {
    return <p className="text-xs text-zinc-500">Loading disaster recovery lanes…</p>;
  }

  return (
    <div className="space-y-4 text-xs max-w-3xl">
      <style>{`
        @keyframes dr-flow-forward {
          to { stroke-dashoffset: -18; }
        }
        @keyframes dr-flow-back {
          to { stroke-dashoffset: 18; }
        }
        .dr-flow-forward { animation: dr-flow-forward 0.9s linear infinite; }
        .dr-flow-back { animation: dr-flow-back 0.9s linear infinite; }
      `}</style>

      {note && <p className="text-[11px] text-rose-700">{note}</p>}

      {lanes.length === 0 && (
        <p className="text-[11px] text-zinc-500">No pairs yet. A lane appears once a source provider is paired with a destination.</p>
      )}

      {lanes.map((lane) => {
        const sourceReachable = lane.sourceReachable || reachFromPairs(lane, 'A');
        const targetReachable = lane.targetReachable || reachFromPairs(lane, 'B');
        const failover = lane.phase === 'FAILOVER' || lane.phase === 'FAILBACK';
        const bothUp = sourceReachable !== 'DOWN' && targetReachable !== 'DOWN' && lane.link !== 'broken';
        const open = openKey === lane.laneKey;
        const summary = rulesetSummary(lane.rulesetRollup);
        const rulesets = displayRulesets(lane);
        return (
          <section key={lane.laneKey} className="rounded-2xl border border-zinc-200 bg-white p-5 space-y-4">
            <div className="flex items-start justify-between gap-3">
              <p className="text-[12px] text-zinc-700 leading-relaxed">{overallSentence(lane)}</p>
              <span className={`shrink-0 px-2 py-0.5 rounded-full text-[10px] font-semibold border ${
                lane.processing === 'PAUSED' ? 'border-amber-200 bg-amber-50 text-amber-800' : 'border-emerald-200 bg-emerald-50 text-emerald-800'
              }`}>
                {failover ? lane.phase : lane.processing}
              </span>
            </div>

            <div className="flex items-center gap-3">
              <ProviderBox
                provider={lane.sourceProvider}
                host={lane.sourceHost}
                reachable={sourceReachable}
                role={lane.sourceRole}
              />
              <LaneArrows sourceDown={sourceReachable === 'DOWN'} targetDown={targetReachable === 'DOWN'} />
              <ProviderBox
                provider={lane.targetProvider}
                host={lane.targetHost}
                reachable={targetReachable}
                role={lane.targetRole}
              />
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <span className={`px-2 py-0.5 rounded-full border text-[10px] font-medium ${summary.className}`}>
                {summary.label}
              </span>
              {lane.lockScope && (lane.phase === 'FAILOVER' || lane.phase === 'FAILBACK') && (
                <span className="text-[10px] text-zinc-400">
                  Lock scope {lane.lockScope.toLowerCase()}
                </span>
              )}
              <button
                type="button"
                onClick={() => setOpenKey(open ? null : lane.laneKey)}
                className="ml-auto inline-flex items-center gap-1 text-[11px] font-medium text-zinc-700"
              >
                Ruleset updates
                <ChevronDown className={`w-3.5 h-3.5 transition-transform ${open ? 'rotate-180' : ''}`} />
              </button>
            </div>

            {open && (
              <div className="space-y-3 border-t border-zinc-100 pt-3">
                <RulesetGroup title="Enterprises" rows={rulesets.filter((row) => row.scope === 'enterprise')} />
                <RulesetGroup title="Organizations" rows={rulesets.filter((row) => row.scope === 'org')} />
                <RulesetGroup title="Repositories" rows={rulesets.filter((row) => row.scope === 'repo')} />
              </div>
            )}

            <div className="flex flex-wrap gap-2">
              <button
                type="button"
                disabled={busy != null}
                onClick={() => run(lane, 'probe')}
                className="inline-flex items-center gap-1 px-3 py-1.5 rounded-lg border border-zinc-300 text-xs font-medium text-zinc-800 disabled:opacity-50"
              >
                <RefreshCw className={`w-3 h-3 ${busy === `probe:${lane.laneKey}` ? 'animate-spin' : ''}`} />
                Check providers
              </button>
              {!failover && (
                <button
                  type="button"
                  disabled={busy != null}
                  onClick={() => run(lane, 'activate')}
                  className="px-3 py-1.5 rounded-lg bg-zinc-900 text-white text-xs font-medium disabled:opacity-50"
                >
                  {busy === `activate:${lane.laneKey}` ? 'Activating…' : 'Activate DR'}
                </button>
              )}
              {failover && (
                <button
                  type="button"
                  disabled={busy != null || !bothUp}
                  onClick={() => run(lane, 'fail-back')}
                  title={bothUp ? 'Return write to the original provider' : 'Both providers must be up'}
                  className="px-3 py-1.5 rounded-lg border border-zinc-300 text-xs font-medium text-zinc-800 disabled:opacity-50"
                >
                  {busy === `fail-back:${lane.laneKey}` ? 'Failing back…' : 'Fail back'}
                </button>
              )}
            </div>
          </section>
        );
      })}
    </div>
  );
};
