import { Cpu, HardDrive, Layers, Lock, Shield, ShieldCheck, Terminal, ToggleLeft, type LucideIcon } from 'lucide-react';

export interface SettingsNavItem {
  to: string;
  label: string;
  description: string;
  icon: LucideIcon;
}

export const settingsNavItems: SettingsNavItem[] = [
  {
    to: '/settings/providers',
    label: 'SCM Providers & Auth',
    description: 'GitHub App, PAT, and enabled SCM providers',
    icon: ShieldCheck,
  },
  {
    to: '/settings/dr',
    label: 'Disaster recovery',
    description: 'Provider-to-provider failover, org and enterprise locks',
    icon: Shield,
  },
  {
    to: '/settings/write-authority',
    label: 'Replica rulesets',
    description: 'Enforce read-only rulesets on mirror replicas',
    icon: Lock,
  },
  {
    to: '/settings/feature-toggles',
    label: 'Feature toggles',
    description: 'Public repos, optional providers, product capability switches',
    icon: ToggleLeft,
  },
  {
    to: '/settings/metadata',
    label: 'Metadata sync',
    description: 'Pull requests, releases, CI checks, and Git LFS',
    icon: Layers,
  },
  {
    to: '/settings/system-engine',
    label: 'System engine',
    description: 'Self-healing probers, jittered backoff, and concurrency limits',
    icon: Cpu,
  },
  {
    to: '/settings/storage',
    label: 'Storage',
    description: 'NVMe disk quotas, NAS/NFS mounts, and LRU eviction',
    icon: HardDrive,
  },
  {
    to: '/settings/logging',
    label: 'Logging',
    description: 'Splunk HEC, Logstash/ELK, Syslog, and dynamic log levels',
    icon: Terminal,
  },
];
