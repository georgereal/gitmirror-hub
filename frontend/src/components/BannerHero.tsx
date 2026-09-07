import React, { useState } from 'react';
import { X, Check, ArrowRight, GitPullRequest, GitCompare, MessageSquare, Shield, GitFork } from 'lucide-react';

interface BannerHeroProps {
  onSyncFromGitHub: () => void;
}

export const BannerHero: React.FC<BannerHeroProps> = ({ onSyncFromGitHub }) => {
  const [dismissed, setDismissed] = useState(false);

  if (dismissed) return null;

  return (
    <div className="relative overflow-hidden rounded-2xl border border-zinc-200/90 bg-white shadow-sm mb-8 transition-all">
      <button
        onClick={() => setDismissed(true)}
        className="absolute top-4 right-4 p-1 text-zinc-400 hover:text-zinc-600 rounded-lg hover:bg-zinc-100 transition-colors z-10"
        title="Dismiss banner"
      >
        <X className="w-4 h-4" />
      </button>

      <div className="grid grid-cols-1 lg:grid-cols-12 items-center">
        {/* Left Side: Mock Pull Request / Mirror Sync Preview Card */}
        <div className="lg:col-span-6 p-6 sm:p-8 bg-gradient-to-br from-zinc-950 via-zinc-900 to-zinc-950 text-white rounded-l-2xl relative overflow-hidden flex flex-col justify-center min-h-[260px]">
          {/* Subtle background glow */}
          <div className="absolute -top-20 -left-20 w-60 h-60 bg-emerald-500/10 rounded-full blur-3xl pointer-events-none" />
          <div className="absolute -bottom-20 -right-20 w-60 h-60 bg-indigo-500/10 rounded-full blur-3xl pointer-events-none" />

          <div className="bg-zinc-900/90 border border-zinc-700/60 rounded-xl p-4 shadow-xl backdrop-blur-sm relative z-10 space-y-3">
            <div className="flex items-center justify-between">
              <span className="inline-flex items-center space-x-1.5 bg-emerald-500/20 text-emerald-400 border border-emerald-500/30 text-[11px] font-medium px-2.5 py-0.5 rounded-full">
                <GitPullRequest className="w-3 h-3" />
                <span>Synchronized</span>
              </span>
              <span className="text-[11px] text-zinc-400 font-mono">
                AMQP Queue: 0 backlog
              </span>
            </div>

            <div>
              <h4 className="text-sm font-medium text-zinc-100 leading-snug">
                feat(core): Real-time bidirectional Git ref replication & failover #1042
              </h4>
              <p className="text-[11px] font-mono text-zinc-400 mt-1">
                upstream/main <span className="text-emerald-400">⇄</span> mirror-fork/main
              </p>
            </div>

            <div className="flex items-center space-x-2 pt-1">
              <span className="px-2.5 py-1 bg-zinc-800 text-zinc-300 text-[11px] font-mono rounded-md border border-zinc-700">
                origin (GitHub)
              </span>
              <span className="text-zinc-500 text-xs">➔</span>
              <span className="px-2.5 py-1 bg-zinc-800 text-zinc-300 text-[11px] font-mono rounded-md border border-zinc-700">
                destination (Git Fork)
              </span>
            </div>

            <div className="pt-2 border-t border-zinc-800/80 flex items-center justify-between text-[11px] text-zinc-400">
              <span className="flex items-center space-x-1">
                <GitFork className="w-3 h-3 text-zinc-500" />
                <span>Refs & Tags Synced</span>
              </span>
              <span>•</span>
              <span>Dedup Ledger Active</span>
              <span>•</span>
              <span className="flex items-center space-x-1 text-emerald-400/90">
                <Shield className="w-3 h-3" />
                <span>Checks Verified</span>
              </span>
            </div>
          </div>
        </div>

        {/* Right Side: Cross-Repo Git Mirror Value Prop */}
        <div className="lg:col-span-6 p-6 sm:p-8 flex flex-col justify-center space-y-4">
          <div>
            <span className="inline-block bg-zinc-100 text-zinc-700 border border-zinc-200 text-[11px] font-medium px-2.5 py-0.5 rounded-full mb-2">
              Cross-Repository Mirroring
            </span>
            <h3 className="text-xl sm:text-2xl font-bold tracking-tight text-zinc-900">
              Mirror GitHub to GitHub or any Git Fork
            </h3>
          </div>

          <ul className="space-y-2 text-xs sm:text-sm text-zinc-600">
            <li className="flex items-center space-x-2">
              <Check className="w-4 h-4 text-emerald-600 shrink-0" />
              <span>Bidirectional event-driven sync on push, commit, & PR events</span>
            </li>
            <li className="flex items-center space-x-2">
              <Check className="w-4 h-4 text-emerald-600 shrink-0" />
              <span>Zero-loop deduplication ledger prevents infinite push cycles</span>
            </li>
            <li className="flex items-center space-x-2">
              <Check className="w-4 h-4 text-emerald-600 shrink-0" />
              <span>Cloud message queue with Dead Letter Queue (DLQ) failover resilience</span>
            </li>
          </ul>

          <div className="pt-2">
            <button
              onClick={onSyncFromGitHub}
              className="inline-flex items-center space-x-2 bg-zinc-900 hover:bg-zinc-800 text-white text-xs sm:text-sm font-medium px-5 py-2.5 rounded-lg transition-all shadow-sm"
            >
              <span>Add Repository Mirror</span>
              <ArrowRight className="w-4 h-4" />
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
