import React from 'react';
import { KafkaWebhookPanel } from '../components/KafkaWebhookPanel';

/** Incremental Kafka consumer group. Shown in nav only when the webhook bus provider is kafka. */
export const KafkaWebhookPage: React.FC = () => {
  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-base font-semibold text-zinc-900">Kafka</h2>
        <p className="text-xs text-zinc-500 mt-0.5">
          Incremental webhook topic for this Hub. Pending and committed counts are for the consumer group configured on the backend.
        </p>
      </div>
      <KafkaWebhookPanel />
    </div>
  );
};
