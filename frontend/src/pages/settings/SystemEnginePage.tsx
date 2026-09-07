import React, { useState, useEffect } from 'react';
import {
  Cpu, Activity, Zap, PlayCircle, RotateCcw, Sliders, CheckCircle2, AlertCircle, Save
} from 'lucide-react';
import { InfoTooltip } from '../../components/InfoTooltip';
import { SystemEngineConfig, CircuitBreakerResetResult } from '../../types';
import { getSystemEngineConfig, saveSystemEngineConfig, probeAndResetCircuitBreaker } from '../../services/api';

export const SystemEnginePage: React.FC = () => {
  const [systemConfig, setSystemConfig] = useState<SystemEngineConfig | null>(null);

  const [maxConcurrentPushes, setMaxConcurrentPushes] = useState(5);
  const [metadataSyncIntervalSeconds, setMetadataSyncIntervalSeconds] = useState(30);
  const [maxRetryAttempts, setMaxRetryAttempts] = useState(3);
  const [retryInitialIntervalMs, setRetryInitialIntervalMs] = useState(3000);
  const [retryMultiplier, setRetryMultiplier] = useState(2.0);
  const [retryMaxIntervalMs, setRetryMaxIntervalMs] = useState(30000);
  const [circuitBreakerFailureThreshold, setCircuitBreakerFailureThreshold] = useState(5);
  const [circuitBreakerResetTimeoutSeconds, setCircuitBreakerResetTimeoutSeconds] = useState(30);
  const [suppressMirrorActionsTriggers, setSuppressMirrorActionsTriggers] = useState(true);

  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(false);
  const [resettingCircuit, setResettingCircuit] = useState(false);
  const [feedback, setFeedback] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

  useEffect(() => {
    loadConfig();
  }, []);

  const loadConfig = async () => {
    setLoading(true);
    try {
      const s = await getSystemEngineConfig();
      if (s) {
        setSystemConfig(s);
        setMaxConcurrentPushes(s.maxConcurrentPushes || 5);
        setMetadataSyncIntervalSeconds(s.metadataSyncIntervalSeconds || 30);
        setMaxRetryAttempts(s.maxRetryAttempts || 3);
        setRetryInitialIntervalMs(s.retryInitialIntervalMs || 3000);
        setRetryMultiplier(s.retryMultiplier || 2.0);
        setRetryMaxIntervalMs(s.retryMaxIntervalMs || 30000);
        setCircuitBreakerFailureThreshold(s.circuitBreakerFailureThreshold || 5);
        setCircuitBreakerResetTimeoutSeconds(s.circuitBreakerResetTimeoutSeconds || 30);
        setSuppressMirrorActionsTriggers(s.suppressMirrorActionsTriggers !== false);
      }
    } catch (e) {
      console.error('Error loading engine config:', e);
    } finally {
      setLoading(false);
    }
  };

  const handleResetCircuitBreaker = async (forceReset = false) => {
    setResettingCircuit(true);
    try {
      const res: CircuitBreakerResetResult = await probeAndResetCircuitBreaker(forceReset);
      const updated = await getSystemEngineConfig();
      setSystemConfig(updated);
      if (res.success) {
        setFeedback({ type: 'success', message: 'Circuit Breaker reset: ' + res.message });
      } else {
        setFeedback({ type: 'error', message: 'Probe result: ' + res.message });
      }
      setTimeout(() => setFeedback(null), 5000);
    } catch (e: any) {
      setFeedback({ type: 'error', message: 'Circuit breaker reset failed: ' + (e.message || 'Unknown error') });
    } finally {
      setResettingCircuit(false);
    }
  };

  const handleSaveSystemConfig = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setFeedback(null);
    try {
      const payload: Partial<SystemEngineConfig> = {
        maxConcurrentPushes,
        metadataSyncIntervalSeconds,
        maxRetryAttempts,
        retryInitialIntervalMs,
        retryMultiplier,
        retryMaxIntervalMs,
        circuitBreakerFailureThreshold,
        circuitBreakerResetTimeoutSeconds,
        suppressMirrorActionsTriggers,
      };

      const saved = await saveSystemEngineConfig(payload);
      setSystemConfig(saved);

      setFeedback({
        type: 'success',
        message: 'System Engine parameters updated and hot-reloaded dynamically!'
      });
      setTimeout(() => setFeedback(null), 4000);
    } catch (err: any) {
      setFeedback({
        type: 'error',
        message: err.response?.data?.message || err.message || 'Failed to save system engine settings'
      });
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-6">
      <div className="rounded-2xl border border-zinc-200/90 bg-white p-6 shadow-sm">
        <form onSubmit={handleSaveSystemConfig} className="space-y-6 text-xs">
          
          {/* 1. Circuit Breaker Telemetry & Admin Controls */}
          <div className="rounded-xl border border-zinc-200 bg-zinc-50/70 p-4 space-y-3">
            <div className="flex items-center justify-between border-b border-zinc-200/60 pb-2.5">
              <div className="flex items-center space-x-2">
                <Activity className="w-4 h-4 text-emerald-600" />
                <span className="font-semibold text-zinc-900">Self-Healing Circuit Breaker Telemetry & Admin Controls</span>
                <InfoTooltip
                  title="Self-Healing Circuit Breaker"
                  badge="Resilience"
                  whatIsIt="Safety mechanism that automatically pauses RabbitMQ consumers during sustained downstream outages, preserving message durability."
                  howItWorks="Transitions from CLOSED to OPEN after consecutive permanent failures. In OPEN state, automated background probers test downstream health and auto-recover to CLOSED once connectivity restores."
                  recommended="Threshold: 5 failures, Timeout: 30s"
                />
              </div>
              <div className="flex items-center space-x-2">
                <span className={`px-2.5 py-0.5 rounded-full text-[10px] font-semibold border uppercase tracking-wider ${
                  systemConfig?.circuitBreakerState === 'CLOSED' ? 'bg-emerald-50 text-emerald-700 border-emerald-200' :
                  systemConfig?.circuitBreakerState === 'HALF_OPEN' ? 'bg-amber-50 text-amber-700 border-amber-200 animate-pulse' :
                  'bg-rose-50 text-rose-700 border-rose-200'
                }`}>
                  State: {systemConfig?.circuitBreakerState || 'CLOSED'}
                </span>
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
              <div className="p-3 bg-white border border-zinc-200/80 rounded-xl space-y-1">
                <div className="flex items-center justify-between">
                  <span className="text-zinc-500 text-[11px]">Current Failures:</span>
                  <InfoTooltip
                    title="Consecutive Permanent Failures"
                    whatIsIt="Count of jobs that have exhausted all retry attempts and entered the DLQ."
                    howItWorks="When this reaches the configured Failure Threshold, the circuit trips OPEN to protect downstream SCMs."
                  />
                </div>
                <div className="text-sm font-semibold font-mono text-zinc-800">
                  {systemConfig?.currentConsecutiveFailures || 0} / {circuitBreakerFailureThreshold}
                </div>
              </div>

              <div className="p-3 bg-white border border-zinc-200/80 rounded-xl space-y-1">
                <div className="flex items-center justify-between">
                  <span className="text-zinc-500 text-[11px]">Last Transition:</span>
                  <InfoTooltip
                    title="Last State Transition Timestamp"
                    whatIsIt="Timestamp when the circuit breaker last switched between CLOSED, OPEN, or HALF_OPEN states."
                  />
                </div>
                <div className="text-xs font-mono text-zinc-700 truncate">
                  {systemConfig?.lastStateTransitionAt ? new Date(systemConfig.lastStateTransitionAt).toLocaleTimeString() : 'Normal'}
                </div>
              </div>

              <div className="p-3 bg-white border border-zinc-200/80 rounded-xl flex items-center justify-between">
                <div>
                  <div className="flex items-center space-x-1">
                    <span className="text-zinc-500 text-[11px] block">Admin Override:</span>
                    <InfoTooltip
                      title="Admin Health Probe & Reset"
                      whatIsIt="Manually sends a health probe to downstream SCM endpoints (e.g. GitHub API) and resets the circuit breaker to CLOSED."
                      howItWorks="Use when an external network incident has been resolved and you wish to immediately resume queue processing."
                    />
                  </div>
                  <span className="text-[10px] text-zinc-400">Test & Reset Breaker</span>
                </div>
                <button
                  type="button"
                  onClick={() => handleResetCircuitBreaker(true)}
                  disabled={resettingCircuit}
                  className="px-2.5 py-1 bg-zinc-900 hover:bg-zinc-800 text-white rounded-md text-[11px] font-medium flex items-center space-x-1 disabled:opacity-50 shadow-xs"
                >
                  <RotateCcw className={`w-3 h-3 ${resettingCircuit ? 'animate-spin' : ''}`} />
                  <span>Test & Reset</span>
                </button>
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 pt-1">
              <div>
                <div className="flex items-center space-x-1 mb-1">
                  <label className="block text-zinc-700 font-medium">Failure Threshold</label>
                  <InfoTooltip
                    title="Circuit Breaker Failure Threshold"
                    whatIsIt="Number of consecutive permanent job failures (dead letters) required before the circuit breaker trips OPEN."
                    howItWorks="Higher values tolerate intermittent provider errors; lower values react faster to sustained infrastructure outages."
                    recommended="3 to 10 failures"
                  />
                </div>
                <input
                  type="number"
                  min="1"
                  max="50"
                  value={circuitBreakerFailureThreshold}
                  onChange={(e) => setCircuitBreakerFailureThreshold(parseInt(e.target.value) || 5)}
                  className="w-full bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                />
                <span className="text-[10px] text-zinc-400">Consecutive failures before consumer pauses</span>
              </div>

              <div>
                <div className="flex items-center space-x-1 mb-1">
                  <label className="block text-zinc-700 font-medium">Self-Healing Probe Interval (Sec)</label>
                  <InfoTooltip
                    title="Self-Healing Probe Interval"
                    whatIsIt="How frequently the background health checker probes downstream SCMs when the circuit is OPEN."
                    howItWorks="Upon successful probe response, the circuit transitions HALF_OPEN -> CLOSED and resumes consumer listeners."
                    recommended="15 to 60 seconds"
                  />
                </div>
                <input
                  type="number"
                  min="5"
                  max="300"
                  value={circuitBreakerResetTimeoutSeconds}
                  onChange={(e) => setCircuitBreakerResetTimeoutSeconds(parseInt(e.target.value) || 30)}
                  className="w-full bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                />
                <span className="text-[10px] text-zinc-400">Probe interval to auto-recover when tripped</span>
              </div>
            </div>
          </div>

          {/* 2. Jittered Exponential Backoff Retry Strategy */}
          <div className="rounded-xl border border-zinc-200 bg-zinc-50/70 p-4 space-y-3">
            <div className="flex items-center space-x-2 border-b border-zinc-200/60 pb-2.5">
              <Zap className="w-4 h-4 text-amber-500" />
              <span className="font-semibold text-zinc-900">Resilient Jittered Exponential Backoff Retry Strategy</span>
              <InfoTooltip
                title="Jittered Exponential Backoff Strategy"
                badge="AMQP Consumer"
                whatIsIt="Intelligent retry policy that scales wait intervals exponentially with randomized jitter between consecutive sync attempts."
                howItWorks="Prevents 'thundering herd' spikes against downstream Git servers and eliminates instant-failure cascades during transient network latency."
                recommended="Max Retries: 3-5, Initial: 3000ms, Multiplier: 2.0x, Max Interval: 30000ms"
              />
            </div>

            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
              <div>
                <div className="flex items-center space-x-1 mb-1">
                  <label className="block text-zinc-700 font-medium">Max Retries</label>
                  <InfoTooltip
                    title="Max Retry Attempts"
                    whatIsIt="The maximum number of retry attempts for a failed sync job before it is permanently routed to the Dead Letter Queue (DLQ)."
                    howItWorks="Each attempt fetches fresh refs. If all retries fail, the job is dead-lettered and increments the circuit breaker failure counter."
                    recommended="3 to 5 attempts"
                  />
                </div>
                <input
                  type="number"
                  min="1"
                  max="10"
                  value={maxRetryAttempts}
                  onChange={(e) => setMaxRetryAttempts(parseInt(e.target.value) || 3)}
                  className="w-full bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                />
                <span className="text-[10px] text-zinc-400">Attempts before DLQ</span>
              </div>

              <div>
                <div className="flex items-center space-x-1 mb-1">
                  <label className="block text-zinc-700 font-medium">Initial Delay (ms)</label>
                  <InfoTooltip
                    title="Initial Retry Delay"
                    whatIsIt="Base sleep duration in milliseconds before the very first retry execution."
                    howItWorks="The consumer will hold the job for this base interval before re-executing Git sync operations."
                    recommended="2000ms - 5000ms"
                  />
                </div>
                <input
                  type="number"
                  step="500"
                  min="500"
                  max="10000"
                  value={retryInitialIntervalMs}
                  onChange={(e) => setRetryInitialIntervalMs(parseInt(e.target.value) || 3000)}
                  className="w-full bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                />
                <span className="text-[10px] text-zinc-400">e.g. 3000ms</span>
              </div>

              <div>
                <div className="flex items-center space-x-1 mb-1">
                  <label className="block text-zinc-700 font-medium">Multiplier</label>
                  <InfoTooltip
                    title="Backoff Multiplier"
                    whatIsIt="The exponential scaling factor applied to subsequent retry delays (Delay = Initial * Multiplier^(Attempt-1))."
                    howItWorks="A multiplier of 2.0 with a 3000ms base results in retry intervals of ~3s, ~6s, ~12s, ~24s up to the max cap."
                    recommended="1.5 to 2.5x"
                  />
                </div>
                <input
                  type="number"
                  step="0.5"
                  min="1.0"
                  max="5.0"
                  value={retryMultiplier}
                  onChange={(e) => setRetryMultiplier(parseFloat(e.target.value) || 2.0)}
                  className="w-full bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                />
                <span className="text-[10px] text-zinc-400">e.g. 2.0x</span>
              </div>

              <div>
                <div className="flex items-center space-x-1 mb-1">
                  <label className="block text-zinc-700 font-medium">Max Interval (ms)</label>
                  <InfoTooltip
                    title="Maximum Delay Cap"
                    whatIsIt="The upper ceiling limit for exponential backoff intervals."
                    howItWorks="Guarantees delays do not compound indefinitely, ensuring transient errors are retried within a reasonable operational window."
                    recommended="30000ms (30s) to 60000ms (60s)"
                  />
                </div>
                <input
                  type="number"
                  step="1000"
                  min="5000"
                  max="120000"
                  value={retryMaxIntervalMs}
                  onChange={(e) => setRetryMaxIntervalMs(parseInt(e.target.value) || 30000)}
                  className="w-full bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                />
                <span className="text-[10px] text-zinc-400">Cap delay</span>
              </div>
            </div>
          </div>

          {/* 3. Concurrency & Metadata Throttling */}
          <div className="rounded-xl border border-zinc-200 bg-zinc-50/70 p-4 space-y-3">
            <div className="flex items-center space-x-2 border-b border-zinc-200/60 pb-2.5">
              <Sliders className="w-4 h-4 text-purple-600" />
              <span className="font-semibold text-zinc-900">Concurrency & Rate Limiting Token Bucket</span>
              <InfoTooltip
                title="Concurrency & Rate Limiting"
                badge="Throughput"
                whatIsIt="Global execution semaphores and rate-limit guards that protect host resources and prevent Git provider API throttling."
                howItWorks="Restricts simultaneous Git push operations and enforces cooldown timers on secondary metadata syncs (PRs, releases, statuses)."
              />
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <div className="flex items-center space-x-1 mb-1">
                  <label className="block text-zinc-700 font-medium">Max Concurrent Git Pushes</label>
                  <InfoTooltip
                    title="Max Concurrent Git Pushes"
                    whatIsIt="The maximum number of simultaneous outbound JGit push operations executed across all worker threads."
                    howItWorks="Controls system CPU and network bandwidth spikes during large webhook floods. Excess jobs queue up in RabbitMQ."
                    recommended="5 to 15 concurrent pushes"
                  />
                </div>
                <input
                  type="number"
                  min="1"
                  max="50"
                  value={maxConcurrentPushes}
                  onChange={(e) => setMaxConcurrentPushes(parseInt(e.target.value) || 5)}
                  className="w-full bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                />
                <span className="text-[10px] text-zinc-400">Limits simultaneous Git network operations</span>
              </div>

              <div>
                <div className="flex items-center space-x-1 mb-1">
                  <label className="block text-zinc-700 font-medium">Metadata Sync Cooldown (Sec)</label>
                  <InfoTooltip
                    title="Metadata Sync Cooldown"
                    whatIsIt="Minimum cooldown duration in seconds between consecutive Pull Request, Release asset, and Commit Status synchronization runs."
                    howItWorks="Guards against exhausting provider REST API rate limits (e.g., GitHub's 5000/hr/user or 15000/hr/app limit) during rapid commit pushes."
                    recommended="15 to 60 seconds"
                  />
                </div>
                <input
                  type="number"
                  min="5"
                  max="3600"
                  value={metadataSyncIntervalSeconds}
                  onChange={(e) => setMetadataSyncIntervalSeconds(parseInt(e.target.value) || 30)}
                  className="w-full bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                />
                <span className="text-[10px] text-zinc-400">Min duration between PR & Release syncs</span>
              </div>
            </div>

            <div className="pt-2 border-t border-zinc-200/60">
              <div className="flex items-start justify-between gap-3">
                <div>
                  <div className="flex items-center space-x-1 mb-1">
                    <label className="block text-zinc-700 font-medium">Suppress mirror-triggered Actions</label>
                    <InfoTooltip
                      title="Mirror Actions Suppression"
                      badge="CI Guard"
                      whatIsIt="Stops GitHub Actions runs caused by GitMirror Hub pushes and PR writes, without disabling Actions for human activity."
                      howItWorks="Requires GitHub App auth (not PAT). After Hub writes, cancels workflow runs attributed to the App bot. Also recommend org Workflow execution protections that exclude the mirror App."
                      recommended="Keep enabled for bidirectional active remotes"
                    />
                  </div>
                  <p className="text-[10px] text-zinc-500 max-w-xl">
                    App/PAT pushes do trigger Actions (unlike GITHUB_TOKEN). When enabled, destination writes must use App installation tokens and the Hub cancels runs for the App bot actor.
                  </p>
                </div>
                <button
                  type="button"
                  role="switch"
                  aria-checked={suppressMirrorActionsTriggers}
                  onClick={() => setSuppressMirrorActionsTriggers((v) => !v)}
                  className={`relative inline-flex h-6 w-11 shrink-0 items-center rounded-full transition-colors ${
                    suppressMirrorActionsTriggers ? 'bg-emerald-600' : 'bg-zinc-300'
                  }`}
                >
                  <span
                    className={`inline-block h-4 w-4 transform rounded-full bg-white transition ${
                      suppressMirrorActionsTriggers ? 'translate-x-6' : 'translate-x-1'
                    }`}
                  />
                </button>
              </div>
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
              <span>{saving ? 'Saving...' : 'Save Engine Configuration'}</span>
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
