import React, { useState } from 'react';
import {
  Search,
  Plus,
  MoreHorizontal,
  Play,
  Trash2,
  Settings as SettingsIcon,
  GitFork,
  GitCompare,
  GitBranch,
  Tag,
  GitPullRequest,
  Layers,
  AlertTriangle,
  ShieldAlert,
} from 'lucide-react';
import { RepoMapping } from '../types';
import {
  HealthChip,
  HealthIcon,
  lastSyncStatusLabel,
  pairHealthChips,
  providerLabel,
} from '../utils/pairHealthFlags';

interface RepoListViewProps {
  mappings: RepoMapping[];
  onSelectRepo: (repo: RepoMapping) => void;
  onNewPair: () => void;
  onSyncFromGitHub: () => void;
  onDeletePair: (id: number) => void;
  onTriggerSync: (id: number) => void;
}

const CHIP_ICON: Record<HealthIcon, React.ComponentType<{ className?: string }>> = {
  branch: GitBranch,
  tag: Tag,
  pr: GitPullRequest,
  lfs: Layers,
  checkpoint: AlertTriangle,
  conflict: ShieldAlert,
};

function FlagChip({ chip }: { chip: HealthChip }) {
  const Icon = chip.icon ? CHIP_ICON[chip.icon] : null;
  const toneClass =
    chip.tone === 'ok'
      ? 'text-emerald-700'
      : chip.tone === 'warn'
        ? 'text-amber-700'
        : 'text-zinc-400';
  return (
    <span className={`inline-flex items-center gap-1 ${toneClass}`}>
      {Icon ? <Icon className="w-3 h-3 shrink-0" /> : null}
      <span>{chip.label}</span>
    </span>
  );
}

function statusPillClass(status?: string) {
  if (status === 'SUCCESS') return 'text-emerald-700 bg-emerald-50';
  if (status === 'CONFLICT_ISOLATED' || status === 'PAUSED' || status === 'INTERRUPTED') {
    return 'text-amber-700 bg-amber-50';
  }
  if (status === 'FAILED' || status === 'DEAD_LETTERED') return 'text-rose-700 bg-rose-50';
  if (status === 'IN_PROGRESS' || status === 'QUEUED') return 'text-blue-700 bg-blue-50';
  return 'text-zinc-500 bg-zinc-100';
}

