import React, { useState, useEffect } from 'react';
import {
  Terminal, RefreshCw, CheckCircle2, AlertCircle, Save
} from 'lucide-react';
import { InfoTooltip } from '../../components/InfoTooltip';
import { SystemEngineConfig, LoggingSinkTestResult } from '../../types';
import {
  getSystemEngineConfig,
  saveSystemEngineConfig,
  testLoggingSink
} from '../../services/api';

export const EnterpriseLoggingPage: React.FC = () => {
  const [systemConfig, setSystemConfig] = useState<SystemEngineConfig | null>(null);

  const [loggingSink, setLoggingSink] = useState<string>('CONSOLE');
  const [loggingLevel, setLoggingLevel] = useState<string>('INFO');
  const [splunkHecUrl, setSplunkHecUrl] = useState('');
  const [splunkHecToken, setSplunkHecToken] = useState('');
  const [splunkIndex, setSplunkIndex] = useState('main');
  const [splunkSourceType, setSplunkSourceType] = useState('_json');
  const [logstashHost, setLogstashHost] = useState('');
  const [syslogHost, setSyslogHost] = useState('');
  const [rollingFilePath, setRollingFilePath] = useState('/tmp/git-utility-mirrors/logs/git-utility.log');
  const [jsonStructuredEnabled, setJsonStructuredEnabled] = useState(true);

  const [saving, setSaving] = useState(false);
  const [testingSink, setTestingSink] = useState(false);
  const [sinkTestResult, setSinkTestResult] = useState<LoggingSinkTestResult | null>(null);
  const [feedback, setFeedback] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

  useEffect(() => {
    loadConfig();
  }, []);

  const loadConfig = async () => {
    try {
      const s = await getSystemEngineConfig();
      if (s) {
        setSystemConfig(s);
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
      console.error('Error loading logging config:', e);
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

  const handleSaveLogging = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setFeedback(null);
    try {
      const payload: Partial<SystemEngineConfig> = {
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
      setSplunkHecToken('');

      setFeedback({
        type: 'success',
        message: 'Enterprise Logging parameters updated and dynamic Logback log level hot-reloaded!'
      });
      setTimeout(() => setFeedback(null), 4000);
    } catch (err: any) {
      setFeedback({
        type: 'error',
        message: err.response?.data?.message || err.message || 'Failed to save logging configuration'
      });
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-6">
      <div className="rounded-2xl border border-zinc-200/90 bg-white p-6 shadow-sm">
        <form onSubmit={handleSaveLogging} className="space-y-6 text-xs">
          <div className="flex items-center justify-between border-b border-zinc-200/60 pb-2.5">
            <div className="flex items-center space-x-2">
              <Terminal className="w-4 h-4 text-emerald-600" />
              <span className="font-semibold text-zinc-900 text-sm">Enterprise Multi-Sink Logging & SIEM Forwarder</span>
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

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
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
                className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 text-xs focus:outline-none focus:border-zinc-400"
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
                className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 text-xs focus:outline-none focus:border-zinc-400"
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
            <div className="p-4 bg-zinc-50 border border-zinc-200/80 rounded-xl space-y-3">
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
            <div className="p-4 bg-zinc-50 border border-zinc-200/80 rounded-xl space-y-2">
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
            <div className="p-4 bg-zinc-50 border border-zinc-200/80 rounded-xl space-y-2">
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
            <div className="p-4 bg-zinc-50 border border-zinc-200/80 rounded-xl space-y-2">
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
            <div className={`p-3 rounded-lg border text-xs flex items-center space-x-2 ${
              sinkTestResult.success ? 'bg-emerald-50 border-emerald-200 text-emerald-800' : 'bg-rose-50 border-rose-200 text-rose-800'
            }`}>
              {sinkTestResult.success ? <CheckCircle2 className="w-4 h-4 text-emerald-600 shrink-0" /> : <AlertCircle className="w-4 h-4 text-rose-600 shrink-0" />}
              <div className="flex-1">
                <span className="font-semibold">{sinkTestResult.message}</span>
                {sinkTestResult.latencyMs !== undefined && (
                  <span className="ml-2 opacity-75 font-mono">({sinkTestResult.latencyMs}ms)</span>
                )}
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

          <div className="flex justify-end pt-2">
            <button
              type="submit"
              disabled={saving}
              className="inline-flex items-center space-x-1.5 bg-zinc-900 hover:bg-zinc-800 text-white text-xs px-4 py-2 rounded-lg font-medium shadow-sm transition-colors disabled:opacity-50"
            >
              <Save className="w-3.5 h-3.5" />
              <span>{saving ? 'Saving...' : 'Save Logging Sinks'}</span>
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
