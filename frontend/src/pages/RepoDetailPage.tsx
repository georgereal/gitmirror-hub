import React, { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { RepoDetailView } from '../components/RepoDetailView';
import { RepoMapping, SyncJob, JobProgress, DiffInspectionProgress } from '../types';
import { getMappings, updateMapping, deleteMapping, triggerManualSync, getRecentJobs, getJobs } from '../services/api';
import { initWebSocket } from '../services/websocket';
import { isLiveSyncStatus } from '../components/SyncPipelineStepper';
import { mergeProviderTraffic } from '../components/ProviderTrafficStrip';

export const RepoDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [mapping, setMapping] = useState<RepoMapping | null>(null);
  const [allMappings, setAllMappings] = useState<RepoMapping[]>([]);
  const [recentJobs, setRecentJobs] = useState<SyncJob[]>([]);
  const [activeRepoTab, setActiveRepoTab] = useState<'code' | 'pull-requests' | 'settings'>('code');
  const [loading, setLoading] = useState(true);
  const [progressByJobId, setProgressByJobId] = useState<Record<number, JobProgress>>({});
  const [diffProgress, setDiffProgress] = useState<DiffInspectionProgress | null>(null);

  const loadRepoData = useCallback(async () => {
    if (!id) return;
    try {
      const mappingId = parseInt(id, 10);
      const [fetchedMappings, pairJobsPage, globalRecentJobs] = await Promise.all([
        getMappings(),
        getJobs(0, 50, undefined, mappingId).catch(() => ({ content: [] as SyncJob[], totalElements: 0, totalPages: 0 })),
        getRecentJobs().catch(() => [] as SyncJob[]),
      ]);
      setAllMappings(fetchedMappings);
      const found = fetchedMappings.find((m) => m.id === mappingId);
      if (found) {
        setMapping(found);
      }
      const map = new Map<number, SyncJob>();
      (pairJobsPage.content || []).forEach((j) => map.set(j.id, j));
      (globalRecentJobs || []).forEach((j) => {
        if (j.mappingId === mappingId) map.set(j.id, j);
      });
      setRecentJobs(Array.from(map.values()).sort((a, b) => b.id - a.id));
    } catch (e) {
      console.error('Failed to load repo detail:', e);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    loadRepoData();
  }, [loadRepoData]);

  useEffect(() => {
    const cleanup = initWebSocket((data) => {
      if (data.type === 'JOB_UPDATE' && data.job) {
        const job = data.job as SyncJob;
        if (id && job.mappingId === parseInt(id, 10)) {
          setRecentJobs((prev) => {
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
          loadRepoData();
        }
      }
      if (data.type === 'JOB_PROGRESS' && data.jobId != null) {
        const progress = data as JobProgress;
        if (!id || progress.mappingId == null || progress.mappingId === parseInt(id, 10)) {
          setRecentJobs((jobs) => {
            const job = jobs.find((j) => j.id === progress.jobId);
            if (!job || isLiveSyncStatus(job.status)) {
              setProgressByJobId((prev) => {
                const existing = prev[progress.jobId];
                const mergedTraffic = mergeProviderTraffic(
                  existing?.providerTraffic,
                  progress.providerTraffic
                );
                return {
                  ...prev,
                  [progress.jobId]: {
                    ...progress,
                    providerTraffic: mergedTraffic ?? progress.providerTraffic ?? existing?.providerTraffic,
                  },
                };
              });
            }
            return jobs;
          });
        }
      }
      if ((data.type === 'DIFF_PROGRESS' || data.type === 'DIFF_COMPLETE') && data.mappingId != null) {
        const progress = data as DiffInspectionProgress;
        if (id && progress.mappingId === parseInt(id, 10)) {
          setDiffProgress(progress);
        }
      }
    });

    return () => {
      cleanup();
    };
  }, [id, loadRepoData]);

  const handleUpdate = async (updated: Partial<RepoMapping>) => {
    if (!mapping) return;
    const res = await updateMapping(mapping.id, updated);
    setMapping(res);
  };

  const handleTriggerSync = async (mappingId: number, branch?: string, overwriteFromSource = false, startFresh = false) => {
    try {
      await triggerManualSync(mappingId, branch || '*', 'A_TO_B', overwriteFromSource, startFresh);
      setTimeout(loadRepoData, 1500);
    } catch (e) {
      console.error('Failed to trigger sync:', e);
    }
  };

  const handleDelete = async (mappingId: number) => {
    try {
      await deleteMapping(mappingId);
      navigate('/repos');
    } catch (e) {
      console.error('Failed to delete mapping:', e);
    }
  };

  if (loading) {
    return (
      <div className="text-center py-16 text-xs text-zinc-400">
        Loading repository details...
      </div>
    );
  }

  if (!mapping) {
    return (
      <div className="text-center py-16 space-y-3">
        <p className="text-sm text-zinc-600 font-medium">Repository pair not found.</p>
        <button
          onClick={() => navigate('/repos')}
          className="px-3 py-1.5 bg-zinc-900 text-white rounded-lg text-xs font-medium"
        >
          Back to Repositories
        </button>
      </div>
    );
  }

  return (
    <RepoDetailView
      mapping={mapping}
      activeRepoTab={activeRepoTab}
      onUpdate={handleUpdate}
      onTriggerSync={handleTriggerSync}
      onDelete={handleDelete}
      onBack={() => navigate('/repos')}
      recentJobs={recentJobs}
      progressByJobId={progressByJobId}
      diffProgress={diffProgress}
      onDiffProgressClear={() => setDiffProgress(null)}
      onRefreshJobs={loadRepoData}
      allMappings={allMappings}
    />
  );
};