export const RepoListView: React.FC<RepoListViewProps> = ({
  mappings,
  onSelectRepo,
  onNewPair,
  onSyncFromGitHub,
  onDeletePair,
  onTriggerSync,
}) => {
  const [searchTerm, setSearchTerm] = useState('');
  const [activeMenuId, setActiveMenuId] = useState<number | null>(null);

  const filteredMappings = mappings.filter((m) =>
    m.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
    m.repoAUrl.toLowerCase().includes(searchTerm.toLowerCase()) ||
    m.repoBUrl.toLowerCase().includes(searchTerm.toLowerCase())
  );

  const formatRelativeTime = (isoString?: string) => {
    if (!isoString) return 'Never synced';
    try {
      const diffMs = Date.now() - new Date(isoString).getTime();
      const diffMins = Math.floor(diffMs / 60000);
      const diffHours = Math.floor(diffMins / 60);
      const diffDays = Math.floor(diffHours / 24);

      if (diffMins < 1) return 'Just now';
      if (diffMins < 60) return `${diffMins} min${diffMins > 1 ? 's' : ''} ago`;
      if (diffHours < 24) return `${diffHours} hour${diffHours > 1 ? 's' : ''} ago`;
      return `${diffDays} day${diffDays > 1 ? 's' : ''} ago`;
    } catch {
      return 'Recently';
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <h2 className="text-sm font-semibold text-zinc-900">All Mirrored Repositories</h2>

        <div className="flex items-center space-x-2">
          <div className="relative">
            <Search className="w-3.5 h-3.5 text-zinc-400 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
            <input
              type="text"
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              placeholder="Find repo or fork..."
              className="bg-white border border-zinc-200/90 rounded-lg pl-8 pr-3 py-1.5 text-xs text-zinc-900 placeholder:text-zinc-400 focus:outline-none focus:border-zinc-400 w-44 sm:w-56"
            />
          </div>

          <button
            onClick={onNewPair}
            className="flex items-center space-x-1.5 bg-white hover:bg-zinc-50 border border-zinc-200 text-zinc-800 text-xs px-3 py-1.5 rounded-lg font-medium transition-colors shadow-sm"
          >
            <Plus className="w-3.5 h-3.5" />
            <span>New</span>
          </button>

          <button
            onClick={onSyncFromGitHub}
            className="bg-zinc-900 hover:bg-zinc-800 text-white text-xs px-3.5 py-1.5 rounded-lg font-medium transition-colors shadow-sm"
          >
            Add Repository Mirror
          </button>
        </div>
      </div>

      <div className="rounded-2xl border border-zinc-200/90 bg-white shadow-sm overflow-hidden">
        {filteredMappings.length === 0 ? (
          <div className="py-16 px-4 text-center space-y-3">
            <div className="w-10 h-10 rounded-full bg-zinc-100 flex items-center justify-center mx-auto text-zinc-400">
              <GitFork className="w-5 h-5" />
            </div>
            <div>
              <p className="text-sm font-medium text-zinc-800">No mirrored repositories found</p>
              <p className="text-xs text-zinc-500 mt-0.5">
                {searchTerm ? 'Try adjusting your search query' : 'Connect a GitHub repository or Git fork to start automated replication'}
              </p>
            </div>
            <div className="pt-2">
              <button
                onClick={onNewPair}
                className="inline-flex items-center space-x-1.5 bg-zinc-900 hover:bg-zinc-800 text-white text-xs px-3.5 py-1.5 rounded-lg font-medium transition-colors"
              >
                <Plus className="w-3.5 h-3.5" />
                <span>Configure Repository Pair</span>
              </button>
            </div>
          </div>
        ) : (
          <div>
            <div className="grid grid-cols-12 px-6 py-3 border-b border-zinc-100 text-xs font-medium text-zinc-500">
              <div className="col-span-8 sm:col-span-9">Repository / Fork Pair</div>
              <div className="col-span-4 sm:col-span-3 text-right">Last Synced ↓</div>
            </div>

            <div className="divide-y divide-zinc-100">
              {filteredMappings.map((mapping) => {
                const sourceProvider = providerLabel(mapping.sourceProvider);
                const targetProvider = providerLabel(mapping.targetProvider);
                const chips = pairHealthChips(mapping);
                return (
                  <div
                    key={mapping.id}
                    className="grid grid-cols-12 px-6 py-4 items-center hover:bg-zinc-50/70 transition-colors group cursor-pointer"
                    onClick={() => onSelectRepo(mapping)}
                  >
                    <div className="col-span-8 sm:col-span-9 flex items-start space-x-3 min-w-0">
                      <div className="w-8 h-8 mt-0.5 rounded-lg bg-zinc-100 flex items-center justify-center text-zinc-700 shrink-0 group-hover:bg-zinc-200/60 transition-colors">
                        <GitCompare className="w-4 h-4" />
                      </div>

                      <div className="min-w-0">
                        <span className="text-xs font-semibold text-zinc-900 group-hover:text-zinc-700">
                          {mapping.name}
                        </span>
                        <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[11px] text-zinc-400 mt-0.5">
                          <span className="font-mono truncate max-w-[220px]">
                            {mapping.repoAUrl.replace(/^https?:\/\/[^/]+\//, '')}
                          </span>
                          <span className="text-zinc-400">➔</span>
                          <span className="font-mono truncate max-w-[220px]">
                            {mapping.repoBUrl.replace(/^https?:\/\/[^/]+\//, '')}
                          </span>
                          {sourceProvider && targetProvider && (
                            <>
                              <span className="px-1.5 py-0.5 rounded border border-zinc-200 text-[10px] text-zinc-600">
                                {sourceProvider}
                              </span>
                              <span className="text-zinc-300">→</span>
                              <span className="px-1.5 py-0.5 rounded border border-zinc-200 text-[10px] text-zinc-600">
                                {targetProvider}
                              </span>
                            </>
                          )}
                          <span className="text-zinc-500">
                            {mapping.syncDirection === 'BIDIRECTIONAL' ? 'Bidirectional' : 'Unidirectional'}
                          </span>
                          <span className={`px-1.5 py-0.2 rounded text-[10px] font-medium ${
                            mapping.storageTier === 'HOT_PERSISTENT' ? 'bg-orange-50 text-orange-700 border border-orange-200' :
                            mapping.storageTier === 'NAS_MOUNT' ? 'bg-blue-50 text-blue-700 border border-blue-200' :
                            mapping.storageTier === 'EPHEMERAL_STREAM' ? 'bg-purple-50 text-purple-700 border border-purple-200' :
                            'bg-zinc-100 text-zinc-600 border border-zinc-200'
                          }`}>
                            {mapping.storageTier === 'HOT_PERSISTENT' ? 'Hot NVMe' :
                             mapping.storageTier === 'NAS_MOUNT' ? 'NAS Volume' :
                             mapping.storageTier === 'EPHEMERAL_STREAM' ? 'Ephemeral' : 'Auto LRU'}
                          </span>
                        </div>
                        <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-[11px] mt-1.5">
                          {chips.map((chip, index) => (
                            <React.Fragment key={chip.id}>
                              {index > 0 && <span className="text-zinc-300">·</span>}
                              <FlagChip chip={chip} />
                            </React.Fragment>
                          ))}
                        </div>
                      </div>
                    </div>

                    <div className="col-span-4 sm:col-span-3 flex items-center justify-end space-x-3" onClick={(e) => e.stopPropagation()}>
                      <div className="text-right">
                        <span className="text-xs text-zinc-600 font-medium block">
                          {formatRelativeTime(mapping.lastSyncAt || mapping.createdAt)}
                        </span>
                        <span className={`text-[10px] font-medium px-1.5 py-0.2 rounded-full inline-block ${statusPillClass(mapping.lastSyncStatus)}`}>
                          {lastSyncStatusLabel(mapping.lastSyncStatus)}
                        </span>
                      </div>

                      <div className="relative">
                        <button
                          onClick={() => setActiveMenuId(activeMenuId === mapping.id ? null : mapping.id)}
                          className="p-1 text-zinc-400 hover:text-zinc-700 hover:bg-zinc-100 rounded-md transition-colors"
                        >
                          <MoreHorizontal className="w-4 h-4" />
                        </button>

                        {activeMenuId === mapping.id && (
                          <div className="absolute right-0 mt-1 w-44 bg-white border border-zinc-200 rounded-xl shadow-lg py-1 z-20 text-xs">
                            <button
                              onClick={() => {
                                setActiveMenuId(null);
                                onSelectRepo(mapping);
                              }}
                              className="w-full text-left px-3 py-2 text-zinc-700 hover:bg-zinc-50 flex items-center space-x-2"
                            >
                              <SettingsIcon className="w-3.5 h-3.5 text-zinc-400" />
                              <span>View Sync Settings</span>
                            </button>
                            <button
                              onClick={() => {
                                setActiveMenuId(null);
                                onTriggerSync(mapping.id);
                              }}
                              className="w-full text-left px-3 py-2 text-zinc-700 hover:bg-zinc-50 flex items-center space-x-2"
                            >
                              <Play className="w-3.5 h-3.5 text-emerald-600" />
                              <span>Trigger Sync Now</span>
                            </button>
                            <div className="border-t border-zinc-100 my-1" />
                            <button
                              onClick={() => {
                                setActiveMenuId(null);
                                onDeletePair(mapping.id);
                              }}
                              className="w-full text-left px-3 py-2 text-rose-600 hover:bg-rose-50 flex items-center space-x-2"
                            >
                              <Trash2 className="w-3.5 h-3.5" />
                              <span>Delete Pair</span>
                            </button>
                          </div>
                        )}
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>

            <div className="px-6 py-3 border-t border-zinc-100 flex items-center justify-between text-xs text-zinc-500">
              <span>Showing 1-{filteredMappings.length} of {filteredMappings.length}</span>

              <div className="flex items-center space-x-2">
                <button
                  disabled
                  className="px-2.5 py-1 rounded border border-zinc-200 text-zinc-400 text-[11px] disabled:opacity-40"
                >
                  Prev
                </button>
                <span className="text-zinc-700 font-medium text-[11px]">1 / 1</span>
                <button
                  disabled
                  className="px-2.5 py-1 rounded border border-zinc-200 text-zinc-400 text-[11px] disabled:opacity-40"
                >
                  Next
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
