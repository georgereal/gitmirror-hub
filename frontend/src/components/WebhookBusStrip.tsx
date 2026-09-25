import React, { useEffect, useState } from 'react';
import { getWebhookBus, redriveWebhookBus, WebhookBusStatus } from '../services/api';

/**
 * Incremental webhook bus (Kafka or Rabbit), separate from operator full-mirror messaging.
 */
export const WebhookBusStrip: React.FC = () => {
  const [status, setStatus] = useState<WebhookBusStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);

  const load = () => {
    getWebhookBus()
      .then(setStatus)
      .catch(() => setStatus(null));
  };

  useEffect(() => {
    load();
    const timer = window.setInterval(load, 15000);
    return () => window.clearInterval(timer);
  }, []);

  if (!status || status.provider === 'off' || status.provider === 'kafka') {
    return null;
  }

  const lane = status.topic || status.queue || status.provider;
  const redrive = async () => {
    setBusy(true);
    setNote(null);
    try {
      const result = await redriveWebhookBus(10);
      const label = status?.provider === 'kafka' ? 'stored failure' : 'dead-letter record';
      setNote(`Replayed ${result.redriven} ${label}${result.redriven === 1 ? '' : 's'}.`);
      load();
    } catch (err) {
      setNote(err instanceof Error ? err.message : 'Redrive failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="rounded-2xl border border-zinc-200 bg-white p-4 text-xs text-zinc-700">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
        <div>
          <div className="font-medium text-zinc-900">Incremental webhook bus · {status.provider}</div>
          <p className="text-[11px] text-zinc-500 mt-0.5">
            {lane}
            {status.groupId ? ` · group ${status.groupId}` : ''}
            {(status.pending ?? status.lag) != null ? ` · pending ${status.pending ?? status.lag}` : ''}
            {status.processed != null ? ` · committed ${status.processed}` : ''}
            {status.provider === 'kafka' && status.storedFailureCount != null
              ? ` · ${status.storedFailureCount} stored failure${status.storedFailureCount === 1 ? '' : 's'}`
              : ''}
          </p>
        </div>
        <button
          type="button"
          onClick={redrive}
          disabled={busy}
          className="px-3 py-1.5 rounded-lg border border-zinc-200 hover:bg-zinc-50 disabled:opacity-50"
        >
          {busy
            ? 'Replaying…'
            : status.provider === 'kafka'
              ? 'Replay stored failures'
              : 'Redrive dead letter'}
        </button>
      </div>
      {note && <p className="mt-2 text-[11px] text-zinc-500">{note}</p>}
    </div>
  );
};
