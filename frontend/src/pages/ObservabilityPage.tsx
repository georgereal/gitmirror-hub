import React, { useState, useEffect } from 'react';
import { QueueControlPanel } from '../components/QueueControlPanel';
import { LiveSyncTable } from '../components/LiveSyncTable';
import { UnmappedWebhooksView } from '../components/UnmappedWebhooksView';
import { JobLogModal } from '../components/JobLogModal';
import { PairConfigModal } from '../components/PairConfigModal';
import { ClusterFleetStrip } from '../components/ClusterFleetStrip';
import { SyncJob, QueueStatus, RepoMapping, JobProgress } from '../types';
import {
  getRecentJobs,
  getQueueStatus,
  getMappings,
  pauseConsumer,
  resumeConsumer,
  redriveDlq,
  purgeDlq,
  retryJob,
  cancelJob,
  pauseJob,
  createMapping
} from '../services/api';
import { initWebSocket } from '../services/websocket';
import { isLiveSyncStatus } from '../components/SyncPipelineStepper';
import { useClusterRuntimeMetrics } from '../hooks/useClusterRuntimeMetrics';

export const ObservabilityPage: React.FC = () => {
  const [observabilitySubTab, setObservabilitySubTab] = useState<'sync-activity' | 'discarded-webhooks'>('sync-activity');
  const [jobs, setJobs] = useState<SyncJob[]>([]);
  const [queueStatus, setQueueStatus] = useState<QueueStatus | null>(null);
  const [mappings, setMappings] = useState<RepoMapping[]>([]);

  const [selectedJobForLogs, setSelectedJobForLogs] = useState<SyncJob | null>(null);
  const [unmappedConfigurePair, setUnmappedConfigurePair] = useState<Partial<RepoMapping> | null>(null);
  const [progressByJobId, setProgressByJobId] = useState<Record<number, JobProgress>>({});
  const { cluster } = useClusterRuntimeMetrics(5000);

  const loadData = async () => {
    try {
      const [rj, q, m] = await Promise.allSettled([
        getRecentJobs(),
        getQueueStatus(),
        getMappings(),
      ]);
      if (rj.status === 'fulfilled') setJobs(rj.value);
      if (q.status === 'fulfilled') setQueueStatus(q.value);
      if (m.status === 'fulfilled') setMappings(m.value);
    } catch (e) {
      console.error('Failed to load observability data:', e);
    }
  };

  useEffect(() => {
    loadData();
    const timer = setInterval(loadData, 5000);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    const cleanup = initWebSocket((data) => {
      if (data.type === 'JOB_UPDATE' && data.job) {
        const job = data.job as SyncJob;
        setJobs((prev) => {
          const exists = prev.some((j) => j.id === job.id);
          if (exists) {
            return prev.map((j) => (j.id === job.id ? job : j));
          }
          return [job, ...prev];
        });
        if (job.status && job.status !== 'IN_PROGRESS' && job.status !== 'QUEUED') {
          setProgressByJobId((prev) => {
            if (!(job.id in prev)) return prev;
            const next = { ...prev };
            delete next[job.id];
            return next;
          });
        }
        setSelectedJobForLogs((current) => (current && current.id === job.id ? job : current));
      }
      if (data.type === 'JOB_PROGRESS' && data.jobId != null) {
        const progress = data as JobProgress;
        setJobs((jobs) => {
          const job = jobs.find((j) => j.id === progress.jobId);
          if (!job || isLiveSyncStatus(job.status)) {
            setProgressByJobId((prev) => ({ ...prev, [progress.jobId]: progress }));
          }
          return jobs;
        });
      }
    });
    return () => cleanup();
  }, []);

  const handleRetryJob = async (jobId: number) => {
    try {
      await retryJob(jobId);
      await loadData();
    } catch (e) {
      console.error('Failed to retry job:', e);
    }
  };

  const handleCancelJob = async (jobId: number) => {
    try {
      await cancelJob(jobId);
      await loadData();
    } catch (e) {
      console.error('Failed to cancel job:', e);
    }
  };

  const handlePauseJob = async (jobId: number) => {
    try {
      await pauseJob(jobId);
      await loadData();
    } catch (e) {
      console.error('Failed to pause job:', e);
    }
  };

  const handleConfigurePairFromUnmapped = (event: any) => {
    setUnmappedConfigurePair({
      name: event.repository || 'auto-configured-pair',
      repoAUrl: event.repositoryUrl || '',
      repoBUrl: '',
      branchPattern: event.branch ? event.branch.replace('refs/heads/', '') : '*',
      syncDirection: 'BIDIRECTIONAL',
      storageTier: 'AUTO_LRU',
      active: true,
    });
  };

  return (
    <div className="space-y-6">
      <ClusterFleetStrip cluster={cluster} compact showInstallUsage />

      <QueueControlPanel
        queueStatus={queueStatus}
        onPauseConsumer={async () => {
          await pauseConsumer();
          await loadData();
        }}
        onResumeConsumer={async () => {
          await resumeConsumer();
          await loadData();
        }}
        onRedriveDlq={async () => {
          await redriveDlq();
          await loadData();
        }}
        onPurgeDlq={async () => {
          await purgeDlq();
          await loadData();
        }}
      />

      {/* Sub-tab Switcher */}
      <div className="flex items-center space-x-1.5 border-b border-zinc-200/80 pb-2">
        <button
          onClick={() => setObservabilitySubTab('sync-activity')}
          className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
            observabilitySubTab === 'sync-activity'
              ? 'bg-zinc-900 text-white shadow-sm'
              : 'text-zinc-600 hover:text-zinc-900 hover:bg-zinc-100'
          }`}
        >
          Sync Activity Stream ({jobs.length})
        </button>
        <button
          onClick={() => setObservabilitySubTab('discarded-webhooks')}
          className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors flex items-center space-x-1.5 ${
            observabilitySubTab === 'discarded-webhooks'
              ? 'bg-zinc-900 text-white shadow-sm'
              : 'text-zinc-600 hover:text-zinc-900 hover:bg-zinc-100'
          }`}
        >
          <span>Discarded & Unmapped Webhooks</span>
        </button>
      </div>

      {observabilitySubTab === 'sync-activity' ? (
        <LiveSyncTable
          jobs={jobs}
          progressByJobId={progressByJobId}
          onViewLogs={(job) => setSelectedJobForLogs(job)}
          onRetry={handleRetryJob}
          onPause={handlePauseJob}
          onCancel={handleCancelJob}
        />
      ) : (
        <UnmappedWebhooksView onConfigurePair={handleConfigurePairFromUnmapped} />
      )}

      {/* Audit Log Drawer */}
      <JobLogModal
        job={selectedJobForLogs}
        progress={selectedJobForLogs ? progressByJobId[selectedJobForLogs.id] : undefined}
        onClose={() => setSelectedJobForLogs(null)}
        onRetry={handleRetryJob}
        onPause={handlePauseJob}
        onCancel={handleCancelJob}
        onJobUpdated={setSelectedJobForLogs}
      />

      {/* 1-Click Configure Mapping Modal */}
      {unmappedConfigurePair && (
        <PairConfigModal
          mapping={unmappedConfigurePair as any}
          isOpen={!!unmappedConfigurePair}
          onClose={() => setUnmappedConfigurePair(null)}
          onSave={async (mappingData) => {
            await createMapping(mappingData);
            setUnmappedConfigurePair(null);
            await loadData();
          }}
          existingMappings={mappings}
        />
      )}
    </div>
  );
};
