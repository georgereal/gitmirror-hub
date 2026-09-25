import React, { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { CloudOff } from 'lucide-react';
import { PeerStatus, PeerStatusSide, RepoMapping } from '../types';
import { getPeerStatus } from '../services/api';
import { providerLabel } from '../utils/pairHealthFlags';
import { InfoTooltip } from './InfoTooltip';

interface PeerHealthCardProps {
  mapping: RepoMapping;
  onStatus?: (status: PeerStatus) => void;
}

function rollupClass(color?: string) {
  if (color === 'green') return 'bg-emerald-50 text-emerald-800 border-emerald-200';
  if (color === 'yellow') return 'bg-amber-50 text-amber-800 border-amber-200';
  return 'bg-rose-50 text-rose-800 border-rose-200';
}

function SideColumn({ side, label }: { side?: PeerStatusSide; label: string }) {
  if (!side) {
    return <div className="text-[11px] text-zinc-400">{label}</div>;
  }
  const down = side.reachable === 'DOWN';
  return (
    <div className="min-w-0 flex-1">
      <div className="flex items-center gap-1.5">
        <span className="text-xs font-semibold text-zinc-900">{providerLabel(side.provider) || label}</span>
        {down && <CloudOff className="w-3.5 h-3.5 text-rose-600" />}
      </div>
      <p className="text-[10px] text-zinc-400 truncate">{side.host}</p>
      <div className="mt-1.5 flex flex-wrap gap-1">
        <span className="px-1.5 py-0.5 rounded border border-zinc-200 text-[10px] text-zinc-600">
          {side.role === 'replica' ? 'DR replica' : 'Primary'}
        </span>
        <span className={`px-1.5 py-0.5 rounded border text-[10px] ${down ? 'border-rose-200 text-rose-700 bg-rose-50' : 'border-emerald-200 text-emerald-700 bg-emerald-50'}`}>
          {side.reachable === 'UNKNOWN' ? 'Unknown' : down ? 'Down' : 'Up'}
        </span>
        <span className={`px-1.5 py-0.5 rounded border text-[10px] ${rollupClass(side.rulesetRollup)}`}
          title={(side.rulesetTargets || []).map((t) => `${t.scope} ${t.name || ''}: ${t.state}`).join('\n')}
        >
          Ruleset {side.rulesetRollup || 'red'}
        </span>
        {side.lock === 'pending' && (
          <span className="px-1.5 py-0.5 rounded border border-amber-200 text-amber-800 bg-amber-50 text-[10px]">Lock pending</span>
        )}
      </div>
    </div>
  );
}

export const PeerHealthCard: React.FC<PeerHealthCardProps> = ({ mapping, onStatus }) => {
  const [status, setStatus] = useState<PeerStatus | null>(mapping.peerStatus || null);
  const [note, setNote] = useState<string | null>(null);

  const load = async () => {
    const next = await getPeerStatus(mapping.id);
    setStatus(next);
    onStatus?.(next);
    return next;
  };

  useEffect(() => {
    void load().catch((e) => setNote(e.response?.data?.error || e.message));
    const timer = window.setInterval(() => {
      void getPeerStatus(mapping.id).then((next) => {
        setStatus(next);
        onStatus?.(next);
      }).catch(() => undefined);
    }, 30_000);
    return () => window.clearInterval(timer);
  }, [mapping.id]);

  const sideA = status?.sides?.find((s) => s.id === 'A');
  const sideB = status?.sides?.find((s) => s.id === 'B');
  const broken = status?.link === 'broken';

  return (
    <div className="rounded-2xl border border-zinc-200/90 bg-white p-6 shadow-sm space-y-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <h3 className="text-sm font-semibold text-zinc-900">Provider map and DR</h3>
            <InfoTooltip
              title="Provider health"
              whatIsIt="This pair sits inside a provider-to-provider lane. Activate DR, fail back, and ruleset locks are managed on that lane."
              howItWorks="Settings → Disaster recovery groups every pair that shares the same source and destination providers. Locks are applied at enterprise or organization scope, with a repository ruleset only when those scopes are unavailable."
            />
          </div>
          <p className="text-[11px] text-zinc-500 mt-1">
            {status?.processing === 'PAUSED'
              ? `Processing paused${status.pausedReason ? ` (${status.pausedReason.replace('_', ' ')})` : ''}. ${status.parkedCount || 0} event(s) held.`
              : 'Both remotes last answered. Incremental sync is live.'}
          </p>
        </div>
        <div className="flex flex-wrap gap-1.5 justify-end">
          {status?.phase && status.phase !== 'STEADY' && (
            <span className="px-2 py-0.5 rounded-full text-[10px] font-semibold border border-amber-200 bg-amber-50 text-amber-800">{status.phase}</span>
          )}
          <span className={`px-2 py-0.5 rounded-full text-[10px] font-semibold border ${status?.processing === 'PAUSED' ? 'border-amber-200 bg-amber-50 text-amber-800' : 'border-emerald-200 bg-emerald-50 text-emerald-800'}`}>
            {status?.processing || 'LIVE'}
          </span>
        </div>
      </div>

      <div className="flex items-center gap-3">
        <SideColumn side={sideA} label="Source" />
        <div className="shrink-0 flex flex-col items-center w-20">
          <div className={`h-0.5 w-full ${broken ? 'border-t-2 border-dashed border-rose-400' : 'bg-zinc-300'}`} />
          <span className="text-[10px] text-zinc-400 mt-1">
            {mapping.syncDirection === 'UNIDIRECTIONAL_B_TO_A' ? 'B → A' : mapping.syncDirection === 'BIDIRECTIONAL' ? 'A ↔ B' : 'A → B'}
          </span>
        </div>
        <SideColumn side={sideB} label="Destination" />
      </div>

      <Link to="/settings/dr" className="inline-flex text-xs font-medium text-zinc-800 underline underline-offset-2">
        Manage this lane in Disaster recovery
      </Link>
      {note && <p className="text-[11px] text-rose-700">{note}</p>}
    </div>
  );
};
