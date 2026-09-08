import React from 'react';
import { NavLink, Outlet } from 'react-router';
import { ShieldCheck, Cpu, HardDrive, Terminal } from 'lucide-react';

export const SettingsLayout: React.FC = () => {
  const navItems = [
    {
      to: '/settings/providers',
      label: 'SCM Providers & Auth',
      description: 'GitHub App, PAT, GitLab, Bitbucket, Origin & Azure',
      icon: ShieldCheck,
    },
    {
      to: '/settings/system-engine',
      label: 'System Engine & Circuit Breaker',
      description: 'Self-healing probers, jittered backoff & concurrency limits',
      icon: Cpu,
    },
    {
      to: '/settings/storage',
      label: 'Storage Tiers & NAS Mounts',
      description: 'NVMe disk quotas, NAS/NFS mounts & LRU eviction',
      icon: HardDrive,
    },
    {
      to: '/settings/logging',
      label: 'Enterprise Logging & SIEM',
      description: 'Splunk HEC, Logstash/ELK, Syslog & dynamic log levels',
      icon: Terminal,
    },
  ];

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <h2 className="text-base font-semibold text-zinc-900">Enterprise Settings & Infrastructure</h2>
        <p className="text-xs text-zinc-500 mt-0.5">
          Configure SCM provider integrations, persistent storage tiers, retry resilience, and SIEM logging sinks.
        </p>
      </div>

      {/* Settings Navigation Bar */}
      <div className="flex border-b border-zinc-200 overflow-x-auto space-x-2">
        {navItems.map((item) => {
          const Icon = item.icon;
          return (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                `flex items-center space-x-2 px-4 py-2.5 text-xs font-medium border-b-2 transition-colors whitespace-nowrap ${
                  isActive
                    ? 'border-zinc-900 text-zinc-900 font-semibold'
                    : 'border-transparent text-zinc-500 hover:text-zinc-700'
                }`
              }
            >
              <Icon className="w-4 h-4" />
              <span>{item.label}</span>
            </NavLink>
          );
        })}
      </div>

      {/* Page Content Outlet */}
      <Outlet />
    </div>
  );
};
