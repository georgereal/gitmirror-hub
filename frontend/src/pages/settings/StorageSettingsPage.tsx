import React, { useState, useEffect } from 'react';
import {
  HardDrive, Database, RefreshCw, CheckCircle2, AlertCircle, Save, Trash2
} from 'lucide-react';
import { InfoTooltip } from '../../components/InfoTooltip';
import { StorageStatusResponse, SystemEngineConfig, NasPathTestResult } from '../../types';
import {
  getStorageStatus,
  triggerStorageEviction,
  getSystemEngineConfig,
  saveSystemEngineConfig,
  testNasPath
} from '../../services/api';

export const StorageSettingsPage: React.FC = () => {
  const [storageStatus, setStorageStatus] = useState<StorageStatusResponse | null>(null);
  const [localDir, setLocalDir] = useState('/tmp/git-utility-mirrors');
  const [nasDir, setNasDir] = useState('/tmp/git-utility-nas-mirrors');
  const [maxDiskQuotaMb, setMaxDiskQuotaMb] = useState(51200);
  const [maxCachedRepos, setMaxCachedRepos] = useState(1000);
  const [retentionHours, setRetentionHours] = useState(72);

  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(false);
  const [evicting, setEvicting] = useState(false);
  const [testingNas, setTestingNas] = useState(false);
  const [nasTestResult, setNasTestResult] = useState<NasPathTestResult | null>(null);
  const [feedback, setFeedback] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

  useEffect(() => {
    loadData();
  }, []);

  const loadData = async () => {
    setLoading(true);
    try {
      const [storage, sysEngine] = await Promise.allSettled([
        getStorageStatus(),
        getSystemEngineConfig(),
      ]);

      if (storage.status === 'fulfilled') {
        setStorageStatus(storage.value);
      }
      if (sysEngine.status === 'fulfilled' && sysEngine.value) {
        const s = sysEngine.value;
        setLocalDir(s.localDir || '/tmp/git-utility-mirrors');
        setNasDir(s.nasDir || '/tmp/git-utility-nas-mirrors');
        setMaxDiskQuotaMb(s.maxDiskQuotaMb || 51200);
        setMaxCachedRepos(s.maxCachedRepos || 1000);
        setRetentionHours(s.retentionHours || 72);
      }
    } catch (e) {
      console.error('Error loading storage settings:', e);
    } finally {
      setLoading(false);
    }
  };

  const handleRunEviction = async () => {
    setEvicting(true);
    try {
      await triggerStorageEviction();
      const updated = await getStorageStatus();
      setStorageStatus(updated);
      setFeedback({ type: 'success', message: 'LRU cache eviction evaluation completed successfully!' });
      setTimeout(() => setFeedback(null), 4000);
    } catch (e: any) {
      setFeedback({ type: 'error', message: 'Eviction failed: ' + (e.message || 'Unknown error') });
    } finally {
      setEvicting(false);
    }
  };

  const handleTestNasPath = async () => {
    setTestingNas(true);
    setNasTestResult(null);
    try {
      const res = await testNasPath(nasDir);
      setNasTestResult(res);
    } catch (e: any) {
      setNasTestResult({
        valid: false,
        path: nasDir,
        message: 'Test failed: ' + (e.response?.data?.message || e.message || 'Could not connect to directory')
      });
    } finally {
      setTestingNas(false);
    }
  };

  const handleSaveStorage = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setFeedback(null);
    try {
      const payload: Partial<SystemEngineConfig> = {
        localDir,
        nasDir,
        maxDiskQuotaMb,
        maxCachedRepos,
        retentionHours,
      };

      await saveSystemEngineConfig(payload);
      const storage = await getStorageStatus();
      setStorageStatus(storage);

      setFeedback({
        type: 'success',
        message: 'Storage paths & quota configurations saved and hot-reloaded!'
      });
      setTimeout(() => setFeedback(null), 4000);
    } catch (err: any) {
      setFeedback({
        type: 'error',
        message: err.response?.data?.message || err.message || 'Failed to save storage settings'
      });
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* Left Form: Storage Configuration */}
        <div className="lg:col-span-7 space-y-4">
          <div className="rounded-2xl border border-zinc-200/90 bg-white p-6 shadow-sm">
            <form onSubmit={handleSaveStorage} className="space-y-5 text-xs">
              <div className="flex items-center space-x-2 border-b border-zinc-200/60 pb-2.5">
                <HardDrive className="w-4 h-4 text-blue-600" />
                <span className="font-semibold text-zinc-900 text-sm">Storage Tier Paths & Network Mounts</span>
                <InfoTooltip
                  title="Enterprise Storage Tiering"
                  badge="Storage"
                  whatIsIt="Multi-tier repository storage management spanning local high-speed NVMe drives, elastic NAS/NFS mounts, and zero-disk ephemeral mirrors."
                  howItWorks="Directs each repository's bare Git database to the appropriate storage tier while enforcing global local NVMe quotas with automated LRU cache eviction."
                />
              </div>

              <div>
                <div className="flex items-center space-x-1 mb-1">
                  <label className="block text-zinc-700 font-medium">Local NVMe Base Cache Directory</label>
                  <InfoTooltip
                    title="Local NVMe Base Cache Path"
                    whatIsIt="Local filesystem directory storing bare Git repositories (pair-<id>.git) for HOT and AUTO_LRU tiers."
                    howItWorks="Maintains bare Git caches on local SSD/NVMe for sub-5ms commit reachability checks, fast delta fetches, and local packfile reuse."
                    recommended="/tmp/git-utility-mirrors or /var/data/git-mirrors"
                  />
                </div>
                <input
                  type="text"
                  value={localDir}
                  onChange={(e) => setLocalDir(e.target.value)}
                  placeholder="/tmp/git-utility-mirrors"
                  className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-1.5 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                  required
                />
              </div>

              <div>
                <div className="flex items-center justify-between mb-1">
                  <div className="flex items-center space-x-1">
                    <label className="block text-zinc-700 font-medium">NAS / NFS Mount Path</label>
                    <InfoTooltip
                      title="NAS / NFS Mount Path"
                      whatIsIt="The filesystem mount path for shared Network Attached Storage (e.g. AWS EFS, Azure NetApp, NFS v4)."
                      howItWorks="Used by repo mappings assigned the NAS_MOUNT tier. Offloads disk space from container/host instances to scalable network storage pools."
                      recommended="/mnt/nas/git-mirrors or /mnt/efs/git-mirrors"
                    />
                  </div>
                  <button
                    type="button"
                    onClick={handleTestNasPath}
                    disabled={testingNas || !nasDir}
                    className="flex items-center space-x-1 text-blue-600 hover:text-blue-700 font-medium text-[11px] disabled:opacity-50"
                  >
                    <RefreshCw className={`w-3 h-3 ${testingNas ? 'animate-spin' : ''}`} />
                    <span>Test Mount Access</span>
                  </button>
                </div>
                <input
                  type="text"
                  value={nasDir}
                  onChange={(e) => setNasDir(e.target.value)}
                  placeholder="/mnt/nas/git-mirrors"
                  className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-1.5 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                  required
                />

                {nasTestResult && (
                  <div className={`mt-2 p-2 rounded-lg border text-[11px] flex items-center space-x-1.5 ${
                    nasTestResult.valid ? 'bg-emerald-50 border-emerald-200 text-emerald-800' : 'bg-rose-50 border-rose-200 text-rose-800'
                  }`}>
                    {nasTestResult.valid ? <CheckCircle2 className="w-3.5 h-3.5 text-emerald-600 shrink-0" /> : <AlertCircle className="w-3.5 h-3.5 text-rose-600 shrink-0" />}
                    <span>{nasTestResult.message}</span>
                  </div>
                )}
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 pt-1">
                <div>
                  <div className="flex items-center space-x-1 mb-1">
                    <label className="block text-zinc-700 font-medium">Max Disk Quota (MB)</label>
                    <InfoTooltip
                      title="Max Disk Quota (MB)"
                      whatIsIt="Maximum cumulative disk space allocated for bare Git caches on the local drive."
                      howItWorks="When total storage exceeds this threshold, the LRU eviction cleaner automatically deletes the least-recently used AUTO_LRU repositories."
                      recommended="51200 MB (50 GB) or appropriate cluster disk allocation"
                    />
                  </div>
                  <input
                    type="number"
                    min="1024"
                    step="1024"
                    value={maxDiskQuotaMb}
                    onChange={(e) => setMaxDiskQuotaMb(parseInt(e.target.value) || 51200)}
                    className="w-full bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                  />
                  <span className="text-[10px] text-zinc-400">{(maxDiskQuotaMb / 1024).toFixed(1)} GB local quota</span>
                </div>

                <div>
                  <div className="flex items-center space-x-1 mb-1">
                    <label className="block text-zinc-700 font-medium">Max Cached Repos</label>
                    <InfoTooltip
                      title="Max Cached Repositories"
                      whatIsIt="The upper bound on the total number of distinct bare repository caches retained on disk simultaneously."
                      howItWorks="Enforces repository density caps on worker instances. When exceeded, the oldest inactive repos are pruned."
                      recommended="500 to 2000 repos"
                    />
                  </div>
                  <input
                    type="number"
                    min="10"
                    value={maxCachedRepos}
                    onChange={(e) => setMaxCachedRepos(parseInt(e.target.value) || 1000)}
                    className="w-full bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                  />
                  <span className="text-[10px] text-zinc-400">LRU cache ceiling</span>
                </div>

                <div>
                  <div className="flex items-center space-x-1 mb-1">
                    <label className="block text-zinc-700 font-medium">Retention Window (Hours)</label>
                    <InfoTooltip
                      title="Inactive Cache Retention"
                      whatIsIt="The minimum number of idle hours required before an unaccessed repository becomes eligible for LRU cache eviction."
                      howItWorks="Protects recently used repositories from premature eviction while ensuring stale, inactive repositories are pruned."
                      recommended="48 to 168 hours (2-7 days)"
                    />
                  </div>
                  <input
                    type="number"
                    min="1"
                    value={retentionHours}
                    onChange={(e) => setRetentionHours(parseInt(e.target.value) || 72)}
                    className="w-full bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                  />
                  <span className="text-[10px] text-zinc-400">Cold tier age</span>
                </div>
              </div>

              {feedback && (
                <div className={`p-3 rounded-lg border text-xs flex items-center space-x-2 ${
                  feedback.type === 'success' ? 'bg-emerald-50 border-emerald-200 text-emerald-800' : 'bg-rose-50 border-rose-200 text-rose-800'
                }`}>
                  {feedback.type === 'success' ? <CheckCircle2 className="w-4 h-4 shrink-0 text-emerald-600" /> : <AlertCircle className="w-4 h-4 shrink-0 text-rose-600" />}
                  <span>{feedback.message}</span>
                </div>
              )}

              <div className="flex justify-end pt-2">
                <button
                  type="submit"
                  disabled={saving}
                  className="inline-flex items-center space-x-1.5 bg-zinc-900 hover:bg-zinc-800 text-white text-xs px-4 py-2 rounded-lg font-medium shadow-sm transition-colors disabled:opacity-50"
                >
                  <Save className="w-3.5 h-3.5" />
                  <span>{saving ? 'Saving...' : 'Save Storage Parameters'}</span>
                </button>
              </div>
            </form>
          </div>
        </div>

        {/* Right Info: Live Telemetry & Eviction */}
        <div className="lg:col-span-5 space-y-4">
          <div className="rounded-2xl border border-zinc-200/90 bg-white p-6 shadow-sm space-y-4">
            <div className="flex items-center justify-between border-b border-zinc-100 pb-3">
              <div className="flex items-center space-x-2">
                <Database className="w-4 h-4 text-emerald-600" />
                <h3 className="text-sm font-semibold text-zinc-900">Live Disk Telemetry & LRU Cleaner</h3>
              </div>
              <button
                onClick={loadData}
                disabled={loading}
                className="text-xs text-zinc-500 hover:text-zinc-900 flex items-center space-x-1"
              >
                <RefreshCw className={`w-3 h-3 ${loading ? 'animate-spin' : ''}`} />
                <span>Refresh</span>
              </button>
            </div>

            {storageStatus ? (
              <div className="space-y-4 text-xs">
                <div>
                  <div className="flex justify-between text-zinc-600 mb-1 font-medium">
                    <span>Local Disk Quota:</span>
                    <span className="font-mono text-zinc-900 font-semibold">
                      {storageStatus.localUsedFormatted} / {storageStatus.maxDiskQuotaFormatted} ({storageStatus.quotaUsedPercent.toFixed(1)}%)
                    </span>
                  </div>
                  <div className="w-full bg-zinc-100 rounded-full h-2 overflow-hidden border border-zinc-200/50">
                    <div
                      className={`h-2 rounded-full transition-all duration-500 ${
                        storageStatus.quotaUsedPercent > 90 ? 'bg-rose-500' :
                        storageStatus.quotaUsedPercent > 70 ? 'bg-amber-500' : 'bg-emerald-500'
                      }`}
                      style={{ width: `${Math.min(100, Math.max(3, storageStatus.quotaUsedPercent))}%` }}
                    />
                  </div>
                </div>

                <div className="grid grid-cols-2 gap-2 text-[11px]">
                  <div className="p-2.5 bg-zinc-50 rounded-lg border border-zinc-100">
                    <span className="text-zinc-400 block">Total Cached Repos</span>
                    <span className="font-semibold text-zinc-800 text-sm font-mono">{storageStatus.totalCachedRepos}</span>
                  </div>
                  <div className="p-2.5 bg-zinc-50 rounded-lg border border-zinc-100">
                    <span className="text-zinc-400 block">Auto LRU Repos</span>
                    <span className="font-semibold text-zinc-800 text-sm font-mono">{storageStatus.autoLruReposCount}</span>
                  </div>
                  <div className="p-2.5 bg-zinc-50 rounded-lg border border-zinc-100">
                    <span className="text-zinc-400 block">Hot Pinned Repos</span>
                    <span className="font-semibold text-zinc-800 text-sm font-mono">{storageStatus.hotReposCount}</span>
                  </div>
                  <div className="p-2.5 bg-zinc-50 rounded-lg border border-zinc-100">
                    <span className="text-zinc-400 block">NAS Volume Repos</span>
                    <span className="font-semibold text-zinc-800 text-sm font-mono">{storageStatus.nasReposCount}</span>
                  </div>
                </div>

                <div className="pt-2 border-t border-zinc-100 flex items-center justify-between">
                  <div>
                    <span className="text-zinc-700 font-medium block">Manual LRU Cache Pruning</span>
                    <span className="text-[10px] text-zinc-400">Evaluates cold repositories exceeding retention window</span>
                  </div>
                  <button
                    type="button"
                    onClick={handleRunEviction}
                    disabled={evicting}
                    className="px-3 py-1.5 bg-zinc-100 hover:bg-zinc-200 text-zinc-800 rounded-lg font-medium text-xs flex items-center space-x-1.5 transition-colors disabled:opacity-50"
                  >
                    <Trash2 className={`w-3.5 h-3.5 ${evicting ? 'animate-spin' : ''}`} />
                    <span>{evicting ? 'Pruning...' : 'Evict Now'}</span>
                  </button>
                </div>
              </div>
            ) : (
              <div className="text-center py-6 text-zinc-400 text-xs">
                Loading disk metrics...
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
