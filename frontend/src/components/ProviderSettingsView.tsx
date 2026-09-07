import React, { useState, useEffect } from 'react';
import {
  ShieldCheck, Key, Save, CheckCircle2, AlertCircle, RefreshCw, Check,
  BookOpen, Layers, GitFork, Globe, Lock, HardDrive, Database, Trash2,
  Cpu, Sliders, Activity, Zap, PlayCircle, RotateCcw, Info, Terminal, FileText
} from 'lucide-react';
import { InfoTooltip } from './InfoTooltip';
import {
  ProviderConfig,
  GitHubRepoOption,
  StorageStatusResponse,
  SystemEngineConfig,
  NasPathTestResult,
  CircuitBreakerResetResult,
  LoggingSinkTestResult
} from '../types';
import {
  getGitHubAppConfig,
  saveGitHubAppConfig,
  listAccessibleRepositories,
  getStorageStatus,
  triggerStorageEviction,
  getSystemEngineConfig,
  saveSystemEngineConfig,
  testNasPath,
  probeAndResetCircuitBreaker,
  testLoggingSink
} from '../services/api';

export const ProviderSettingsView: React.FC = () => {
  const [selectedProvider, setSelectedProvider] = useState<'github' | 'gitlab' | 'bitbucket' | 'origin' | 'generic' | 'system-engine'>('github');

  // Loaded config metadata
  const [loadedConfig, setLoadedConfig] = useState<ProviderConfig | null>(null);
  const [storageStatus, setStorageStatus] = useState<StorageStatusResponse | null>(null);
  const [systemConfig, setSystemConfig] = useState<SystemEngineConfig | null>(null);

  const [evicting, setEvicting] = useState(false);
  const [testingNas, setTestingNas] = useState(false);
  const [nasTestResult, setNasTestResult] = useState<NasPathTestResult | null>(null);
  const [resettingCircuit, setResettingCircuit] = useState(false);

  // GitHub state
  const [authType, setAuthType] = useState<'GITHUB_APP' | 'PERSONAL_ACCESS_TOKEN'>('PERSONAL_ACCESS_TOKEN');
  const [appId, setAppId] = useState('');
  const [clientId, setClientId] = useState('');
  const [clientSecret, setClientSecret] = useState('');
  const [privateKeyPem, setPrivateKeyPem] = useState('');
  const [installationId, setInstallationId] = useState('');
  const [webhookSecret, setWebhookSecret] = useState('');
  const [defaultPatToken, setDefaultPatToken] = useState('');

  // GitLab state
  const [gitlabHostUrl, setGitlabHostUrl] = useState('https://gitlab.com');
  const [gitlabAccessToken, setGitlabAccessToken] = useState('');
  const [gitlabWebhookSecret, setGitlabWebhookSecret] = useState('');

  // Bitbucket state
  const [bitbucketWorkspace, setBitbucketWorkspace] = useState('');
  const [bitbucketUsername, setBitbucketUsername] = useState('x-token-auth');
  const [bitbucketAccessToken, setBitbucketAccessToken] = useState('');
  const [bitbucketWebhookSecret, setBitbucketWebhookSecret] = useState('');

  // Cursor Origin state
  const [originHostUrl, setOriginHostUrl] = useState('https://origin.cursor.com');
  const [originAccessToken, setOriginAccessToken] = useState('');
  const [originWebhookSecret, setOriginWebhookSecret] = useState('');

  // Generic state
  const [genericUsername, setGenericUsername] = useState('');
  const [genericAccessToken, setGenericAccessToken] = useState('');

  // System Engine & Storage state
  const [localDir, setLocalDir] = useState('/tmp/git-utility-mirrors');
  const [nasDir, setNasDir] = useState('/tmp/git-utility-nas-mirrors');
  const [maxDiskQuotaMb, setMaxDiskQuotaMb] = useState(51200);
  const [maxCachedRepos, setMaxCachedRepos] = useState(1000);
  const [retentionHours, setRetentionHours] = useState(72);
  const [maxConcurrentPushes, setMaxConcurrentPushes] = useState(5);
  const [metadataSyncIntervalSeconds, setMetadataSyncIntervalSeconds] = useState(30);
  const [maxRetryAttempts, setMaxRetryAttempts] = useState(3);
  const [retryInitialIntervalMs, setRetryInitialIntervalMs] = useState(3000);
  const [retryMultiplier, setRetryMultiplier] = useState(2.0);
  const [retryMaxIntervalMs, setRetryMaxIntervalMs] = useState(30000);
  const [circuitBreakerFailureThreshold, setCircuitBreakerFailureThreshold] = useState(5);
  const [circuitBreakerResetTimeoutSeconds, setCircuitBreakerResetTimeoutSeconds] = useState(30);

  // Enterprise Logging state
  const [loggingSink, setLoggingSink] = useState<'CONSOLE' | 'SPLUNK_HEC' | 'LOGSTASH_ELK' | 'SYSLOG' | 'ROLLING_FILE' | 'DUAL_CONSOLE_SPLUNK' | string>('CONSOLE');
  const [loggingLevel, setLoggingLevel] = useState<'DEBUG' | 'INFO' | 'WARN' | 'ERROR' | string>('INFO');
  const [splunkHecUrl, setSplunkHecUrl] = useState('');
  const [splunkHecToken, setSplunkHecToken] = useState('');
  const [splunkIndex, setSplunkIndex] = useState('main');
  const [splunkSourceType, setSplunkSourceType] = useState('_json');
  const [logstashHost, setLogstashHost] = useState('');
  const [syslogHost, setSyslogHost] = useState('');
  const [rollingFilePath, setRollingFilePath] = useState('/tmp/git-utility-mirrors/logs/git-utility.log');
  const [jsonStructuredEnabled, setJsonStructuredEnabled] = useState(true);

  const [testingSink, setTestingSink] = useState(false);
  const [sinkTestResult, setSinkTestResult] = useState<LoggingSinkTestResult | null>(null);

  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(false);
  const [feedback, setFeedback] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

  const [repos, setRepos] = useState<GitHubRepoOption[]>([]);
  const [fetchingRepos, setFetchingRepos] = useState(false);

  useEffect(() => {
    loadConfig();
  }, []);

  const loadConfig = async () => {
    setLoading(true);
    try {
      const [config, storage, sysEngine] = await Promise.allSettled([
        getGitHubAppConfig(),
        getStorageStatus(),
        getSystemEngineConfig(),
      ]);

      if (config.status === 'fulfilled' && config.value) {
        const c = config.value;
        setLoadedConfig(c);
        setAuthType(c.authType || 'PERSONAL_ACCESS_TOKEN');
        setAppId(c.appId || '');
        setClientId(c.clientId || '');
        setInstallationId(c.installationId || '');
        if (c.gitlabHostUrl) setGitlabHostUrl(c.gitlabHostUrl);
        if (c.bitbucketWorkspace) setBitbucketWorkspace(c.bitbucketWorkspace);
        if (c.bitbucketUsername) setBitbucketUsername(c.bitbucketUsername);
        if (c.originHostUrl) setOriginHostUrl(c.originHostUrl);
        if (c.genericUsername) setGenericUsername(c.genericUsername);

        if (c.configured && (c.hasDefaultPatToken || (c.appId && c.hasPrivateKey) || c.authType === 'GITHUB_APP')) {
          fetchAccessibleRepos();
        }
      }

      if (storage.status === 'fulfilled') {
        setStorageStatus(storage.value);
      }

      if (sysEngine.status === 'fulfilled' && sysEngine.value) {
        const s = sysEngine.value;
        setSystemConfig(s);
        setLocalDir(s.localDir || '/tmp/git-utility-mirrors');
        setNasDir(s.nasDir || '/tmp/git-utility-nas-mirrors');
        setMaxDiskQuotaMb(s.maxDiskQuotaMb || 51200);
        setMaxCachedRepos(s.maxCachedRepos || 1000);
        setRetentionHours(s.retentionHours || 72);
        setMaxConcurrentPushes(s.maxConcurrentPushes || 5);
        setMetadataSyncIntervalSeconds(s.metadataSyncIntervalSeconds || 30);
        setMaxRetryAttempts(s.maxRetryAttempts || 3);
        setRetryInitialIntervalMs(s.retryInitialIntervalMs || 3000);
        setRetryMultiplier(s.retryMultiplier || 2.0);
        setRetryMaxIntervalMs(s.retryMaxIntervalMs || 30000);
        setCircuitBreakerFailureThreshold(s.circuitBreakerFailureThreshold || 5);
        setCircuitBreakerResetTimeoutSeconds(s.circuitBreakerResetTimeoutSeconds || 30);
        setLoggingSink(s.loggingSink || 'CONSOLE');
        setLoggingLevel(s.loggingLevel || 'INFO');
        setSplunkHecUrl(s.splunkHecUrl || '');
        setSplunkIndex(s.splunkIndex || 'main');
        setSplunkSourceType(s.splunkSourceType || '_json');
        setLogstashHost(s.logstashHost || '');
        setSyslogHost(s.syslogHost || '');
        setRollingFilePath(s.rollingFilePath || '/tmp/git-utility-mirrors/logs/git-utility.log');
        setJsonStructuredEnabled(s.jsonStructuredEnabled ?? true);
      }
    } catch (e) {
      console.error('Error loading config:', e);
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

  const handleTestLoggingSink = async () => {
    setTestingSink(true);
    setSinkTestResult(null);
    try {
      const res = await testLoggingSink({
        sink: loggingSink,
        url: splunkHecUrl,
        token: splunkHecToken,
        host: loggingSink === 'SYSLOG' ? syslogHost : logstashHost,
        filePath: rollingFilePath,
      });
      setSinkTestResult(res);
    } catch (e: any) {
      setSinkTestResult({
        success: false,
        statusCode: 500,
        message: 'Test failed: ' + (e.response?.data?.message || e.message || 'Could not connect to sink endpoint'),
      });
    } finally {
      setTestingSink(false);
    }
  };

  const handleSaveSystemConfig = async (e: React.FormEvent) => {
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
        maxConcurrentPushes,
        metadataSyncIntervalSeconds,
        maxRetryAttempts,
        retryInitialIntervalMs,
        retryMultiplier,
        retryMaxIntervalMs,
        circuitBreakerFailureThreshold,
        circuitBreakerResetTimeoutSeconds,
        loggingSink,
        loggingLevel,
        splunkHecUrl,
        splunkHecToken: splunkHecToken ? splunkHecToken : undefined,
        splunkIndex,
        splunkSourceType,
        logstashHost,
        syslogHost,
        rollingFilePath,
        jsonStructuredEnabled,
      };

      const saved = await saveSystemEngineConfig(payload);
      setSystemConfig(saved);
      const storage = await getStorageStatus();
      setStorageStatus(storage);

      setFeedback({
        type: 'success',
        message: 'System Engine & Storage configuration updated and hot-reloaded dynamically!'
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

  const fetchAccessibleRepos = async () => {
    setFetchingRepos(true);
    try {
      const r = await listAccessibleRepositories();
      setRepos(r);
    } catch (e) {
      console.error('Failed to list repos:', e);
    } finally {
      setFetchingRepos(false);
    }
  };

  const handleSaveProvider = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setFeedback(null);

    try {
      const payload: Partial<ProviderConfig> = {
        authType,
        appId: appId || undefined,
        clientId: clientId || undefined,
        clientSecret: clientSecret || undefined,
        privateKeyPem: privateKeyPem || undefined,
        installationId: installationId || undefined,
        webhookSecret: webhookSecret || undefined,
        defaultPatToken: defaultPatToken || undefined,

        gitlabHostUrl: gitlabHostUrl || undefined,
        gitlabAccessToken: gitlabAccessToken || undefined,
        gitlabWebhookSecret: gitlabWebhookSecret || undefined,

        bitbucketWorkspace: bitbucketWorkspace || undefined,
        bitbucketUsername: bitbucketUsername || undefined,
        bitbucketAccessToken: bitbucketAccessToken || undefined,
        bitbucketWebhookSecret: bitbucketWebhookSecret || undefined,

        originHostUrl: originHostUrl || undefined,
        originAccessToken: originAccessToken || undefined,
        originWebhookSecret: originWebhookSecret || undefined,

        genericUsername: genericUsername || undefined,
        genericAccessToken: genericAccessToken || undefined,
      };

      const saved = await saveGitHubAppConfig(payload);
      setLoadedConfig(saved);
      setClientSecret('');
      setPrivateKeyPem('');
      setWebhookSecret('');
      setDefaultPatToken('');
      setGitlabAccessToken('');
      setGitlabWebhookSecret('');
      setBitbucketAccessToken('');
      setBitbucketWebhookSecret('');
      setOriginAccessToken('');
      setOriginWebhookSecret('');
      setGenericAccessToken('');

      setFeedback({
        type: 'success',
        message: 'Provider credentials encrypted and saved successfully!'
      });

      if (saved.configured) {
        fetchAccessibleRepos();
      }
    } catch (err: any) {
      setFeedback({
        type: 'error',
        message: err.response?.data?.message || err.message || 'Failed to save configuration'
      });
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-base font-semibold text-zinc-900">Enterprise Settings & Infrastructure</h2>
        <p className="text-xs text-zinc-500 mt-0.5">
          Configure SCM authentication, Storage Tiering, Rate Limiting, Jittered Retries, and Self-Healing Circuit Breakers.
        </p>
      </div>

      {/* Provider Selector Tabs */}
      <div className="flex border-b border-zinc-200 overflow-x-auto space-x-2">
        <button
          onClick={() => setSelectedProvider('github')}
          className={`flex items-center space-x-2 px-4 py-2.5 text-xs font-medium border-b-2 transition-colors whitespace-nowrap ${
            selectedProvider === 'github'
              ? 'border-zinc-900 text-zinc-900 font-semibold'
              : 'border-transparent text-zinc-500 hover:text-zinc-700'
          }`}
        >
          <GitFork className="w-4 h-4" />
          <span>GitHub App & PAT</span>
          {loadedConfig?.configured && (
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 ml-1" />
          )}
        </button>

        <button
          onClick={() => setSelectedProvider('gitlab')}
          className={`flex items-center space-x-2 px-4 py-2.5 text-xs font-medium border-b-2 transition-colors whitespace-nowrap ${
            selectedProvider === 'gitlab'
              ? 'border-zinc-900 text-zinc-900 font-semibold'
              : 'border-transparent text-zinc-500 hover:text-zinc-700'
          }`}
        >
          <Layers className="w-4 h-4" />
          <span>GitLab</span>
        </button>

        <button
          onClick={() => setSelectedProvider('bitbucket')}
          className={`flex items-center space-x-2 px-4 py-2.5 text-xs font-medium border-b-2 transition-colors whitespace-nowrap ${
            selectedProvider === 'bitbucket'
              ? 'border-zinc-900 text-zinc-900 font-semibold'
              : 'border-transparent text-zinc-500 hover:text-zinc-700'
          }`}
        >
          <Globe className="w-4 h-4" />
          <span>Bitbucket</span>
        </button>

        <button
          onClick={() => setSelectedProvider('origin')}
          className={`flex items-center space-x-2 px-4 py-2.5 text-xs font-medium border-b-2 transition-colors whitespace-nowrap ${
            selectedProvider === 'origin'
              ? 'border-zinc-900 text-zinc-900 font-semibold'
              : 'border-transparent text-zinc-500 hover:text-zinc-700'
          }`}
        >
          <Lock className="w-4 h-4" />
          <span>Cursor Origin</span>
        </button>

        <button
          onClick={() => setSelectedProvider('generic')}
          className={`flex items-center space-x-2 px-4 py-2.5 text-xs font-medium border-b-2 transition-colors whitespace-nowrap ${
            selectedProvider === 'generic'
              ? 'border-zinc-900 text-zinc-900 font-semibold'
              : 'border-transparent text-zinc-500 hover:text-zinc-700'
          }`}
        >
          <Key className="w-4 h-4" />
          <span>Azure DevOps / Generic</span>
        </button>

        <button
          onClick={() => setSelectedProvider('system-engine')}
          className={`flex items-center space-x-2 px-4 py-2.5 text-xs font-medium border-b-2 transition-colors whitespace-nowrap ${
            selectedProvider === 'system-engine'
              ? 'border-zinc-900 text-zinc-900 font-semibold'
              : 'border-transparent text-zinc-500 hover:text-zinc-700'
          }`}
        >
          <Cpu className="w-4 h-4 text-emerald-600" />
          <span className="text-zinc-900 font-semibold">System Engine & Storage</span>
          <span className="px-1.5 py-0.2 text-[9px] bg-emerald-100 text-emerald-700 rounded font-semibold uppercase">Live</span>
        </button>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* Left Form View */}
        <div className="lg:col-span-7 space-y-4">
          <div className="rounded-2xl border border-zinc-200/90 bg-white p-6 shadow-sm">

            {/* SYSTEM ENGINE & STORAGE TAB */}
            {selectedProvider === 'system-engine' ? (
              <form onSubmit={handleSaveSystemConfig} className="space-y-6 text-xs">
                
                {/* 1. Circuit Breaker Telemetry & Admin Controls */}
                <div className="rounded-xl border border-zinc-200 bg-zinc-50/70 p-4 space-y-3">
                  <div className="flex items-center justify-between border-b border-zinc-200/60 pb-2.5">
                    <div className="flex items-center space-x-2">
                      <Activity className="w-4 h-4 text-rose-600" />
                      <span className="font-semibold text-zinc-900">Self-Healing Circuit Breaker Status</span>
                      <InfoTooltip
                        title="Self-Healing Circuit Breaker"
                        badge="Resilience"
                        whatIsIt="A protective tri-state mechanism (CLOSED, OPEN, HALF_OPEN) that isolates downstream Git provider outages and prevents cascading system degradation."
                        howItWorks="When sustained consecutive failures occur, the circuit opens and pauses the AMQP queue consumer, preserving unacknowledged sync events in RabbitMQ without dropping them. Background health probes periodically test downstream SCMs and automatically heal the circuit once connectivity is restored."
                        recommended="Keep enabled to ensure zero message loss during upstream outages"
                      />
                    </div>

                    <div className="flex items-center space-x-2">
                      <span className={`px-2 py-0.5 rounded-full text-[10px] font-semibold border ${
                        systemConfig?.circuitBreakerState === 'CLOSED'
                          ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                          : systemConfig?.circuitBreakerState === 'HALF_OPEN'
                          ? 'bg-blue-50 text-blue-700 border-blue-200 animate-pulse'
                          : 'bg-rose-50 text-rose-700 border-rose-200'
                      }`}>
                        State: {systemConfig?.circuitBreakerState || 'CLOSED'}
                      </span>

                      <div className="flex items-center space-x-1">
                        <button
                          type="button"
                          onClick={() => handleResetCircuitBreaker(false)}
                          disabled={resettingCircuit}
                          className="flex items-center space-x-1 px-2.5 py-1 bg-white hover:bg-zinc-100 border border-zinc-200 text-zinc-700 rounded-md font-medium text-[11px] shadow-sm disabled:opacity-50"
                          title="Executes live SCM probe and resumes consumer"
                        >
                          <RotateCcw className={`w-3 h-3 ${resettingCircuit ? 'animate-spin' : ''}`} />
                          <span>Test & Reset</span>
                        </button>
                        <InfoTooltip
                          title="Admin Probe & Reset"
                          whatIsIt="An immediate administrator trigger to test downstream Git provider connectivity and reset the circuit breaker."
                          howItWorks="Executes an out-of-band ping to provider APIs. If reachable, it clears failures to 0, transitions the breaker to CLOSED, and unpauses the AMQP consumer immediately."
                          position="left"
                        />
                      </div>
                    </div>
                  </div>

                  <div className="text-[11px] text-zinc-600 space-y-1">
                    <p>
                      <strong>Consecutive Permanent Failures:</strong> {systemConfig?.currentConsecutiveFailures ?? 0} / {circuitBreakerFailureThreshold}
                    </p>
                    <p className="font-mono text-[10px] text-zinc-500 truncate">
                      <strong>Last Probe:</strong> {systemConfig?.lastProbeMessage || 'System healthy'}
                    </p>
                  </div>

                  <div className="grid grid-cols-2 gap-3 pt-1">
                    <div>
                      <div className="flex items-center space-x-1 mb-1">
                        <label className="block text-zinc-700 font-medium">Failure Threshold</label>
                        <InfoTooltip
                          title="Circuit Breaker Failure Threshold"
                          whatIsIt="The number of consecutive unrecoverable permanent job failures required to trip the circuit to OPEN."
                          howItWorks="Only sync jobs that fail all retry attempts count toward this threshold. Transient network retries do not increment this counter."
                          recommended="3 to 5 failures"
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
                      <span className="text-[10px] text-zinc-400">Perm failures before auto-pause</span>
                    </div>

                    <div>
                      <div className="flex items-center space-x-1 mb-1">
                        <label className="block text-zinc-700 font-medium">Probe Interval (sec)</label>
                        <InfoTooltip
                          title="Background Health Probe Interval"
                          whatIsIt="Frequency in seconds at which the background daemon executes automated health checks against downstream SCM APIs."
                          howItWorks="When OPEN, the system transitions to HALF_OPEN every N seconds and executes health probes against Git providers. If reachable, it resets failure counters, transitions to CLOSED, and automatically resumes the queue consumer."
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
                      <span className="text-[10px] text-zinc-400">Background SCM test cycle</span>
                    </div>
                  </div>
                </div>

                {/* 2. Resilient Jittered Exponential Backoff Strategy */}
                <div className="rounded-xl border border-zinc-200 bg-zinc-50/70 p-4 space-y-3">
                  <div className="flex items-center space-x-2 border-b border-zinc-200/60 pb-2.5">
                    <Zap className="w-4 h-4 text-amber-600" />
                    <span className="font-semibold text-zinc-900">Jittered Exponential Backoff Retry Strategy</span>
                    <InfoTooltip
                      title="Jittered Exponential Backoff"
                      badge="Queue Resilience"
                      whatIsIt="A progressive retry delay algorithm with randomized jitter to prevent rate-limit exhaustion and synchronized thundering herd retries."
                      howItWorks="Failed messages are re-queued with exponentially increasing delays. Randomized jitter breaks synchronization across parallel failing jobs."
                      recommended="Default configuration is optimized for enterprise scale"
                    />
                  </div>

                  <p className="text-[11px] text-zinc-500">
                    Defines how failed sync jobs back off to prevent rate limiting (429) and downstream thundering herds.
                  </p>

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

                {/* 3. Storage Paths & NAS Mount Validator */}
                <div className="rounded-xl border border-zinc-200 bg-zinc-50/70 p-4 space-y-3">
                  <div className="flex items-center space-x-2 border-b border-zinc-200/60 pb-2.5">
                    <HardDrive className="w-4 h-4 text-blue-600" />
                    <span className="font-semibold text-zinc-900">Storage Tier Paths & Network Mounts</span>
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
                </div>

                {/* 4. Concurrency & Metadata Throttling */}
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
                </div>

                {/* 5. Enterprise Multi-Sink Logging & SIEM Aggregation */}
                <div className="rounded-xl border border-zinc-200 bg-zinc-50/70 p-4 space-y-3">
                  <div className="flex items-center justify-between border-b border-zinc-200/60 pb-2.5">
                    <div className="flex items-center space-x-2">
                      <Terminal className="w-4 h-4 text-emerald-600" />
                      <span className="font-semibold text-zinc-900">Enterprise Multi-Sink Logging & SIEM Forwarder</span>
                      <InfoTooltip
                        title="Enterprise Multi-Sink Logging"
                        badge="SIEM & Audit"
                        whatIsIt="Centralized log aggregation subsystem sending structured sync audit events to enterprise SIEM platforms."
                        howItWorks="Supports real-time token-authenticated push to Splunk HEC, HTTP/TCP streams to Logstash/Elasticsearch, RFC 5424 Syslog, and daily rotated disk logfiles. Log levels hot-reload without JVM restarts."
                        recommended="CONSOLE for dev/staging, SPLUNK_HEC or LOGSTASH_ELK for SOC2/ISO compliance."
                      />
                    </div>
                    <button
                      type="button"
                      onClick={handleTestLoggingSink}
                      disabled={testingSink}
                      className="flex items-center space-x-1 text-emerald-600 hover:text-emerald-700 font-medium text-[11px] disabled:opacity-50"
                    >
                      <RefreshCw className={`w-3 h-3 ${testingSink ? 'animate-spin' : ''}`} />
                      <span>Test Logging Sink</span>
                    </button>
                  </div>

                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                    <div>
                      <div className="flex items-center space-x-1 mb-1">
                        <label className="block text-zinc-700 font-medium">Active Logging Sink</label>
                        <InfoTooltip
                          title="Active Logging Destination"
                          whatIsIt="Target sink where structured replication audit telemetry is shipped."
                          howItWorks="CONSOLE outputs to stdout/docker logs. SPLUNK_HEC sends HTTP events to Splunk HTTP Event Collector. LOGSTASH_ELK forwards to Elasticsearch indexers. SYSLOG transmits UDP RFC 5424 packets. ROLLING_FILE writes JSON logs directly to local storage."
                          recommended="CONSOLE or SPLUNK_HEC"
                        />
                      </div>
                      <select
                        value={loggingSink}
                        onChange={(e) => setLoggingSink(e.target.value)}
                        className="w-full bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-zinc-900 text-xs focus:outline-none focus:border-zinc-400"
                      >
                        <option value="CONSOLE">Console Only (Default stdout)</option>
                        <option value="SPLUNK_HEC">Splunk HEC (HTTP Event Collector)</option>
                        <option value="LOGSTASH_ELK">Logstash / Elasticsearch HTTP Pipeline</option>
                        <option value="SYSLOG">Syslog Server (RFC 5424 / UDP 514)</option>
                        <option value="ROLLING_FILE">Rolling File on Disk</option>
                        <option value="DUAL_CONSOLE_SPLUNK">Dual Sink: Console + Splunk HEC</option>
                      </select>
                    </div>

                    <div>
                      <div className="flex items-center space-x-1 mb-1">
                        <label className="block text-zinc-700 font-medium">Dynamic Runtime Log Level</label>
                        <InfoTooltip
                          title="Runtime Log Level"
                          whatIsIt="Minimum severity threshold for logging output across the GitMirror Hub backend."
                          howItWorks="Adjusting this setting instantly updates Logback loggers across the entire running application without requiring a service restart."
                          recommended="INFO for production, DEBUG for troubleshooting sync anomalies."
                        />
                      </div>
                      <select
                        value={loggingLevel}
                        onChange={(e) => setLoggingLevel(e.target.value)}
                        className="w-full bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-zinc-900 text-xs focus:outline-none focus:border-zinc-400"
                      >
                        <option value="INFO">INFO (Standard production logging)</option>
                        <option value="DEBUG">DEBUG (Verbose JGit & ref trace logging)</option>
                        <option value="WARN">WARN (Only warnings and failures)</option>
                        <option value="ERROR">ERROR (Critical exceptions only)</option>
                      </select>
                    </div>
                  </div>

                  {/* Dynamic Sink Configuration Fields */}
                  {(loggingSink === 'SPLUNK_HEC' || loggingSink === 'DUAL_CONSOLE_SPLUNK') && (
                    <div className="p-3 bg-white border border-zinc-200/80 rounded-xl space-y-3">
                      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                        <div>
                          <label className="block text-[11px] font-medium text-zinc-700 mb-1">Splunk HEC Collector URL</label>
                          <input
                            type="text"
                            value={splunkHecUrl}
                            onChange={(e) => setSplunkHecUrl(e.target.value)}
                            placeholder="https://splunk.internal:8088/services/collector/event"
                            className="w-full bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                          />
                        </div>
                        <div>
                          <label className="block text-[11px] font-medium text-zinc-700 mb-1">
                            Splunk HEC Auth Token {systemConfig?.hasSplunkHecToken && <span className="text-[10px] text-emerald-600 font-normal">(Configured: {systemConfig.splunkHecTokenMasked})</span>}
                          </label>
                          <input
                            type="password"
                            value={splunkHecToken}
                            onChange={(e) => setSplunkHecToken(e.target.value)}
                            placeholder={systemConfig?.hasSplunkHecToken ? '•••••••• (Leave blank to keep current)' : 'Enter Splunk HEC GUID Token'}
                            className="w-full bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                          />
                        </div>
                      </div>
                      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                        <div>
                          <label className="block text-[11px] font-medium text-zinc-700 mb-1">Target Index</label>
                          <input
                            type="text"
                            value={splunkIndex}
                            onChange={(e) => setSplunkIndex(e.target.value)}
                            placeholder="git_mirror_events"
                            className="w-full bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                          />
                        </div>
                        <div>
                          <label className="block text-[11px] font-medium text-zinc-700 mb-1">Sourcetype</label>
                          <input
                            type="text"
                            value={splunkSourceType}
                            onChange={(e) => setSplunkSourceType(e.target.value)}
                            placeholder="_json or gitmirror:audit"
                            className="w-full bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                          />
                        </div>
                      </div>
                    </div>
                  )}

                  {loggingSink === 'LOGSTASH_ELK' && (
                    <div className="p-3 bg-white border border-zinc-200/80 rounded-xl space-y-2">
                      <label className="block text-[11px] font-medium text-zinc-700">Logstash / Elasticsearch HTTP Ingestion URL</label>
                      <input
                        type="text"
                        value={logstashHost}
                        onChange={(e) => setLogstashHost(e.target.value)}
                        placeholder="http://logstash.corp.internal:8080 or https://elastic.internal:9200/git-audit/_doc"
                        className="w-full bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                      />
                    </div>
                  )}

                  {loggingSink === 'SYSLOG' && (
                    <div className="p-3 bg-white border border-zinc-200/80 rounded-xl space-y-2">
                      <label className="block text-[11px] font-medium text-zinc-700">Syslog Host & UDP Port</label>
                      <input
                        type="text"
                        value={syslogHost}
                        onChange={(e) => setSyslogHost(e.target.value)}
                        placeholder="syslog.corp.internal:514 or 10.0.1.50:514"
                        className="w-full bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                      />
                    </div>
                  )}

                  {loggingSink === 'ROLLING_FILE' && (
                    <div className="p-3 bg-white border border-zinc-200/80 rounded-xl space-y-2">
                      <label className="block text-[11px] font-medium text-zinc-700">Rolling JSON Logfile Path</label>
                      <input
                        type="text"
                        value={rollingFilePath}
                        onChange={(e) => setRollingFilePath(e.target.value)}
                        placeholder="/var/log/git-utility/audit.log"
                        className="w-full bg-white border border-zinc-200 rounded-lg px-2.5 py-1.5 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                      />
                    </div>
                  )}

                  {sinkTestResult && (
                    <div className={`p-2.5 rounded-lg border text-[11px] flex items-center space-x-1.5 ${
                      sinkTestResult.success ? 'bg-emerald-50 border-emerald-200 text-emerald-800' : 'bg-rose-50 border-rose-200 text-rose-800'
                    }`}>
                      {sinkTestResult.success ? <CheckCircle2 className="w-4 h-4 text-emerald-600 shrink-0" /> : <AlertCircle className="w-4 h-4 text-rose-600 shrink-0" />}
                      <div className="flex-1">
                        <span className="font-medium">{sinkTestResult.message}</span>
                        {sinkTestResult.latencyMs !== undefined && (
                          <span className="ml-2 opacity-75 font-mono">({sinkTestResult.latencyMs}ms)</span>
                        )}
                      </div>
                    </div>
                  )}
                </div>

                {feedback && (
                  <div className={`p-3 rounded-lg border text-xs flex items-center space-x-2 ${
                    feedback.type === 'success' ? 'bg-emerald-50 border-emerald-200 text-emerald-800' : 'bg-rose-50 border-rose-200 text-rose-800'
                  }`}>
                    {feedback.type === 'success' ? <CheckCircle2 className="w-4 h-4 shrink-0 text-emerald-600" /> : <AlertCircle className="w-4 h-4 shrink-0 text-rose-600" />}
                    <span>{feedback.message}</span>
                  </div>
                )}

                <div className="flex items-center justify-between pt-2">
                  <button
                    type="button"
                    onClick={handleRunEviction}
                    disabled={evicting}
                    className="flex items-center space-x-1.5 bg-white hover:bg-zinc-50 text-zinc-700 px-3 py-1.5 rounded-lg border border-zinc-200 font-medium text-xs transition-colors"
                  >
                    <Trash2 className={`w-3.5 h-3.5 ${evicting ? 'animate-spin' : ''}`} />
                    <span>Run LRU Cache Eviction Now</span>
                  </button>

                  <button
                    type="submit"
                    disabled={saving}
                    className="flex items-center space-x-1.5 bg-zinc-900 hover:bg-zinc-800 text-white px-4 py-1.5 rounded-lg font-medium text-xs transition-colors shadow-sm disabled:opacity-50"
                  >
                    <Save className="w-3.5 h-3.5" />
                    <span>{saving ? 'Applying Hot-Reload...' : 'Save & Hot-Reload Engine'}</span>
                  </button>
                </div>
              </form>
            ) : (
              /* PROVIDER AUTH FORM */
              <form onSubmit={handleSaveProvider} className="space-y-4 text-xs">
                {/* GITHUB APP / PAT SETTINGS */}
                {selectedProvider === 'github' && (
                  <div className="space-y-4">
                    <div className="flex items-center justify-between border-b border-zinc-100 pb-3">
                      <h3 className="text-sm font-semibold text-zinc-900">GitHub Authentication</h3>
                      <div className="flex items-center space-x-2">
                        <label className="flex items-center space-x-1.5 cursor-pointer">
                          <input
                            type="radio"
                            name="authType"
                            value="PERSONAL_ACCESS_TOKEN"
                            checked={authType === 'PERSONAL_ACCESS_TOKEN'}
                            onChange={() => setAuthType('PERSONAL_ACCESS_TOKEN')}
                            className="text-zinc-900 focus:ring-zinc-900"
                          />
                          <span className="text-zinc-700">PAT Token</span>
                        </label>
                        <label className="flex items-center space-x-1.5 cursor-pointer">
                          <input
                            type="radio"
                            name="authType"
                            value="GITHUB_APP"
                            checked={authType === 'GITHUB_APP'}
                            onChange={() => setAuthType('GITHUB_APP')}
                            className="text-zinc-900 focus:ring-zinc-900"
                          />
                          <span className="text-zinc-700">GitHub App (Zero-Rate-Limit)</span>
                        </label>
                      </div>
                    </div>

                    {authType === 'GITHUB_APP' ? (
                      <div className="space-y-3">
                        <div className="grid grid-cols-2 gap-3">
                          <div>
                            <label className="block text-zinc-700 font-medium mb-1">App ID</label>
                            <input
                              type="text"
                              value={appId}
                              onChange={(e) => setAppId(e.target.value)}
                              placeholder="e.g. 123456"
                              className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                            />
                          </div>
                          <div>
                            <label className="block text-zinc-700 font-medium mb-1">Installation ID</label>
                            <input
                              type="text"
                              value={installationId}
                              onChange={(e) => setInstallationId(e.target.value)}
                              placeholder="Optional (Auto-discovered)"
                              className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                            />
                          </div>
                        </div>

                        <div>
                          <div className="flex items-center justify-between mb-1">
                            <label className="block text-zinc-700 font-medium">RSA Private Key (.pem)</label>
                            {loadedConfig?.hasPrivateKey && (
                              <span className="text-[10px] text-emerald-600 font-medium">
                                Installed & Encrypted
                              </span>
                            )}
                          </div>
                          <textarea
                            rows={4}
                            value={privateKeyPem}
                            onChange={(e) => setPrivateKeyPem(e.target.value)}
                            placeholder={loadedConfig?.hasPrivateKey ? "Leave blank to keep existing encrypted private key" : "-----BEGIN RSA PRIVATE KEY-----\n...\n-----END RSA PRIVATE KEY-----"}
                            className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                          />
                        </div>

                        <div>
                          <label className="block text-zinc-700 font-medium mb-1">Webhook Secret (Optional)</label>
                          <input
                            type="text"
                            value={webhookSecret}
                            onChange={(e) => setWebhookSecret(e.target.value)}
                            placeholder={loadedConfig?.hasWebhookSecret ? "Leave blank to keep current" : "Secret for validating GitHub webhooks"}
                            className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                          />
                        </div>
                      </div>
                    ) : (
                      <div className="space-y-3">
                        <div>
                          <div className="flex items-center justify-between mb-1">
                            <label className="block text-zinc-700 font-medium">Personal Access Token (PAT)</label>
                            {loadedConfig?.hasDefaultPatToken && (
                              <span className="text-[10px] text-zinc-500 font-mono">
                                Current: {loadedConfig.defaultPatTokenMasked}
                              </span>
                            )}
                          </div>
                          <input
                            type="password"
                            value={defaultPatToken}
                            onChange={(e) => setDefaultPatToken(e.target.value)}
                            placeholder={loadedConfig?.hasDefaultPatToken ? "Enter new token to replace" : "ghp_xxxxxxxxxxxx"}
                            className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                          />
                        </div>
                      </div>
                    )}
                  </div>
                )}

                {/* GITLAB SETTINGS */}
                {selectedProvider === 'gitlab' && (
                  <div className="space-y-4">
                    <h3 className="text-sm font-semibold text-zinc-900 border-b border-zinc-100 pb-3">GitLab Remote Settings</h3>
                    <div>
                      <label className="block text-zinc-700 font-medium mb-1">GitLab Host URL</label>
                      <input
                        type="text"
                        value={gitlabHostUrl}
                        onChange={(e) => setGitlabHostUrl(e.target.value)}
                        placeholder="https://gitlab.com or https://gitlab.mycorp.com"
                        className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                      />
                    </div>
                    <div>
                      <div className="flex items-center justify-between mb-1">
                        <label className="block text-zinc-700 font-medium">Access Token / Deploy Token</label>
                        {loadedConfig?.hasGitlabAccessToken && (
                          <span className="text-[10px] text-zinc-500 font-mono">
                            Current: {loadedConfig.gitlabAccessTokenMasked}
                          </span>
                        )}
                      </div>
                      <input
                        type="password"
                        value={gitlabAccessToken}
                        onChange={(e) => setGitlabAccessToken(e.target.value)}
                        placeholder={loadedConfig?.hasGitlabAccessToken ? "Enter new token to replace" : "glpat-xxxxxxxxxxxx"}
                        className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                      />
                    </div>
                  </div>
                )}

                {/* BITBUCKET SETTINGS */}
                {selectedProvider === 'bitbucket' && (
                  <div className="space-y-4">
                    <h3 className="text-sm font-semibold text-zinc-900 border-b border-zinc-100 pb-3">Bitbucket Remote Settings</h3>
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <label className="block text-zinc-700 font-medium mb-1">Workspace ID / Slug</label>
                        <input
                          type="text"
                          value={bitbucketWorkspace}
                          onChange={(e) => setBitbucketWorkspace(e.target.value)}
                          placeholder="e.g. acme-corp"
                          className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 text-xs focus:outline-none focus:border-zinc-400"
                        />
                      </div>
                      <div>
                        <label className="block text-zinc-700 font-medium mb-1">Username / Auth Handle</label>
                        <input
                          type="text"
                          value={bitbucketUsername}
                          onChange={(e) => setBitbucketUsername(e.target.value)}
                          placeholder="x-token-auth or bitbucket-user"
                          className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                        />
                      </div>
                    </div>
                    <div>
                      <div className="flex items-center justify-between mb-1">
                        <label className="block text-zinc-700 font-medium">Access Token / App Password</label>
                        {loadedConfig?.hasBitbucketAccessToken && (
                          <span className="text-[10px] text-zinc-500 font-mono">
                            Current: {loadedConfig.bitbucketAccessTokenMasked}
                          </span>
                        )}
                      </div>
                      <input
                        type="password"
                        value={bitbucketAccessToken}
                        onChange={(e) => setBitbucketAccessToken(e.target.value)}
                        placeholder={loadedConfig?.hasBitbucketAccessToken ? "Enter new token to replace" : "Bitbucket access token or app password"}
                        className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                      />
                    </div>
                  </div>
                )}

                {/* CURSOR ORIGIN SETTINGS */}
                {selectedProvider === 'origin' && (
                  <div className="space-y-4">
                    <h3 className="text-sm font-semibold text-zinc-900 border-b border-zinc-100 pb-3">Cursor Origin Remote Settings</h3>
                    <div>
                      <label className="block text-zinc-700 font-medium mb-1">Origin Endpoint URL</label>
                      <input
                        type="text"
                        value={originHostUrl}
                        onChange={(e) => setOriginHostUrl(e.target.value)}
                        placeholder="https://origin.cursor.com"
                        className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                      />
                    </div>
                    <div>
                      <div className="flex items-center justify-between mb-1">
                        <label className="block text-zinc-700 font-medium">Origin CLI / Access Token</label>
                        {loadedConfig?.hasOriginAccessToken && (
                          <span className="text-[10px] text-zinc-500 font-mono">
                            Current: {loadedConfig.originAccessTokenMasked}
                          </span>
                        )}
                      </div>
                      <input
                        type="password"
                        value={originAccessToken}
                        onChange={(e) => setOriginAccessToken(e.target.value)}
                        placeholder={loadedConfig?.hasOriginAccessToken ? "Enter new token to replace" : "Origin bearer access token"}
                        className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                      />
                    </div>
                  </div>
                )}

                {/* GENERIC GIT / AZURE DEVOPS SETTINGS */}
                {selectedProvider === 'generic' && (
                  <div className="space-y-4">
                    <h3 className="text-sm font-semibold text-zinc-900 border-b border-zinc-100 pb-3">Azure DevOps & Generic Git Remotes</h3>
                    <div>
                      <label className="block text-zinc-700 font-medium mb-1">Username / Service Account</label>
                      <input
                        type="text"
                        value={genericUsername}
                        onChange={(e) => setGenericUsername(e.target.value)}
                        placeholder="e.g. azure-devops or git-service-account"
                        className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 text-xs focus:outline-none focus:border-zinc-400"
                      />
                    </div>
                    <div>
                      <div className="flex items-center justify-between mb-1">
                        <label className="block text-zinc-700 font-medium">Personal Access Token (PAT)</label>
                        {loadedConfig?.hasGenericAccessToken && (
                          <span className="text-[10px] text-zinc-500 font-mono">
                            Current: {loadedConfig.genericAccessTokenMasked}
                          </span>
                        )}
                      </div>
                      <input
                        type="password"
                        value={genericAccessToken}
                        onChange={(e) => setGenericAccessToken(e.target.value)}
                        placeholder={loadedConfig?.hasGenericAccessToken ? "Enter new token to replace" : "Azure DevOps or Git PAT"}
                        className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                      />
                    </div>
                  </div>
                )}

                {feedback && (
                  <div className={`p-3 rounded-lg border text-xs flex items-center space-x-2 ${
                    feedback.type === 'success' ? 'bg-emerald-50 border-emerald-200 text-emerald-800' : 'bg-rose-50 border-rose-200 text-rose-800'
                  }`}>
                    {feedback.type === 'success' ? <CheckCircle2 className="w-4 h-4 shrink-0 text-emerald-600" /> : <AlertCircle className="w-4 h-4 shrink-0 text-rose-600" />}
                    <span>{feedback.message}</span>
                  </div>
                )}

                <div className="flex items-center justify-between pt-2">
                  <button
                    type="button"
                    onClick={() => fetchAccessibleRepos()}
                    disabled={fetchingRepos}
                    className="flex items-center space-x-1.5 bg-white hover:bg-zinc-50 text-zinc-700 px-3 py-1.5 rounded-lg border border-zinc-200 font-medium text-xs transition-colors"
                  >
                    <RefreshCw className={`w-3.5 h-3.5 ${fetchingRepos ? 'animate-spin' : ''}`} />
                    <span>Test Accessible Repositories</span>
                  </button>

                  <button
                    type="submit"
                    disabled={saving}
                    className="flex items-center space-x-1.5 bg-zinc-900 hover:bg-zinc-800 text-white px-4 py-1.5 rounded-lg font-medium text-xs transition-colors shadow-sm disabled:opacity-50"
                  >
                    <Save className="w-3.5 h-3.5" />
                    <span>{saving ? 'Encrypting & Saving...' : 'Save Provider Settings'}</span>
                  </button>
                </div>
              </form>
            )}
          </div>
        </div>

        {/* Right Reference Cards */}
        <div className="lg:col-span-5 space-y-4">
          {/* Storage Telemetry Mini Widget */}
          {storageStatus && (
            <div className="rounded-2xl border border-zinc-200/90 bg-white p-5 shadow-sm space-y-3 text-xs">
              <div className="flex items-center justify-between border-b border-zinc-100 pb-2.5">
                <div className="flex items-center space-x-2">
                  <Database className="w-4 h-4 text-emerald-600" />
                  <span className="font-semibold text-zinc-900">Storage Telemetry</span>
                  <InfoTooltip
                    title="Bare Repository Storage Breakdown"
                    badge="Metrics"
                    whatIsIt="Real-time distribution of bare Git repository caches across storage tiers and local NVMe disk quota consumption."
                    howItWorks="Monitors storage directories, calculates packfile disk utilization, and enforces LRU thresholds."
                  />
                </div>
                <span className="text-[11px] font-mono text-zinc-500">
                  {storageStatus.totalCachedRepos} Repos Cached
                </span>
              </div>

              <div className="space-y-1.5">
                <div className="flex justify-between text-[11px]">
                  <span className="text-zinc-600">Local NVMe Quota</span>
                  <span className="font-mono text-zinc-800 font-medium">
                    {storageStatus.localUsedFormatted} / {storageStatus.maxDiskQuotaFormatted} ({storageStatus.quotaUsedPercent}%)
                  </span>
                </div>
                <div className="w-full bg-zinc-100 h-2 rounded-full overflow-hidden">
                  <div
                    className={`h-full transition-all ${
                      storageStatus.quotaUsedPercent > 85 ? 'bg-rose-500' :
                      storageStatus.quotaUsedPercent > 65 ? 'bg-amber-500' : 'bg-emerald-500'
                    }`}
                    style={{ width: `${Math.min(100, Math.max(2, storageStatus.quotaUsedPercent))}%` }}
                  />
                </div>
              </div>

              <div className="grid grid-cols-4 gap-2 pt-1 text-center">
                <div className="p-2 bg-zinc-50 rounded-lg relative group">
                  <div className="font-bold text-zinc-900">{storageStatus.hotReposCount}</div>
                  <div className="text-[9px] text-orange-600 font-semibold uppercase flex items-center justify-center space-x-0.5">
                    <span>Hot</span>
                    <InfoTooltip
                      title="HOT_PERSISTENT Tier"
                      whatIsIt="Bare repository permanently pinned on local NVMe SSD."
                      howItWorks="Never evicted by LRU cleaner. Provides sub-5ms commit reachability checks and incremental delta fetches."
                      recommended="High-velocity trunk repositories."
                      position="bottom"
                      iconSize={11}
                    />
                  </div>
                </div>

                <div className="p-2 bg-zinc-50 rounded-lg relative group">
                  <div className="font-bold text-zinc-900">{storageStatus.autoLruReposCount}</div>
                  <div className="text-[9px] text-zinc-500 font-semibold uppercase flex items-center justify-center space-x-0.5">
                    <span>LRU</span>
                    <InfoTooltip
                      title="AUTO_LRU Tier"
                      whatIsIt="Default tier storing bare repositories on local NVMe with automated LRU cache eviction."
                      howItWorks="When quota or retention is exceeded, least-recently used repositories are automatically purged."
                      recommended="Standard active repositories."
                      position="bottom"
                      iconSize={11}
                    />
                  </div>
                </div>

                <div className="p-2 bg-zinc-50 rounded-lg relative group">
                  <div className="font-bold text-zinc-900">{storageStatus.ephemeralReposCount}</div>
                  <div className="text-[9px] text-purple-600 font-semibold uppercase flex items-center justify-center space-x-0.5">
                    <span>Eph</span>
                    <InfoTooltip
                      title="EPHEMERAL_STREAM Tier"
                      whatIsIt="Zero-disk ephemeral mirror mode."
                      howItWorks="Bare repository is created in temporary directory only during sync execution and deleted immediately afterwards."
                      recommended="Cold or rarely updated repositories."
                      position="bottom"
                      iconSize={11}
                    />
                  </div>
                </div>

                <div className="p-2 bg-zinc-50 rounded-lg relative group">
                  <div className="font-bold text-zinc-900">{storageStatus.nasReposCount}</div>
                  <div className="text-[9px] text-blue-600 font-semibold uppercase flex items-center justify-center space-x-0.5">
                    <span>NAS</span>
                    <InfoTooltip
                      title="NAS_MOUNT Tier"
                      whatIsIt="Network Attached Storage persistent tier."
                      howItWorks="Bare repository directory is maintained on a shared network mount (NFS/EFS), offloading host disk."
                      recommended="Large repository catalogs in cloud/container clusters."
                      position="bottom"
                      iconSize={11}
                    />
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* Security & Scopes Reference */}
          <div className="rounded-2xl border border-zinc-200/90 bg-white p-5 shadow-sm space-y-3 text-xs">
            <div className="flex items-center space-x-2 border-b border-zinc-100 pb-2.5">
              <ShieldCheck className="w-4 h-4 text-emerald-600" />
              <h3 className="font-semibold text-zinc-900">Security Architecture</h3>
            </div>

            <ul className="space-y-2.5 text-zinc-700">
              <li className="flex items-start space-x-2">
                <Check className="w-3.5 h-3.5 text-emerald-600 shrink-0 mt-0.5" />
                <div>
                  <strong className="text-zinc-900">AES-256-GCM Envelope Encryption</strong>
                  <p className="text-zinc-500 text-[11px]">All tokens and private keys are encrypted before database insertion using random salts and IVs.</p>
                </div>
              </li>
              <li className="flex items-start space-x-2">
                <Check className="w-3.5 h-3.5 text-emerald-600 shrink-0 mt-0.5" />
                <div>
                  <strong className="text-zinc-900">Self-Healing Circuit Breaker</strong>
                  <p className="text-zinc-500 text-[11px]">Auto-pauses on sustained provider downtime to protect queue messages; tests and auto-resumes once connectivity returns.</p>
                </div>
              </li>
              <li className="flex items-start space-x-2">
                <Check className="w-3.5 h-3.5 text-emerald-600 shrink-0 mt-0.5" />
                <div>
                  <strong className="text-zinc-900">Jittered Exponential Retries</strong>
                  <p className="text-zinc-500 text-[11px]">Protects SCM APIs from rate limits and concurrency bursts using randomized backoff.</p>
                </div>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </div>
  );
};
