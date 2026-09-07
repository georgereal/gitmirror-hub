import React, { useState, useEffect } from 'react';
import {
  Inbox,
  Search,
  Filter,
  Trash2,
  RefreshCw,
  Sparkles,
  ExternalLink,
  AlertCircle,
  Clock,
  GitBranch,
  GitCommit,
  User,
  ShieldAlert,
  ArrowRight
} from 'lucide-react';
import { UnmappedWebhookEvent } from '../types';
import { getUnmappedWebhooks, deleteUnmappedWebhook, clearUnmappedWebhooks } from '../services/api';

interface UnmappedWebhooksViewProps {
  onConfigurePair: (initialData: { repoAUrl: string; name?: string }) => void;
}

export const UnmappedWebhooksView: React.FC<UnmappedWebhooksViewProps> = ({ onConfigurePair }) => {
  const [events, setEvents] = useState<UnmappedWebhookEvent[]>([]);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [filterReason, setFilterReason] = useState<string>('ALL');

  const fetchEvents = async () => {
    setLoading(true);
    try {
      const data = await getUnmappedWebhooks();
      setEvents(data);
    } catch (e) {
      console.error('Failed to load unmapped webhook events:', e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchEvents();
  }, []);

  const handleDelete = async (id: number) => {
    try {
      await deleteUnmappedWebhook(id);
      setEvents((prev) => prev.filter((e) => e.id !== id));
    } catch (e) {
      console.error('Failed to delete unmapped event:', e);
    }
  };

  const handleClearAll = async () => {
    if (!window.confirm('Are you sure you want to clear all discarded webhook events?')) return;
    try {
      await clearUnmappedWebhooks();
      setEvents([]);
    } catch (e) {
      console.error('Failed to clear unmapped events:', e);
    }
  };

  const filteredEvents = events.filter((e) => {
    if (filterReason !== 'ALL' && e.discardReason !== filterReason) return false;
    if (search) {
      const q = search.toLowerCase();
      return (
        (e.repoFullName && e.repoFullName.toLowerCase().includes(q)) ||
        (e.branch && e.branch.toLowerCase().includes(q)) ||
        (e.sender && e.sender.toLowerCase().includes(q)) ||
        (e.commitSha && e.commitSha.toLowerCase().includes(q)) ||
        (e.details && e.details.toLowerCase().includes(q))
      );
    }
    return true;
  });

  const getReasonBadge = (reason: string) => {
    switch (reason) {
      case 'UNMAPPED_REPOSITORY':
        return (
          <span className="inline-flex items-center space-x-1 bg-amber-50 text-amber-700 border border-amber-200 px-2 py-0.5 rounded-full text-[10px] font-medium">
            <AlertCircle className="w-3 h-3" />
            <span>Unmapped Repository</span>
          </span>
        );
      case 'INACTIVE_MAPPING':
        return (
          <span className="inline-flex items-center space-x-1 bg-zinc-100 text-zinc-700 border border-zinc-200 px-2 py-0.5 rounded-full text-[10px] font-medium">
            <span>Inactive Mapping</span>
          </span>
        );
      case 'NON_BRANCH_REF':
        return (
          <span className="inline-flex items-center space-x-1 bg-purple-50 text-purple-700 border border-purple-200 px-2 py-0.5 rounded-full text-[10px] font-medium">
            <span>Non-Branch Ref</span>
          </span>
        );
      case 'DIRECTION_IGNORED':
        return (
          <span className="inline-flex items-center space-x-1 bg-blue-50 text-blue-700 border border-blue-200 px-2 py-0.5 rounded-full text-[10px] font-medium">
            <span>Direction Excluded</span>
          </span>
        );
      default:
        return (
          <span className="inline-flex items-center space-x-1 bg-zinc-100 text-zinc-600 border border-zinc-200 px-2 py-0.5 rounded-full text-[10px] font-medium">
            <span>{reason}</span>
          </span>
        );
    }
  };

  const formatTime = (isoString: string) => {
    try {
      const d = new Date(isoString);
      return d.toLocaleString([], {
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
      });
    } catch {
      return isoString;
    }
  };

  return (
    <div className="space-y-4">
      {/* Top Banner Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div>
          <div className="flex items-center space-x-2">
            <h2 className="text-base font-semibold text-zinc-900">Discarded & Unmapped Webhooks Stream</h2>
            <span className="inline-flex items-center space-x-1 bg-zinc-100 text-zinc-600 border border-zinc-200 px-2 py-0.5 rounded-full text-[10px] font-medium">
              <Clock className="w-3 h-3 text-zinc-400" />
              <span>7-Day Retention</span>
            </span>
          </div>
          <p className="text-xs text-zinc-500 mt-0.5">
            Real-time trace of incoming webhooks received from AMQP that were ignored or discarded.
          </p>
        </div>

        <div className="flex items-center space-x-2">
          <div className="relative">
            <Search className="w-3.5 h-3.5 text-zinc-400 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search repository, sender..."
              className="bg-white border border-zinc-200 rounded-lg pl-8 pr-3 py-1.5 text-xs text-zinc-900 placeholder:text-zinc-400 focus:outline-none focus:border-zinc-400 w-44 sm:w-56"
            />
          </div>

          <select
            value={filterReason}
            onChange={(e) => setFilterReason(e.target.value)}
            className="bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-xs text-zinc-700 focus:outline-none focus:border-zinc-400"
          >
            <option value="ALL">All Reasons</option>
            <option value="UNMAPPED_REPOSITORY">Unmapped Repository</option>
            <option value="INACTIVE_MAPPING">Inactive Mapping</option>
            <option value="NON_BRANCH_REF">Non-Branch Ref</option>
            <option value="DIRECTION_IGNORED">Direction Excluded</option>
          </select>

          <button
            onClick={fetchEvents}
            disabled={loading}
            className="p-1.5 text-zinc-500 hover:text-zinc-800 bg-white hover:bg-zinc-50 border border-zinc-200 rounded-lg shadow-sm transition-colors"
            title="Refresh list"
          >
            <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
          </button>

          {events.length > 0 && (
            <button
              onClick={handleClearAll}
              className="inline-flex items-center space-x-1 px-2.5 py-1.5 text-xs font-medium text-rose-700 bg-rose-50 hover:bg-rose-100 border border-rose-200 rounded-lg shadow-sm transition-colors"
              title="Clear all discarded events"
            >
              <Trash2 className="w-3.5 h-3.5" />
              <span>Clear</span>
            </button>
          )}
        </div>
      </div>

      {/* Events Stream Table */}
      <div className="rounded-2xl border border-zinc-200/90 bg-white shadow-sm overflow-hidden">
        {filteredEvents.length === 0 ? (
          <div className="py-16 text-center space-y-2">
            <div className="w-10 h-10 rounded-full bg-zinc-100 flex items-center justify-center mx-auto text-zinc-400">
              <Inbox className="w-5 h-5" />
            </div>
            <p className="text-sm font-medium text-zinc-700">No discarded webhook events</p>
            <p className="text-xs text-zinc-400 max-w-sm mx-auto">
              {search || filterReason !== 'ALL'
                ? 'No events match your search criteria'
                : 'All inbound webhooks received from AMQP have matched active repository pairs.'}
            </p>
          </div>
        ) : (
          <div className="divide-y divide-zinc-100">
            {filteredEvents.map((evt) => (
              <div
                key={evt.id}
                className="p-4 sm:px-6 hover:bg-zinc-50/70 transition-colors flex flex-col md:flex-row md:items-center justify-between gap-3"
              >
                {/* Event Summary Details */}
                <div className="space-y-1.5">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-xs font-semibold text-zinc-900 flex items-center space-x-1.5 font-mono">
                      <span>{evt.repoFullName || 'Unknown Repository'}</span>
                      {evt.repoFullName && (
                        <a
                          href={`https://github.com/${evt.repoFullName}`}
                          target="_blank"
                          rel="noreferrer"
                          className="text-zinc-400 hover:text-zinc-700"
                        >
                          <ExternalLink className="w-3 h-3" />
                        </a>
                      )}
                    </span>

                    {getReasonBadge(evt.discardReason)}

                    <span className="bg-zinc-100 text-zinc-600 px-1.5 py-0.2 rounded text-[10px] font-medium uppercase font-mono">
                      {evt.eventType}
                    </span>
                  </div>

                  <p className="text-xs text-zinc-600">
                    {evt.details}
                  </p>

                  <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-zinc-400 font-mono">
                    {evt.branch && (
                      <span className="flex items-center space-x-1 text-zinc-600">
                        <GitBranch className="w-3 h-3 text-zinc-400" />
                        <span>{evt.branch}</span>
                      </span>
                    )}

                    {evt.commitSha && (
                      <span className="flex items-center space-x-1 text-zinc-500">
                        <GitCommit className="w-3 h-3 text-zinc-400" />
                        <span>{evt.commitSha.substring(0, 7)}</span>
                      </span>
                    )}

                    {evt.sender && (
                      <span className="flex items-center space-x-1 text-zinc-500">
                        <User className="w-3 h-3 text-zinc-400" />
                        <span>@{evt.sender}</span>
                      </span>
                    )}

                    {evt.commitMessage && (
                      <span className="text-zinc-400 font-sans italic truncate max-w-[240px]" title={evt.commitMessage}>
                        "{evt.commitMessage}"
                      </span>
                    )}

                    <span>•</span>
                    <span className="text-zinc-400">{formatTime(evt.receivedAt)}</span>
                  </div>
                </div>

                {/* Right Quick Actions */}
                <div className="flex items-center space-x-2 shrink-0">
                  {evt.discardReason === 'UNMAPPED_REPOSITORY' && evt.repoFullName && (
                    <button
                      onClick={() =>
                        onConfigurePair({
                          repoAUrl: evt.repoUrl || `https://github.com/${evt.repoFullName}.git`,
                          name: evt.repoFullName.split('/')[1] || evt.repoFullName,
                        })
                      }
                      className="inline-flex items-center space-x-1.5 px-3 py-1.5 rounded-lg bg-zinc-900 hover:bg-zinc-800 text-white text-xs font-medium shadow-sm transition-colors shrink-0"
                    >
                      <Sparkles className="w-3.5 h-3.5 text-amber-300" />
                      <span>Configure Mirror Pair</span>
                    </button>
                  )}

                  <button
                    onClick={() => handleDelete(evt.id)}
                    className="p-1.5 text-zinc-400 hover:text-rose-600 hover:bg-rose-50 rounded-lg transition-colors"
                    title="Delete event record"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};
