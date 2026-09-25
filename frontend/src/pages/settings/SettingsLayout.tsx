import React from 'react';
import { Outlet, useLocation } from 'react-router';
import { Database } from 'lucide-react';
import { usePersistenceModule } from '../../hooks/usePersistenceModule';
import { settingsNavItems } from './settingsNav';

export const SettingsLayout: React.FC = () => {
  const { persistence, loading: persistenceLoading } = usePersistenceModule();
  const { pathname } = useLocation();
  const current = settingsNavItems.find((item) => pathname.startsWith(item.to)) ?? settingsNavItems[0];

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-base font-semibold text-zinc-900">{current.label}</h2>
        <p className="text-xs text-zinc-500 mt-0.5">{current.description}</p>
        <p className="mt-2 inline-flex items-start gap-1.5 text-[11px] text-zinc-600 bg-zinc-50 border border-zinc-200 rounded-lg px-2.5 py-1.5">
          <Database className="w-3.5 h-3.5 text-zinc-500 shrink-0 mt-0.5" />
          <span>
            <span className="font-semibold text-zinc-900">
              Store · {persistenceLoading ? '…' : persistence.displayName}
            </span>
            {!persistenceLoading && (
              <span className="text-zinc-500"> — {persistence.description}</span>
            )}
          </span>
        </p>
      </div>

      <Outlet />
    </div>
  );
};
