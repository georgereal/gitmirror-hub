import React, { useState } from 'react';
import { PlayCircle, Send, AlertTriangle, Clock, RefreshCw, CheckCircle2, ShieldAlert } from 'lucide-react';
import { RepoMapping, QueueStatus, SyncJob } from '../types';

interface SimulationLabProps {
  mappings: RepoMapping[];
  queueStatus: QueueStatus | null;
  onUpdateSimulationConfig: (config: any) => Promise<void>;
  onEmitSyntheticWebhook: (data: any) => Promise<SyncJob>;
}

export const SimulationLab: React.FC<SimulationLabProps> = ({
  mappings,
  queueStatus,
  onUpdateSimulationConfig,
  onEmitSyntheticWebhook,
}) => {
  const [selectedMappingId, setSelectedMappingId] = useState<number>(mappings[0]?.id || 0);
  const [branch, setBranch] = useState('main');
  const [commitSha, setCommitSha] = useState('a1b2c3d4e5f67890abcdef1234567890abcdef12');
  const [commitMessage, setCommitMessage] = useState('feat(core): synthetic commit for queue resilience verification');
  const [authorName, setAuthorName] = useState('developer');

  const [emitting, setEmitting] = useState(false);
  const [lastEmittedJob, setLastEmittedJob] = useState<SyncJob | null>(null);

  const sim = queueStatus?.simulationStatus || {
    consumerPaused: false,
    simulateTargetDown: false,
    simulateSourceDown: false,
    simulateRateLimit: false,
    artificialDelayMs: 0,
  };

  const handleToggle = async (key: string, currentValue: boolean) => {
    await onUpdateSimulationConfig({ [key]: !currentValue });
  };

  const handleDelayChange = async (delayMs: number) => {
    await onUpdateSimulationConfig({ artificialDelayMs: delayMs });
  };

  const handleEmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedMappingId && mappings.length > 0) {
      setSelectedMappingId(mappings[0].id);
    }
    const mappingIdToUse = selectedMappingId || mappings[0]?.id;
    if (!mappingIdToUse) return;

    setEmitting(true);
    try {
      const job = await onEmitSyntheticWebhook({
        mappingId: mappingIdToUse,
        branch,
        commitSha,
        commitMessage,
        authorName,
      });
      setLastEmittedJob(job);
      // Auto-generate next random sha
      const randSha = Array.from({ length: 40 }, () => Math.floor(Math.random() * 16).toString(16)).join('');
      setCommitSha(randSha);
    } catch (e) {
      console.error('Error emitting synthetic webhook:', e);
    } finally {
      setEmitting(false);
    }
  };

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-base font-semibold text-zinc-900">Resilience & Simulation Lab</h2>
        <p className="text-xs text-zinc-500 mt-0.5">
          Simulate cloud downtime, pause ingestion, or test Dead Letter Queue (DLQ) retry behaviors safely.
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* Left: Fault Injection Toggles */}
        <div className="lg:col-span-6 rounded-2xl border border-zinc-200/90 bg-white p-6 shadow-sm space-y-4">
          <h3 className="text-sm font-semibold text-zinc-900 border-b border-zinc-100 pb-3">
            Chaos Fault Injection Controls
          </h3>

          <div className="space-y-3 text-xs">
            {/* Consumer Pause */}
            <div className="p-3.5 rounded-xl bg-zinc-50/70 border border-zinc-200/80 flex items-center justify-between">
              <div>
                <span className="font-semibold text-zinc-800">Pause Queue Consumer</span>
                <p className="text-[11px] text-zinc-500 mt-0.5">
                  Webhooks will accumulate in RabbitMQ until resumed.
                </p>
              </div>
              <button
                onClick={() => handleToggle('consumerPaused', sim.consumerPaused)}
                className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${
                  sim.consumerPaused
                    ? 'bg-amber-500 text-white'
                    : 'bg-zinc-200 text-zinc-700 hover:bg-zinc-300'
                }`}
              >
                {sim.consumerPaused ? 'Paused' : 'Active'}
              </button>
            </div>

            {/* Target Down */}
            <div className="p-3.5 rounded-xl bg-zinc-50/70 border border-zinc-200/80 flex items-center justify-between">
              <div>
                <span className="font-semibold text-zinc-800">Simulate Target Repo Outage</span>
                <p className="text-[11px] text-zinc-500 mt-0.5">
                  Causes Git pushes to fail, testing automatic retry & DLQ redirection.
                </p>
              </div>
              <button
                onClick={() => handleToggle('simulateTargetDown', sim.simulateTargetDown)}
                className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${
                  sim.simulateTargetDown
                    ? 'bg-rose-600 text-white'
                    : 'bg-zinc-200 text-zinc-700 hover:bg-zinc-300'
                }`}
              >
                {sim.simulateTargetDown ? 'Injected' : 'Off'}
              </button>
            </div>

            {/* Rate Limit */}
            <div className="p-3.5 rounded-xl bg-zinc-50/70 border border-zinc-200/80 flex items-center justify-between">
              <div>
                <span className="font-semibold text-zinc-800">Simulate GitHub 429 Rate Limit</span>
                <p className="text-[11px] text-zinc-500 mt-0.5">
                  Simulates API throttling and backoff policies.
                </p>
              </div>
              <button
                onClick={() => handleToggle('simulateRateLimit', sim.simulateRateLimit)}
                className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${
                  sim.simulateRateLimit
                    ? 'bg-rose-600 text-white'
                    : 'bg-zinc-200 text-zinc-700 hover:bg-zinc-300'
                }`}
              >
                {sim.simulateRateLimit ? 'Injected' : 'Off'}
              </button>
            </div>

            {/* Artificial Latency */}
            <div className="p-3.5 rounded-xl bg-zinc-50/70 border border-zinc-200/80 space-y-2">
              <div className="flex items-center justify-between">
                <span className="font-semibold text-zinc-800">Artificial Network Latency</span>
                <span className="font-mono text-zinc-600">{sim.artificialDelayMs}ms</span>
              </div>
              <input
                type="range"
                min="0"
                max="5000"
                step="500"
                value={sim.artificialDelayMs}
                onChange={(e) => handleDelayChange(parseInt(e.target.value, 10))}
                className="w-full accent-zinc-900"
              />
            </div>
          </div>
        </div>

        {/* Right: Synthetic Webhook Emitter */}
        <div className="lg:col-span-6 rounded-2xl border border-zinc-200/90 bg-white p-6 shadow-sm space-y-4">
          <h3 className="text-sm font-semibold text-zinc-900 border-b border-zinc-100 pb-3">
            Dispatch Synthetic GitHub Webhook
          </h3>

          <form onSubmit={handleEmit} className="space-y-3 text-xs">
            <div>
              <label className="block text-zinc-700 font-medium mb-1">Target Repository Pair</label>
              <select
                value={selectedMappingId}
                onChange={(e) => setSelectedMappingId(parseInt(e.target.value, 10))}
                className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 text-xs focus:outline-none focus:border-zinc-400"
              >
                {mappings.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.name} ({m.repoAUrl.replace('https://github.com/', '')})
                  </option>
                ))}
              </select>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="block text-zinc-700 font-medium mb-1">Branch</label>
                <input
                  type="text"
                  value={branch}
                  onChange={(e) => setBranch(e.target.value)}
                  className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-1.5 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                />
              </div>
              <div>
                <label className="block text-zinc-700 font-medium mb-1">Author</label>
                <input
                  type="text"
                  value={authorName}
                  onChange={(e) => setAuthorName(e.target.value)}
                  className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-1.5 text-zinc-900 text-xs focus:outline-none focus:border-zinc-400"
                />
              </div>
            </div>

            <div>
              <label className="block text-zinc-700 font-medium mb-1">Commit SHA</label>
              <input
                type="text"
                value={commitSha}
                onChange={(e) => setCommitSha(e.target.value)}
                className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-1.5 text-zinc-900 font-mono text-[11px] focus:outline-none focus:border-zinc-400"
              />
            </div>

            <div>
              <label className="block text-zinc-700 font-medium mb-1">Commit Message</label>
              <input
                type="text"
                value={commitMessage}
                onChange={(e) => setCommitMessage(e.target.value)}
                className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-1.5 text-zinc-900 text-xs focus:outline-none focus:border-zinc-400"
              />
            </div>

            {lastEmittedJob && (
              <div className="p-2.5 rounded-lg bg-emerald-50 border border-emerald-200 text-emerald-800 text-[11px] flex items-center space-x-1.5">
                <CheckCircle2 className="w-3.5 h-3.5 text-emerald-600 shrink-0" />
                <span>Job #{lastEmittedJob.id} enqueued to {lastEmittedJob.pairName} (Status: {lastEmittedJob.status})</span>
              </div>
            )}

            <div className="pt-2">
              <button
                type="submit"
                disabled={emitting || mappings.length === 0}
                className="w-full flex items-center justify-center space-x-1.5 bg-zinc-900 hover:bg-zinc-800 text-white py-2 rounded-lg font-medium text-xs transition-colors shadow-sm disabled:opacity-40"
              >
                <Send className="w-3.5 h-3.5" />
                <span>{emitting ? 'Publishing Event...' : 'Inject Webhook Event into Queue'}</span>
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
};
