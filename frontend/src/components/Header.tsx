import React, { useEffect, useRef, useState } from 'react';
import { NavLink, Link, useLocation, useNavigate } from 'react-router';
import { Settings, RefreshCw, Layers, PlayCircle, ChevronDown, GitCompare, ListOrdered, Cpu, Radio } from 'lucide-react';
import { useMessagingModule } from '../hooks/useMessagingModule';
import { getWebhookBus } from '../services/api';
import { settingsNavItems } from '../pages/settings/settingsNav';

interface HeaderProps {
  onRefresh?: () => void;
}

export const Header: React.FC<HeaderProps> = ({ onRefresh }) => {
  const location = useLocation();
  const navigate = useNavigate();
  const isSettingsActive = location.pathname.startsWith('/settings');
  const [settingsOpen, setSettingsOpen] = useState(false);
  const settingsMenuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setSettingsOpen(false);
  }, [location.pathname]);

  useEffect(() => {
    if (!settingsOpen) return;
    const close = (event: MouseEvent) => {
      if (settingsMenuRef.current && !settingsMenuRef.current.contains(event.target as Node)) {
        setSettingsOpen(false);
      }
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [settingsOpen]);

  const settingsMenu = settingsOpen ? (
    <div className="absolute right-0 top-full z-40 mt-1 w-72 rounded-lg border border-zinc-200 bg-white py-1 shadow-lg">
      {settingsNavItems.map((item) => {
        const Icon = item.icon;
        const selected = location.pathname.startsWith(item.to);
        return (
          <button
            key={item.to}
            type="button"
            onClick={() => navigate(item.to)}
            className={`flex w-full items-start gap-2 px-3 py-2 text-left hover:bg-zinc-50 ${
              selected ? 'bg-zinc-50' : ''
            }`}
          >
            <Icon className={`mt-0.5 h-3.5 w-3.5 shrink-0 ${selected ? 'text-zinc-900' : 'text-zinc-500'}`} />
            <span className="min-w-0">
              <span className={`block text-xs font-medium ${selected ? 'text-zinc-900' : 'text-zinc-800'}`}>{item.label}</span>
              <span className="block text-[11px] leading-4 text-zinc-500">{item.description}</span>
            </span>
          </button>
        );
      })}
    </div>
  ) : null;
  const { messaging } = useMessagingModule();
  const [webhookBus, setWebhookBus] = useState<string>('off');
  useEffect(() => {
    let cancelled = false;
    getWebhookBus()
      .then((status) => {
        if (!cancelled) setWebhookBus(status.provider || 'off');
      })
      .catch(() => {
        if (!cancelled) setWebhookBus('off');
      });
    return () => {
      cancelled = true;
    };
  }, []);
  const queuesLabel = messaging.supportsQueueManager ? 'Queues' : 'Execution';
  const queuesTitle = messaging.supportsQueueManager
    ? 'Durable queue manager (RabbitMQ)'
    : 'In-process execution controls (no external broker)';

  return (
    <header className="border-b border-zinc-200/80 bg-white/80 backdrop-blur-md sticky top-0 z-30 px-6 sm:px-10 py-4">
      <div className="max-w-6xl mx-auto space-y-3">
        {/* Top Row: Brand & Quick Settings */}
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-2 text-base font-semibold text-zinc-900 tracking-tight">
            <Link
              to="/repos"
              className="flex items-center space-x-2 hover:text-zinc-600 transition-colors font-bold text-lg"
            >
              <div className="w-6 h-6 rounded-md bg-zinc-900 text-white flex items-center justify-center">
                <GitCompare className="w-3.5 h-3.5" />
              </div>
              <span>GitMirror</span>
            </Link>
          </div>

          <div className="flex items-center space-x-3 text-xs">
            {onRefresh && (
              <button
                onClick={onRefresh}
                className="p-1.5 text-zinc-400 hover:text-zinc-700 hover:bg-zinc-100 rounded-lg transition-colors"
                title="Refresh status"
              >
                <RefreshCw className="w-3.5 h-3.5" />
              </button>
            )}

            <div className="relative" ref={settingsMenuRef}>
              <button
                type="button"
                onClick={() => setSettingsOpen((open) => !open)}
                className={`flex items-center space-x-1.5 px-2.5 py-1.5 rounded-lg transition-colors font-medium text-xs ${
                  isSettingsActive
                    ? 'bg-zinc-900 text-white'
                    : 'text-zinc-600 hover:text-zinc-900 hover:bg-zinc-100'
                }`}
                aria-expanded={settingsOpen}
                aria-haspopup="menu"
              >
                <Settings className="w-3.5 h-3.5" />
                <span>Settings</span>
                <ChevronDown className="w-3 h-3" />
              </button>
              {settingsMenu}
            </div>
          </div>
        </div>

        {/* Sub-header Navigation Pills */}
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 pt-0.5">
          <p className="text-xs text-zinc-500">
            Enterprise cross-repository Git mirroring engine for GitHub & SCM forks.
          </p>

          <div className="flex items-center space-x-1">
            <NavLink
              to="/repos"
              className={({ isActive }) =>
                `px-3 py-1 rounded-md text-xs font-medium transition-colors ${
                  isActive
                    ? 'bg-zinc-200/80 text-zinc-900 font-semibold'
                    : 'text-zinc-500 hover:text-zinc-900 hover:bg-zinc-100'
                }`
              }
            >
              Repositories
            </NavLink>

            <NavLink
              to="/observability"
              end
              className={({ isActive }) =>
                `flex items-center space-x-1 px-3 py-1 rounded-md text-xs font-medium transition-colors ${
                  isActive
                    ? 'bg-zinc-200/80 text-zinc-900 font-semibold'
                    : 'text-zinc-500 hover:text-zinc-900 hover:bg-zinc-100'
                }`
              }
            >
              <Layers className="w-3 h-3" />
              <span>Observability</span>
            </NavLink>

            <NavLink
              to="/observability/internals"
              className={({ isActive }) =>
                `flex items-center space-x-1 px-3 py-1 rounded-md text-xs font-medium transition-colors ${
                  isActive
                    ? 'bg-zinc-200/80 text-zinc-900 font-semibold'
                    : 'text-zinc-500 hover:text-zinc-900 hover:bg-zinc-100'
                }`
              }
            >
              <Cpu className="w-3 h-3" />
              <span>Internals</span>
            </NavLink>

            {webhookBus === 'kafka' && (
              <NavLink
                to="/kafka"
                title="Incremental Kafka consumer group"
                className={({ isActive }) =>
                  `flex items-center space-x-1 px-3 py-1 rounded-md text-xs font-medium transition-colors ${
                    isActive
                      ? 'bg-zinc-200/80 text-zinc-900 font-semibold'
                      : 'text-zinc-500 hover:text-zinc-900 hover:bg-zinc-100'
                  }`
                }
              >
                <Radio className="w-3 h-3" />
                <span>Kafka</span>
              </NavLink>
            )}

            <NavLink
              to="/queues"
              title={queuesTitle}
              className={({ isActive }) =>
                `flex items-center space-x-1 px-3 py-1 rounded-md text-xs font-medium transition-colors ${
                  isActive
                    ? 'bg-zinc-200/80 text-zinc-900 font-semibold'
                    : 'text-zinc-500 hover:text-zinc-900 hover:bg-zinc-100'
                }`
              }
            >
              <ListOrdered className="w-3 h-3" />
              <span>{queuesLabel}</span>
            </NavLink>

            <NavLink
              to="/simulation"
              className={({ isActive }) =>
                `flex items-center space-x-1 px-3 py-1 rounded-md text-xs font-medium transition-colors ${
                  isActive
                    ? 'bg-zinc-200/80 text-zinc-900 font-semibold'
                    : 'text-zinc-500 hover:text-zinc-900 hover:bg-zinc-100'
                }`
              }
            >
              <PlayCircle className="w-3 h-3" />
              <span>Simulation Lab</span>
            </NavLink>
          </div>
        </div>
      </div>
    </header>
  );
};
