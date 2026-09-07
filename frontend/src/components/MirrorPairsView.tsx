import React, { useState } from 'react';
import { GitBranch, ArrowLeftRight, ArrowRight, Play, Plus, Edit2, Trash2, CheckCircle2, AlertCircle, Clock } from 'lucide-react';
import { RepoMapping } from '../types';

interface MirrorPairsViewProps {
  mappings: RepoMapping[];
  onAdd: () => void;
  onEdit: (mapping: RepoMapping) => void;
  onDelete: (id: number) => void;
  onTriggerSync: (id: number, branch?: string, direction?: string) => void;
}

export const MirrorPairsView: React.FC<MirrorPairsViewProps> = ({
  mappings,
  onAdd,
  onEdit,
  onDelete,
  onTriggerSync,
}) => {
  const [triggeringId, setTriggeringId] = useState<number | null>(null);

  const handleSyncClick = async (id: number) => {
    setTriggeringId(id);
    try {
      await onTriggerSync(id);
    } finally {
      setTimeout(() => setTriggeringId(null), 1000);
    }
  };

  const cleanRepoName = (url: string) => {
    if (!url) return '';
    try {
      return url.replace(/^https?:\/\/[^/]+\//, '').replace(/\.git$/, '');
    } catch {
      return url;
    }
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 shadow-sm">
      <div className="flex items-center justify-between mb-4">
        <div>
          <h2 className="text-base font-semibold text-white">Configured Repository Mirror Pairs</h2>
          <p className="text-xs text-slate-400">Bidirectional and unidirectional mirror mappings between GitHub repos</p>
        </div>
        <button
          onClick={onAdd}
          className="flex items-center space-x-1.5 bg-emerald-600 hover:bg-emerald-500 text-white px-3.5 py-1.5 rounded-lg text-xs font-medium transition-colors shadow-sm"
        >
          <Plus className="w-3.5 h-3.5" />
          <span>Add New Mirror Pair</span>
        </button>
      </div>

      {mappings.length === 0 ? (
        <div className="text-center py-12 border border-dashed border-slate-800 rounded-lg">
          <GitBranch className="w-8 h-8 text-slate-600 mx-auto mb-2" />
          <p className="text-sm font-medium text-slate-400">No mirror pairs configured</p>
          <p className="text-xs text-slate-500 mt-1">Add your first pair to begin mirroring</p>
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead className="bg-slate-950/60 text-slate-400 border-b border-slate-800">
              <tr>
                <th className="py-2.5 px-3 font-medium">PAIR NAME</th>
                <th className="py-2.5 px-3 font-medium">SOURCE & TARGET REPOSITORIES</th>
                <th className="py-2.5 px-3 font-medium">DIRECTION</th>
                <th className="py-2.5 px-3 font-medium">BRANCH PATTERN</th>
                <th className="py-2.5 px-3 font-medium">LAST SYNC</th>
                <th className="py-2.5 px-3 font-medium text-right">ACTIONS</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800/60">
              {mappings.map((pair) => (
                <tr key={pair.id} className="hover:bg-slate-800/40 transition-colors">
                  <td className="py-3 px-3 font-medium text-white flex items-center space-x-2">
                    <span className={`w-2 h-2 rounded-full ${pair.active ? 'bg-emerald-400' : 'bg-slate-600'}`} />
                    <span>{pair.name}</span>
                  </td>
                  <td className="py-3 px-3">
                    <div className="flex items-center space-x-2 font-mono text-[11px]">
                      <span className="text-slate-300 bg-slate-800 px-2 py-0.5 rounded border border-slate-700">
                        {cleanRepoName(pair.repoAUrl)}
                      </span>
                      {pair.syncDirection === 'BIDIRECTIONAL' ? (
                        <ArrowLeftRight className="w-3.5 h-3.5 text-emerald-400 shrink-0" />
                      ) : (
                        <ArrowRight className="w-3.5 h-3.5 text-blue-400 shrink-0" />
                      )}
                      <span className="text-slate-300 bg-slate-800 px-2 py-0.5 rounded border border-slate-700">
                        {cleanRepoName(pair.repoBUrl)}
                      </span>
                    </div>
                  </td>
                  <td className="py-3 px-3">
                    <span
                      className={`px-2 py-0.5 rounded-full text-[10px] font-medium border ${
                        pair.syncDirection === 'BIDIRECTIONAL'
                          ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20'
                          : 'bg-blue-500/10 text-blue-400 border-blue-500/20'
                      }`}
                    >
                      {pair.syncDirection === 'BIDIRECTIONAL' ? 'Bidirectional' : 'Unidirectional'}
                    </span>
                  </td>
                  <td className="py-3 px-3 font-mono text-slate-400">
                    {pair.branchPattern || '*'}
                  </td>
                  <td className="py-3 px-3">
                    {pair.lastSyncAt ? (
                      <div className="flex items-center space-x-1.5 text-slate-300">
                        {pair.lastSyncStatus === 'SUCCESS' ? (
                          <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />
                        ) : (
                          <AlertCircle className="w-3.5 h-3.5 text-rose-400" />
                        )}
                        <span>{new Date(pair.lastSyncAt).toLocaleTimeString()}</span>
                      </div>
                    ) : (
                      <span className="text-slate-600">Never</span>
                    )}
                  </td>
                  <td className="py-3 px-3 text-right">
                    <div className="flex items-center justify-end space-x-1.5">
                      <button
                        onClick={() => handleSyncClick(pair.id)}
                        disabled={triggeringId === pair.id}
                        className="flex items-center space-x-1 bg-slate-800 hover:bg-emerald-600/30 hover:text-emerald-300 text-slate-300 px-2.5 py-1 rounded border border-slate-700 transition-colors"
                        title="Trigger manual sync now"
                      >
                        <Play className={`w-3 h-3 ${triggeringId === pair.id ? 'animate-spin' : ''}`} />
                        <span>Sync</span>
                      </button>
                      <button
                        onClick={() => onEdit(pair)}
                        className="p-1 text-slate-400 hover:text-white bg-slate-800 hover:bg-slate-700 rounded border border-slate-700 transition-colors"
                        title="Edit pair"
                      >
                        <Edit2 className="w-3.5 h-3.5" />
                      </button>
                      <button
                        onClick={() => onDelete(pair.id)}
                        className="p-1 text-slate-400 hover:text-rose-400 bg-slate-800 hover:bg-slate-700 rounded border border-slate-700 transition-colors"
                        title="Delete pair"
                      >
                        <Trash2 className="w-3.5 h-3.5" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};
