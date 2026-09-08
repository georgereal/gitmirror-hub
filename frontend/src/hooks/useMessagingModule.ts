import { useEffect, useState } from 'react';
import { getMessagingModule } from '../services/api';
import { MessagingModuleInfo } from '../types';

const DEFAULT_RABBITMQ: MessagingModuleInfo = {
  provider: 'rabbitmq',
  displayName: 'RabbitMQ',
  description: 'Durable AMQP lanes with DLQ, purge, and multi-pod consumers.',
  durableBroker: true,
  supportsQueueManager: true,
  supportsDlq: true,
  supportsPurge: true,
  supportsPauseConsumers: true,
  supportsInboundBrokerQueue: true,
};

/**
 * Loads the active messaging fabric so nav / queue pages can adapt (rabbitmq vs none).
 */
export function useMessagingModule() {
  const [messaging, setMessaging] = useState<MessagingModuleInfo | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    getMessagingModule()
      .then((m) => {
        if (!cancelled) setMessaging(m);
      })
      .catch(() => {
        if (!cancelled) setMessaging(DEFAULT_RABBITMQ);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return { messaging: messaging ?? DEFAULT_RABBITMQ, loading, known: messaging != null };
}
