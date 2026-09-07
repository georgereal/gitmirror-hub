import React from 'react';
import { NavLink, Link, useLocation } from 'react-router-dom';
import { Settings, RefreshCw, Layers, PlayCircle, ShieldCheck, GitCompare, ListOrdered, Cpu } from 'lucide-react';

interface HeaderProps {
  onRefresh?: () => void;
}

export const Header: React.FC<HeaderProps> = ({ onRefresh }) => {
  const location = useLocation();
  const isSettingsActive = location.pathname.startsWith('/settings');

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

            <Link
              to="/settings/providers"
              className={`flex items-center space-x-1.5 px-2.5 py-1.5 rounded-lg transition-colors font-medium text-xs ${
                isSettingsActive
                  ? 'bg-zinc-900 text-white'
                  : 'text-zinc-600 hover:text-zinc-900 hover:bg-zinc-100'
              }`}
            >
              <Settings className="w-3.5 h-3.5" />
              <span>Settings</span>
            </Link>
          </div>
        </div>

        {/* Sub-header Navigation Pills */}
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 pt-0.5">
          <p className="text-xs text-zinc-500">
            Enterprise cross-repository Git mirroring engine for GitHub, GitLab, Bitbucket & SCM forks.
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

            <NavLink
              to="/queues"
              className={({ isActive }) =>
                `flex items-center space-x-1 px-3 py-1 rounded-md text-xs font-medium transition-colors ${
                  isActive
                    ? 'bg-zinc-200/80 text-zinc-900 font-semibold'
                    : 'text-zinc-500 hover:text-zinc-900 hover:bg-zinc-100'
                }`
              }
            >
              <ListOrdered className="w-3 h-3" />
              <span>Queues</span>
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

            <NavLink
              to="/settings/providers"
              className={({ isActive }) =>
                `flex items-center space-x-1 px-3 py-1 rounded-md text-xs font-medium transition-colors ${
                  isActive || isSettingsActive
                    ? 'bg-zinc-200/80 text-zinc-900 font-semibold'
                    : 'text-zinc-500 hover:text-zinc-900 hover:bg-zinc-100'
                }`
              }
            >
              <ShieldCheck className="w-3 h-3" />
              <span>Settings</span>
            </NavLink>
          </div>
        </div>
      </div>
    </header>
  );
};
