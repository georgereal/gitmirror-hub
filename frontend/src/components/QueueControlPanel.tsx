import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { Play, Pause, RefreshCw, Trash2, AlertTriangle, Activity, RotateCcw } from 'lucide-react';
import { QueueStatus } from '../types';
import { probeAndResetCircuitBreaker } from '../services/api';
import { ConsumerRuntimePanel } from './ConsumerRuntimePanel';

interface QueueControlPanelProps {
  queueStatus: QueueStatus | null;
  onPauseConsumer: () => Promise<void>;
  onResumeConsumer: () => Promise<void>;
  onRedriveDlq: () => Promise<void>;
  onPurgeDlq: () => Promise<void>;
}

export const QueueControlPanel: React.FC<QueueControlPanelProps> = ({
  queueStatus,
  onPauseConsumer,
  onResumeConsumer,
  onRedriveDlq,
  onPurgeDlq,
}) => {
  const [loadingAction, setLoadingAction] = useState<string | null>(null);
  const [circuitResetMessage, setCircuitResetMessage] = useState<string | null>(null);

  const isPaused = queueStatus?.consumerPaused || queueStatus?.simulationStatus?.consumerPaused || false;
  const listenerStopped = queueStatus != null && queueStatus.consumerRunning === false && !isPaused;
  const dlqCount = queueStatus?.dlqMessageCount ?? 0;
  const isConnected = queueStatus?.brokerConnected ?? true;

  const handleAction = async (action: string, fn: () => Promise<void>) => {
    setLoadingAction(action);
    try {
      await fn();
    } finally {
      setTimeout(() => setLoadingAction(null), 600);
    }
  };

  const handleCircuitBreakerReset = async () => {
    setLoadingAction('circuit-reset');
    try {
      const res = await probeAndResetCircuitBreaker(false);
      setCircuitResetMessage(res.message);
      await onResumeConsumer();
      setTimeout(() => setCircuitResetMessage(null), 5000);
    } catch (e: any) {
      setCircuitResetMessage('Reset error: ' + (e.message || 'Unknown error'));
    } finally {
      setLoadingAction(null);
    }
  };

  return (
    <div className="space-y-6">
      {/* Title */}
      <div>
        <h2 className="text-base font-semibold text-zinc-900">Observability & Message Queue</h2>
        <p className="text-xs text-zinc-500 mt-0.5">
          Monitor RabbitMQ Ready (pending) vs Unacked (in-flight), Dead Letter Queue state, and consumer threads.
          Use the <Link to="/queues" className="text-zinc-800 font-medium underline underline-offset-2">Queue Manager</Link> to inspect waiting jobs and cancel a backlog.
        </p>
      </div>

      {/* Circuit Breaker Alert Banner when Paused */}
      {isPaused && (
        <div className="rounded-2xl border border-amber-200 bg-amber-50/80 p-4 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 text-xs">
          <div className="flex items-start space-x-2.5">
            <AlertTriangle className="w-4 h-4 text-amber-600 shrink-0 mt-0.5" />
            <div>
              <strong className="text-amber-900">Consumer Ingestion Paused / Circuit Breaker Active</strong>
              <p className="text-amber-700 text-[11px] mt-0.5">
                Webhooks are safely buffered and durable in RabbitMQ. You can run an immediate live probe against SCM providers to reset the circuit breaker.
              </p>
            </div>
          </div>

          <button
            onClick={handleCircuitBreakerReset}
            disabled={loadingAction === 'circuit-reset'}
            className="flex items-center space-x-1.5 px-3 py-1.5 bg-amber-600 hover:bg-amber-700 text-white rounded-lg font-medium text-xs shadow-sm shrink-0 disabled:opacity-50 transition-colors"
          >
            <RotateCcw className={`w-3.5 h-3.5 ${loadingAction === 'circuit-reset' ? 'animate-spin' : ''}`} />
            <span>Probe Health & Resume</span>
          </button>
        </div>
      )}

      {circuitResetMessage && (
        <div className="rounded-xl border border-blue-200 bg-blue-50 p-3 text-xs text-blue-800 flex items-center space-x-2">
          <Activity className="w-4 h-4 text-blue-600 shrink-0" />
          <span>{circuitResetMessage}</span>
        </div>
      )}

      <ConsumerRuntimePanel lanes={queueStatus?.consumers} compact />

      {/* Metric Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        {/* AMQP Connection */}
        <div className="rounded-2xl border border-zinc-200/90 bg-white p-5 shadow-sm space-y-2">
          <div className="flex items-center justify-between text-xs text-zinc-500 font-medium">
            <span>Broker State</span>
            <span className={`inline-flex items-center space-x-1.5 px-2 py-0.5 rounded-full text-[11px] font-medium ${
              isConnected ? 'bg-emerald-50 text-emerald-700 border border-emerald-200' : 'bg-rose-50 text-rose-700 border border-rose-200'
            }`}>
              <span className={`w-1.5 h-1.5 rounded-full ${isConnected ? 'bg-emerald-500' : 'bg-rose-500'}`} />
              <span>{isConnected ? 'Connected' : 'Offline'}</span>
            </span>
          </div>
          <div className="text-lg font-bold text-zinc-900 truncate">
            {queueStatus?.brokerAddress || 'CloudAMQP / Localhost'}
          </div>
          <p className="text-[11px] text-zinc-400 font-mono">AMQP {queueStatus?.queueName || 'git.sync.queue'}</p>
        </div>

        {/* Dead Letter Queue */}
        <div className="rounded-2xl border border-zinc-200/90 bg-white p-5 shadow-sm space-y-2">
          <div className="flex items-center justify-between text-xs text-zinc-500 font-medium">
            <span>Dead Letter Queue (DLQ)</span>
            {dlqCount > 0 && (
              <span className="text-[11px] px-2 py-0.5 rounded-full bg-rose-50 text-rose-700 border border-rose-200 font-medium">
                {dlqCount} failed
              </span>
            )}
          </div>
          <div className="text-2xl font-bold text-zinc-900">{dlqCount}</div>
          <p className="text-[11px] text-zinc-400">Failed jobs awaiting recovery</p>
        </div>
      </div>

      {/* Control Actions Box */}
      <div className="rounded-2xl border border-zinc-200/90 bg-white p-6 shadow-sm space-y-4">
        <h3 className="text-sm font-semibold text-zinc-900 border-b border-zinc-100 pb-3">
          Queue Operations & DLQ Redrive
        </h3>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          {/* Consumer State Switch */}
          <div className="p-4 rounded-xl bg-zinc-50/70 border border-zinc-200/80 flex items-center justify-between">
            <div>
              <div className="text-xs font-semibold text-zinc-800">Execution workers</div>
              <div className="text-[11px] text-zinc-500 mt-0.5">
                {listenerStopped
                  ? 'A listener is stopped. Resume to skip-ACK cancelled leftovers.'
                  : isPaused
                    ? 'Both execution lanes are paused. Messages stay durable in RabbitMQ.'
                    : 'Full-mirror and webhook lanes run in parallel (same pair still serializes).'}
              </div>
            </div>

            <button
              onClick={() => handleAction('consumer', isPaused || listenerStopped ? onResumeConsumer : onPauseConsumer)}
              disabled={loadingAction === 'consumer'}
              className={`flex items-center space-x-1.5 px-3.5 py-1.5 rounded-lg text-xs font-medium transition-colors shrink-0 ml-4 ${
                isPaused || listenerStopped
                  ? 'bg-zinc-900 hover:bg-zinc-800 text-white'
                  : 'bg-white hover:bg-zinc-100 border border-zinc-200 text-zinc-800'
              }`}
            >
              {isPaused || listenerStopped ? <Play className="w-3.5 h-3.5" /> : <Pause className="w-3.5 h-3.5" />}
              <span>{isPaused || listenerStopped ? 'Resume Consumer' : 'Pause Consumer'}</span>
            </button>
          </div>

          {/* DLQ Redrive & Purge */}
          <div className="p-4 rounded-xl bg-zinc-50/70 border border-zinc-200/80 flex items-center justify-between">
            <div>
              <div className="text-xs font-semibold text-zinc-800">Dead Letter Recovery</div>
              <div className="text-[11px] text-zinc-500 mt-0.5">
                Re-enqueue failed payloads from DLQ onto the matching execution lane.
              </div>
            </div>

            <div className="flex items-center space-x-2 shrink-0 ml-4">
              <button
                onClick={() => handleAction('redrive', onRedriveDlq)}
                disabled={dlqCount === 0 || loadingAction === 'redrive'}
                className="flex items-center space-x-1 bg-zinc-900 hover:bg-zinc-800 text-white px-3 py-1.5 rounded-lg text-xs font-medium transition-colors disabled:opacity-40"
              >
                <RefreshCw className={`w-3.5 h-3.5 ${loadingAction === 'redrive' ? 'animate-spin' : ''}`} />
                <span>Redrive ({dlqCount})</span>
              </button>

              <button
                onClick={() => handleAction('purge', onPurgeDlq)}
                disabled={dlqCount === 0 || loadingAction === 'purge'}
                className="p-1.5 rounded-lg text-zinc-400 hover:text-rose-600 hover:bg-rose-50 border border-transparent hover:border-rose-200 transition-colors disabled:opacity-30"
                title="Purge DLQ"
              >
                <Trash2 className="w-4 h-4" />
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
