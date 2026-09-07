import React, { useState, useEffect } from 'react';
import { SimulationLab } from '../components/SimulationLab';
import { RepoMapping, QueueStatus } from '../types';
import {
  getMappings,
  getQueueStatus,
  updateSimulationConfig,
  emitSyntheticWebhook
} from '../services/api';

export const SimulationPage: React.FC = () => {
  const [mappings, setMappings] = useState<RepoMapping[]>([]);
  const [queueStatus, setQueueStatus] = useState<QueueStatus | null>(null);

  const loadData = async () => {
    try {
      const [m, q] = await Promise.allSettled([
        getMappings(),
        getQueueStatus(),
      ]);
      if (m.status === 'fulfilled') setMappings(m.value);
      if (q.status === 'fulfilled') setQueueStatus(q.value);
    } catch (e) {
      console.error('Failed to load simulation data:', e);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  return (
    <div className="space-y-6">
      <SimulationLab
        mappings={mappings}
        queueStatus={queueStatus}
        onUpdateSimulationConfig={async (config) => {
          await updateSimulationConfig(config);
          await loadData();
        }}
        onEmitSyntheticWebhook={async (data) => {
          const res = await emitSyntheticWebhook(data);
          await loadData();
          return res;
        }}
      />
    </div>
  );
};
