import React, { useState, useEffect, useMemo, useRef } from 'react';
import {
  CheckCircle2,
  ExternalLink,
  Play,
  ShieldCheck,
  RefreshCw,
  Save,
  Trash2,
  GitBranch,
  Check,
  AlertCircle,
  AlertTriangle,
  GitFork,
  ArrowRight,
  ArrowLeft,
  GitCommit,
  Layers,
  Tag,
  GitPullRequest,
  Clock,
  ChevronRight,
  Sparkles,
  HardDrive,
  Database,
  Package,
  FileCheck,
  Activity,
  FileCode,
  Settings,
  Download,
  Search,
  CheckCircle,
  XCircle,
  FileText,
  KeyRound
} from 'lucide-react';
import { RepoMapping, SyncJob, PermissionCheckReport, SyncDiffReport, StorageTier, JobProgress, TrunkConflictPolicy, SyncConflictRecord, DiffInspectionProgress } from '../types';
import {
  testRepoConnection,
  updateMapping,
  getSyncDiffReport,
  syncPullRequests,
  syncLfsObjects,
  syncReleases,
  cancelJob,
  pauseJob,
  getMappingConflicts,
  resolveMappingConflict,
  openConflictPr
} from '../services/api';
import { InfoTooltip } from './InfoTooltip';
import { JobLogModal } from './JobLogModal';
import { SyncRunsHistoryModal } from './SyncRunsHistoryModal';
import { DiffInspectionModal } from './DiffInspectionModal';
import { PairConfigModal } from './PairConfigModal';
import { BranchComparisonTable } from './BranchComparisonTable';
import { formatBytes, formatDuration } from '../utils/format';
import { findRepoCollision } from '../utils/repoUrl';
import { applySuccessfulMirrorJob, applyJobMirrorMetrics, applyLiveDiffProgress, isPrHandled, isPrMirrored, normalizeDiffReport, prDiscussionSummary, prsAccountedFromReport } from '../utils/syncDiff';
import { shouldWarnBidirectionalBackup } from '../utils/mirrorTopology';

interface RepoDetailViewProps {
  mapping: RepoMapping;
  activeRepoTab?: 'code' | 'pull-requests' | 'metadata' | 'settings';
  onUpdate: (updated: Partial<RepoMapping>) => Promise<void>;
  onTriggerSync: (id: number, branch?: string, overwriteFromSource?: boolean, startFresh?: boolean) => Promise<void>;
  onDelete: (id: number) => Promise<void>;
  onBack: () => void;
  recentJobs: SyncJob[];
  progressByJobId?: Record<number, JobProgress>;
  diffProgress?: DiffInspectionProgress | null;
  onDiffProgressClear?: () => void;
  onRefreshJobs?: () => void;
  allMappings?: RepoMapping[];
}

export const RepoDetailView: React.FC<RepoDetailViewProps> = ({
  mapping,
  activeRepoTab = 'code',
  onUpdate,
  onTriggerSync,
  onDelete,
  onBack,
  recentJobs,
  progressByJobId = {},
  diffProgress = null,
  onDiffProgressClear,
  onRefreshJobs,
  allMappings = []
}) => {
  const [currentTab, setCurrentTab] = useState<'code' | 'pull-requests' | 'metadata' | 'settings'>(activeRepoTab);
  const [metadataSubTab, setMetadataSubTab] = useState<'tags' | 'releases' | 'lfs' | 'ci'>('tags');
  const [subTab, setSubTab] = useState<'rules' | 'general' | 'apps' | 'history' | 'advanced'>('rules');
  const [triggering, setTriggering] = useState(false);
  const [triggeringBranch, setTriggeringBranch] = useState<string | null>(null);
  const [confirmOverwrite, setConfirmOverwrite] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  // Tag search / filter
  const [tagSearchQuery, setTagSearchQuery] = useState('');

  // Sync diff inspection report
  const [diffReport, setDiffReport] = useState<SyncDiffReport | null>(null);
  const [initialDiffLoading, setInitialDiffLoading] = useState(true);
  const [fullDiffLoading, setFullDiffLoading] = useState(false);
  const [showDiffModal, setShowDiffModal] = useState(false);
  const [diffError, setDiffError] = useState<string | null>(null);

  // Form states
  const [name, setName] = useState(mapping.name);
  const [repoAUrl, setRepoAUrl] = useState(mapping.repoAUrl);
  const [repoBUrl, setRepoBUrl] = useState(mapping.repoBUrl);
  const [branchPattern, setBranchPattern] = useState(mapping.branchPattern || '*');
  const [syncDirection, setSyncDirection] = useState(mapping.syncDirection || 'BIDIRECTIONAL');
  const [trunkConflictPolicy, setTrunkConflictPolicy] = useState<TrunkConflictPolicy>(mapping.trunkConflictPolicy || 'ISOLATE');
  const [storageTier, setStorageTier] = useState<StorageTier>(mapping.storageTier || 'AUTO_LRU');
  const [active, setActive] = useState(mapping.active ?? true);

  // Access check state
  const [testingAccess, setTestingAccess] = useState(false);
  const [checkReport, setCheckReport] = useState<PermissionCheckReport | null>(null);

  // Selected job for log modal
  const [selectedJobForLogs, setSelectedJobForLogs] = useState<SyncJob | null>(null);
  const [showRunsModal, setShowRunsModal] = useState(false);
  const [rebindOpen, setRebindOpen] = useState(false);

  useEffect(() => {
    setSelectedJobForLogs((current) => {
      if (!current) return current;
      const latest = recentJobs.find((j) => j.id === current.id);
      if (!latest) return current;
      if (
        latest.status === current.status
        && latest.completedAt === current.completedAt
        && latest.summaryMessage === current.summaryMessage
      ) {
        return current;
      }
      return latest;
    });
  }, [recentJobs]);

  // Dedicated feature sync states
  const [syncingPrs, setSyncingPrs] = useState(false);
  const [syncingLfs, setSyncingLfs] = useState(false);
  const [syncingReleases, setSyncingReleases] = useState(false);
  const [actionNotice, setActionNotice] = useState<string | null>(null);
  const [conflicts, setConflicts] = useState<SyncConflictRecord[]>([]);
  const [conflictBusyId, setConflictBusyId] = useState<number | null>(null);
  const [fullSyncChoiceOpen, setFullSyncChoiceOpen] = useState(false);

  const pairJobs = recentJobs.filter(j => j.mappingId === mapping.id);
  const latestJob = pairJobs[0] || null;
  const diffFetchedAtRef = useRef(0);
  const appliedJobKeyRef = useRef<string | null>(null);
  const snapshotRefreshKeyRef = useRef<string | null>(null);

  const applyDiffReport = (data: SyncDiffReport) => {
    diffFetchedAtRef.current = Date.now();
    appliedJobKeyRef.current = null;
    setDiffError(data.inspectionError || null);
    setDiffReport(normalizeDiffReport(data, mapping.syncDirection));
  };

  const applyBranchPageReport = (data: SyncDiffReport) => {
    setDiffReport((prev) =>
      prev
        ? {
            ...prev,
            branches: data.branches,
            branchesTruncated: data.branchesTruncated,
            branchesPageOffset: data.branchesPageOffset,
            branchesPageSize: data.branchesPageSize,
            branchesFilteredCount: data.branchesFilteredCount,
          }
        : normalizeDiffReport(data, mapping.syncDirection)
    );
  };

  const fetchDiffReport = async (full = false, silent = false) => {
    if (full) {
      if (fullDiffLoading) {
        setShowDiffModal(true);
        return;
      }
      setShowDiffModal(true);
      setFullDiffLoading(true);
    } else if (!silent) {
      setInitialDiffLoading(true);
    }
    setDiffError(null);
    try {
      applyDiffReport(await getSyncDiffReport(mapping.id, full
        ? { refresh: true, metadata: true }
        : undefined));
    } catch (e: unknown) {
      const message = e instanceof Error ? e.message : 'Failed to load sync diff report';
      setDiffError(message);
      console.error('Failed to load sync diff report:', e);
    } finally {
      if (full) {
        setFullDiffLoading(false);
        onDiffProgressClear?.();
      } else if (!silent) {
        setInitialDiffLoading(false);
      }
    }
  };

  const handleRefreshDiffClick = () => {
    setShowDiffModal(true);
    if (!fullDiffLoading) {
      void fetchDiffReport(true);
    }
  };

  useEffect(() => {
    let cancelled = false;
    setInitialDiffLoading(true);
    setDiffError(null);
    getSyncDiffReport(mapping.id)
      .then((data) => {
        if (!cancelled) {
          applyDiffReport(data);
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) {
          const message = e instanceof Error ? e.message : 'Failed to load sync diff report';
          setDiffError(message);
          console.error('Failed to load sync diff report:', e);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setInitialDiffLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [mapping.id]);

  const fetchConflicts = async () => {
    try {
      setConflicts(await getMappingConflicts(mapping.id));
    } catch (e) {
      console.error('Failed to load conflicts:', e);
    }
  };

  useEffect(() => {
    fetchConflicts();
  }, [mapping.id, latestJob?.status, latestJob?.id]);

  useEffect(() => {
    if (!latestJob || !diffReport) {
      return;
    }
    if (latestJob.status === 'SUCCESS' || latestJob.status === 'CONFLICT_ISOLATED') {
      const completedAt = latestJob.completedAt ? new Date(latestJob.completedAt).getTime() : 0;
      const inspectNewer = completedAt > 0 && diffFetchedAtRef.current > completedAt;
      if (inspectNewer) {
        const handled = (diffReport.pullRequests || []).filter(isPrHandled).length;
        if (handled > 0 || !(latestJob.prsSyncedCount && latestJob.prsSyncedCount > 0)) {
          return;
        }
      }
      const key = `${latestJob.id}:${latestJob.completedAt || ''}`;
      if (appliedJobKeyRef.current === key) {
        return;
      }
      appliedJobKeyRef.current = key;
      setDiffReport((prev) => (prev ? applySuccessfulMirrorJob(prev, latestJob, mapping.syncDirection) : prev));
      return;
    }
    if ((latestJob.lfsObjectsCount ?? 0) > 0 || (latestJob.branchesCount ?? 0) > 0) {
      const key = `partial:${latestJob.id}:${latestJob.lfsObjectsCount ?? 0}:${latestJob.completedAt || latestJob.status}`;
      if (appliedJobKeyRef.current === key) {
        return;
      }
      appliedJobKeyRef.current = key;
      setDiffReport((prev) => (prev ? applyJobMirrorMetrics(prev, latestJob) : prev));
    }
  }, [latestJob?.id, latestJob?.status, latestJob?.completedAt, latestJob?.prsSyncedCount, latestJob?.lfsObjectsCount, latestJob?.branchesCount, diffReport, mapping.syncDirection]);

  useEffect(() => {
    if (!latestJob) {
      return;
    }
    const key = `${latestJob.id}:${latestJob.status}:${latestJob.branchesCount ?? 0}:${latestJob.tagsCount ?? 0}:${latestJob.lfsObjectsCount ?? 0}:${latestJob.prsSyncedCount ?? 0}:${latestJob.completedAt || ''}`;
    if (snapshotRefreshKeyRef.current === key) {
      return;
    }
    snapshotRefreshKeyRef.current = key;
    if (latestJob.status === 'QUEUED') {
      return;
    }
    void fetchDiffReport(false, true);
  }, [latestJob?.id, latestJob?.status, latestJob?.completedAt, latestJob?.branchesCount, latestJob?.tagsCount, latestJob?.lfsObjectsCount, latestJob?.prsSyncedCount, mapping.id]);

  const handleSyncPrs = async () => {
    setSyncingPrs(true);
    setActionNotice(null);
    try {
      const res = await syncPullRequests(mapping.id);
      setActionNotice(res.message || `Synchronized ${res.syncedCount} pull request(s)`);
      await fetchDiffReport(false, true);
      onRefreshJobs?.();
    } catch (e: any) {
      setActionNotice(`PR sync error: ${e.response?.data?.error || e.message}`);
    } finally {
      setSyncingPrs(false);
    }
  };

  const handleSyncLfs = async () => {
    setSyncingLfs(true);
    setActionNotice(null);
    try {
      const res = await syncLfsObjects(mapping.id);
      setActionNotice(res.message || `Synchronized ${res.syncedCount} Git LFS blob(s)`);
      await fetchDiffReport(false, true);
      onRefreshJobs?.();
    } catch (e: any) {
      setActionNotice(`LFS sync error: ${e.response?.data?.error || e.message}`);
    } finally {
      setSyncingLfs(false);
    }
  };

  const handleSyncReleases = async () => {
    setSyncingReleases(true);
    setActionNotice(null);
    try {
      const res = await syncReleases(mapping.id);
      setActionNotice(res.message || `Synchronized ${res.syncedCount} release(s)`);
      await fetchDiffReport(false, true);
      onRefreshJobs?.();
    } catch (e: any) {
      setActionNotice(`Releases sync error: ${e.response?.data?.error || e.message}`);
    } finally {
      setSyncingReleases(false);
    }
  };

  const handleTriggerSync = async (branch?: string, overwriteFromSource = false, startFresh = false) => {
    if (branch) {
      setTriggeringBranch(branch);
    } else {
      setTriggering(true);
    }
    try {
      await onTriggerSync(mapping.id, branch || '*', overwriteFromSource, startFresh);
    } finally {
      setConfirmOverwrite(null);
      setFullSyncChoiceOpen(false);
      setTimeout(() => {
        setTriggering(false);
        setTriggeringBranch(null);
      }, 1200);
    }
  };

  const handleFullMirrorSync = () => {
    // Always let the operator choose smart sync vs force re-fetch.
    setFullSyncChoiceOpen(true);
  };

  const handleTestAccess = async () => {
    setTestingAccess(true);
    try {
      const res = await testRepoConnection({
        repoUrl: repoAUrl,
        requiredAccess: 'READ',
        credentialId: mapping.sourceCredentialId,
      });
      setCheckReport(res);
    } catch (e: any) {
      setCheckReport({
        valid: false,
        repoFullName: repoAUrl,
        isPrivate: false,
        httpStatusCode: 500,
        message: e.message || 'Verification call failed',
        passedChecks: [],
        warnings: [],
        errors: [e.message || 'Connection error'],
      });
    } finally {
      setTestingAccess(false);
    }
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    try {
      await onUpdate({
        name,
        repoAUrl,
        repoBUrl,
        branchPattern,
        syncDirection,
        trunkConflictPolicy,
        storageTier,
        active,
      });
    } finally {
      setSaving(false);
    }
  };

  const openConflicts = conflicts.filter((c) => c.status === 'OPEN' || c.status === 'PR_OPENED');

  const handleResolveConflict = async (conflictId: number) => {
    setConflictBusyId(conflictId);
    try {
      await resolveMappingConflict(mapping.id, conflictId);
      await fetchConflicts();
    } catch (e: any) {
      setActionNotice(`Resolve conflict error: ${e.response?.data?.error || e.message}`);
    } finally {
      setConflictBusyId(null);
    }
  };

  const handleOpenConflictPr = async (conflictId: number) => {
    setConflictBusyId(conflictId);
    try {
      const row = await openConflictPr(mapping.id, conflictId);
      await fetchConflicts();
      if (row.conflictPrNumber) {
        setActionNotice(`Opened conflict PR #${row.conflictPrNumber}`);
      }
    } catch (e: any) {
      setActionNotice(`Open conflict PR error: ${e.response?.data?.error || e.message}`);
    } finally {
      setConflictBusyId(null);
    }
  };

  const getBranchStatusBadge = (status: string, forkPrHead?: boolean) => {
    switch (status) {
      case 'IN_SYNC':
        return (
          <span className="inline-flex items-center space-x-1 bg-emerald-50 text-emerald-700 border border-emerald-200 px-2 py-0.5 rounded-full text-[11px] font-medium">
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" />
            <span>In Sync</span>
          </span>
        );
      case 'AHEAD':
        return (
          <span className="inline-flex items-center space-x-1 bg-amber-50 text-amber-700 border border-amber-200 px-2 py-0.5 rounded-full text-[11px] font-medium">
            <span className="w-1.5 h-1.5 rounded-full bg-amber-500" />
            <span>Pending to Target</span>
          </span>
        );
      case 'BEHIND':
        return (
          <span className="inline-flex items-center space-x-1 bg-blue-50 text-blue-700 border border-blue-200 px-2 py-0.5 rounded-full text-[11px] font-medium">
            <span className="w-1.5 h-1.5 rounded-full bg-blue-500" />
            <span>Pending from Target</span>
          </span>
        );
      case 'TARGET_MISSING':
        return (
          <span className="inline-flex items-center space-x-1 bg-purple-50 text-purple-700 border border-purple-200 px-2 py-0.5 rounded-full text-[11px] font-medium">
            <span className="w-1.5 h-1.5 rounded-full bg-purple-500" />
            <span>Pending Creation on Target</span>
          </span>
        );
      case 'SOURCE_MISSING':
        return forkPrHead ? (
          <span className="inline-flex items-center space-x-1 bg-violet-50 text-violet-700 border border-violet-200 px-2 py-0.5 rounded-full text-[11px] font-medium">
            <span className="w-1.5 h-1.5 rounded-full bg-violet-500" />
            <span>Fork PR head</span>
          </span>
        ) : (
          <span className="inline-flex items-center space-x-1 bg-zinc-100 text-zinc-600 border border-zinc-200 px-2 py-0.5 rounded-full text-[11px] font-medium">
            <span className="w-1.5 h-1.5 rounded-full bg-zinc-400" />
            <span>Destination only</span>
          </span>
        );
      case 'DIVERGED':
        return (
          <span className="inline-flex items-center space-x-1 bg-rose-50 text-rose-700 border border-rose-200 px-2 py-0.5 rounded-full text-[11px] font-medium">
            <span className="w-1.5 h-1.5 rounded-full bg-rose-500" />
            <span>Diverged</span>
          </span>
        );
      default:
        return (
          <span className="inline-flex items-center space-x-1 bg-zinc-100 text-zinc-700 border border-zinc-200 px-2 py-0.5 rounded-full text-[11px] font-medium">
            <span>{status}</span>
          </span>
        );
    }
  };

  const metricsReport = useMemo(
    () => applyLiveDiffProgress(diffReport, diffProgress, fullDiffLoading) ?? diffReport,
    [diffReport, diffProgress, fullDiffLoading]
  );

  const prsSummary = prsAccountedFromReport(metricsReport);
  const destOnlyCount = metricsReport?.destOnlyBranchesCount ?? 0;
  const pendingBranchCount = metricsReport?.pendingBranchesCount ?? 0;
  const divergedBranchCount = metricsReport?.divergedBranchesCount ?? 0;
  const sourceBranchCount = metricsReport?.sourceBranchesCount
    || Math.max(0, (metricsReport?.inSyncBranchesCount ?? 0) + pendingBranchCount + divergedBranchCount);
  const destBranchCount = metricsReport?.destBranchesCount
    || Math.max(0, sourceBranchCount + destOnlyCount);
  const inSyncBranchCount = metricsReport?.inSyncBranchesCount ?? 0;
  const matrixBadge = (() => {
    if (!metricsReport) return null;
    if (metricsReport.overallStatus === 'IN_SYNC') {
      return {
        label: destOnlyCount > 0 ? `In Sync · ${destOnlyCount} dest-only` : 'All Refs In Sync',
        className: 'bg-emerald-50 text-emerald-700 border-emerald-200',
      };
    }
    if (metricsReport.overallStatus === 'DIVERGED' || divergedBranchCount > 0) {
      return {
        label: `${divergedBranchCount || 1} diverged`,
        className: 'bg-rose-50 text-rose-700 border-rose-200',
      };
    }
    return {
      label: `${pendingBranchCount} ref${pendingBranchCount === 1 ? '' : 's'} pending`,
      className: 'bg-amber-50 text-amber-700 border-amber-200',
    };
  })();
  const lfsCount = metricsReport?.lfs?.totalDiscovered ?? 0;
  const tagsCount = metricsReport?.tags?.targetTagsCount ?? 0;
  const releasesCount = metricsReport?.releases?.targetReleasesCount ?? 0;

  const filteredTags = (diffReport?.tagItems || []).filter(t =>
    t.tagName.toLowerCase().includes(tagSearchQuery.toLowerCase()) ||
    t.refName.toLowerCase().includes(tagSearchQuery.toLowerCase())
  );

  const collisionA = useMemo(() => findRepoCollision(mapping.repoAUrl, allMappings, mapping.id), [mapping.repoAUrl, allMappings, mapping.id]);
  const collisionB = useMemo(() => findRepoCollision(mapping.repoBUrl, allMappings, mapping.id), [mapping.repoBUrl, allMappings, mapping.id]);

  return (
    <div className="space-y-6">
      {/* Top Header & Breadcrumb */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-3">
            <button
              onClick={onBack}
              className="inline-flex items-center space-x-1 text-zinc-500 hover:text-zinc-900 text-xs font-medium bg-white hover:bg-zinc-100 border border-zinc-200 px-2.5 py-1.5 rounded-lg shadow-xs transition-colors"
            >
              <ArrowLeft className="w-3.5 h-3.5" />
              <span>Back to Repos</span>
            </button>

            <div>
              <h2 className="text-base font-bold text-zinc-900 flex items-center space-x-2">
                <span>{mapping.name}</span>
                <span className={`px-2 py-0.5 rounded-full text-[10px] font-semibold border ${
                  mapping.active ? 'bg-emerald-50 text-emerald-700 border-emerald-200' : 'bg-zinc-100 text-zinc-600 border-zinc-200'
                }`}>
                  {mapping.active ? 'Active Replication' : 'Paused'}
                </span>
              </h2>
              <div className="flex items-center space-x-2 text-[11px] text-zinc-500 font-mono mt-0.5">
                <span className="truncate max-w-[240px]" title={mapping.repoAUrl}>{mapping.repoAUrl}</span>
                <span>⇄</span>
                <span className="truncate max-w-[240px]" title={mapping.repoBUrl}>{mapping.repoBUrl}</span>
              </div>
            </div>
          </div>

          <div className="flex items-center space-x-2">
            <button
              onClick={() => setShowRunsModal(true)}
              className="inline-flex items-center space-x-1.5 bg-white hover:bg-zinc-50 border border-zinc-200 text-zinc-700 text-xs px-3 py-1.5 rounded-lg font-medium shadow-sm transition-colors"
              title="View all sync execution runs"
            >
              <Clock className="w-3.5 h-3.5 text-zinc-500" />
              <span>Sync Runs</span>
              {pairJobs.length > 0 && (
                <span className="px-1.5 py-0.2 text-[10px] bg-zinc-100 text-zinc-700 rounded-full font-mono font-semibold">
                  {pairJobs.length}
                </span>
              )}
            </button>

            <button
              onClick={handleRefreshDiffClick}
              className="inline-flex items-center space-x-1.5 bg-white hover:bg-zinc-50 border border-zinc-200 text-zinc-700 text-xs px-3 py-1.5 rounded-lg font-medium shadow-sm transition-colors"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${fullDiffLoading ? 'animate-spin text-indigo-600' : ''}`} />
              <span>{fullDiffLoading ? 'View Inspection' : 'Refresh Diff'}</span>
            </button>

            <button
              onClick={handleFullMirrorSync}
              disabled={triggering || fullDiffLoading}
              className="inline-flex items-center space-x-1.5 bg-zinc-900 hover:bg-zinc-800 text-white text-xs px-3.5 py-1.5 rounded-lg font-medium shadow-sm transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              title={fullDiffLoading ? 'Live inspection in progress; actions disabled to prevent race conditions' : 'Sync all branches and tags'}
            >
              <Play className="w-3 h-3 fill-current" />
              <span>{triggering ? 'Triggering...' : fullDiffLoading ? 'Inspecting...' : 'Sync Full Mirror'}</span>
            </button>
          </div>
        </div>

        {/* Repository Collision Alert Banner */}
        {(collisionA || collisionB) && mapping.active && (
          <div className="p-3.5 rounded-xl bg-amber-50 border border-amber-200 text-amber-900 space-y-1 shadow-xs">
            <div className="flex items-center space-x-2 font-bold text-xs text-amber-950">
              <AlertTriangle className="w-4 h-4 text-amber-600 shrink-0" />
              <span>Active Repository Assignment Conflict</span>
            </div>
            {collisionA && (
              <p className="text-xs text-amber-800">
                Source repository <span className="font-mono font-semibold">{mapping.repoAUrl}</span> is also actively assigned to pair <span className="font-semibold font-mono">'{collisionA.pairName}'</span>.
              </p>
            )}
            {collisionB && (
              <p className="text-xs text-amber-800">
                Destination repository <span className="font-mono font-semibold">{mapping.repoBUrl}</span> is also actively assigned to pair <span className="font-semibold font-mono">'{collisionB.pairName}'</span>.
              </p>
            )}
            <p className="text-[11px] text-amber-700/90">
              Multiple active mirror pairs sharing the same target repository will clobber each other's commit history, reject non-fast-forward pushes, and delete non-matching branches. Please pause or remove the duplicate pair.
            </p>
          </div>
        )}

        {/* Action / Notification Banner */}
        {actionNotice && (
          <div className="rounded-xl border border-blue-200 bg-blue-50/90 p-3.5 text-xs flex items-center justify-between gap-3 shadow-xs transition-all">
            <div className="flex items-center space-x-2.5">
              <Sparkles className="w-4 h-4 text-blue-600 shrink-0" />
              <span className="font-medium text-blue-950">{actionNotice}</span>
            </div>
            <button
              onClick={() => setActionNotice(null)}
              className="text-blue-700 hover:text-blue-900 text-xs font-semibold"
            >
              Dismiss
            </button>
          </div>
        )}

        {/* Real-Time Sync Execution Alert Banner */}
        {latestJob && (latestJob.status === 'FAILED' || latestJob.status === 'DEAD_LETTERED') && (
          <div className="rounded-xl border border-rose-200 bg-rose-50/90 p-4 text-xs flex flex-col md:flex-row md:items-center justify-between gap-3 shadow-xs">
            <div className="flex items-start space-x-3">
              <div className="w-7 h-7 rounded-lg bg-rose-100 border border-rose-200 flex items-center justify-center text-rose-600 shrink-0 mt-0.5">
                <AlertCircle className="w-4 h-4" />
              </div>
              <div className="space-y-1">
                <div className="flex items-center space-x-2">
                  <span className="font-semibold text-rose-950">Mirror Sync Failed ({latestJob.status})</span>
                  <span className="text-[10px] font-mono text-rose-700 bg-rose-200/60 px-1.5 py-0.5 rounded font-medium">
                    Attempt {latestJob.attemptCount}/{latestJob.maxAttempts}
                  </span>
                </div>
                <p className="text-rose-800 font-mono text-[11px] leading-relaxed break-all">
                  {latestJob.errorMessage || 'An error occurred during synchronization.'}
                </p>
                {(latestJob.errorMessage || '').includes('AUTH_INSTALLATION_MISMATCH') && (
                  <p className="text-rose-900 text-[11px]">
                    This pair&apos;s credential cannot access the repository (transfer, uninstall, or wrong org). Rebind source or destination — Hub will not auto-switch installs.
                  </p>
                )}
              </div>
            </div>
            <div className="flex items-center space-x-2 shrink-0 self-end md:self-center">
              {(latestJob.errorMessage || '').includes('AUTH_INSTALLATION_MISMATCH') && (
                <button
                  onClick={() => setRebindOpen(true)}
                  className="inline-flex items-center space-x-1 px-2.5 py-1.5 bg-white hover:bg-rose-100/50 border border-rose-200 text-rose-800 rounded-lg text-xs font-medium shadow-xs transition-colors"
                >
                  <KeyRound className="w-3.5 h-3.5" />
                  <span>Rebind credential</span>
                </button>
              )}
              <button
                onClick={() => setSelectedJobForLogs(latestJob)}
                className="inline-flex items-center space-x-1 px-2.5 py-1.5 bg-white hover:bg-rose-100/50 border border-rose-200 text-rose-800 rounded-lg text-xs font-medium shadow-xs transition-colors"
              >
                <FileText className="w-3.5 h-3.5" />
                <span>View Execution Logs</span>
              </button>
              <button
                onClick={handleFullMirrorSync}
                disabled={triggering}
                className="inline-flex items-center space-x-1 px-2.5 py-1.5 bg-rose-700 hover:bg-rose-800 text-white rounded-lg text-xs font-medium shadow-xs transition-colors disabled:opacity-50"
              >
                <RefreshCw className={`w-3.5 h-3.5 ${triggering ? 'animate-spin' : ''}`} />
                <span>Retry Sync</span>
              </button>
            </div>
          </div>
        )}

        {latestJob && latestJob.status === 'QUEUED' && (
          <div className="rounded-xl border border-amber-200 bg-amber-50/90 p-3.5 text-xs flex items-center justify-between gap-3 shadow-xs">
            <div className="flex items-center space-x-2.5">
              <Clock className="w-4 h-4 text-amber-600 shrink-0" />
              <div>
                <span className="font-semibold text-amber-950">Waiting in queue: </span>
                <span className="text-amber-800 font-mono text-[11px]">{latestJob.branch || 'all branches'}</span>
                <span className="text-amber-700 ml-1">Behind other jobs until the worker is free.</span>
              </div>
            </div>
            <div className="flex items-center space-x-2">
              <button
                onClick={() => setSelectedJobForLogs(latestJob)}
                className="inline-flex items-center space-x-1 px-2.5 py-1 bg-white hover:bg-amber-100/50 border border-amber-200 text-amber-900 rounded-lg text-xs font-medium"
              >
                <FileText className="w-3.5 h-3.5" />
                <span>Details</span>
              </button>
              <button
                onClick={async () => {
                  await pauseJob(latestJob.id);
                  onRefreshJobs?.();
                }}
                className="inline-flex items-center space-x-1 px-2.5 py-1 bg-amber-600 hover:bg-amber-700 text-white rounded-lg text-xs font-medium"
              >
                Pause
              </button>
              <button
                onClick={async () => {
                  await cancelJob(latestJob.id);
                  onRefreshJobs?.();
                }}
                className="inline-flex items-center space-x-1 px-2.5 py-1 bg-rose-700 hover:bg-rose-800 text-white rounded-lg text-xs font-medium"
              >
                Cancel
              </button>
            </div>
          </div>
        )}

        {latestJob && latestJob.status === 'IN_PROGRESS' && (
          <div className="rounded-xl border border-blue-200 bg-blue-50/90 p-3.5 text-xs flex items-center justify-between gap-3 shadow-xs">
            <div className="flex items-center space-x-2.5">
              <RefreshCw className="w-4 h-4 text-blue-600 animate-spin shrink-0" />
              <div>
                <span className="font-semibold text-blue-950">Synchronization in Progress: </span>
                <span className="text-blue-800 font-mono text-[11px]">{latestJob.branch || 'all branches'} (Attempt {latestJob.attemptCount}/{latestJob.maxAttempts})</span>
              </div>
            </div>
            <div className="flex items-center space-x-2 shrink-0">
              <button
                onClick={() => setSelectedJobForLogs(latestJob)}
                className="inline-flex items-center space-x-1 px-2.5 py-1 bg-white hover:bg-blue-100/50 border border-blue-200 text-blue-800 rounded-lg text-xs font-medium"
              >
                <FileText className="w-3.5 h-3.5" />
                <span>View Logs</span>
              </button>
              <button
                onClick={async () => {
                  await pauseJob(latestJob.id);
                  onRefreshJobs?.();
                }}
                className="inline-flex items-center space-x-1 px-2.5 py-1 bg-amber-600 hover:bg-amber-700 text-white rounded-lg text-xs font-medium"
              >
                Pause
              </button>
              <button
                onClick={async () => {
                  await cancelJob(latestJob.id);
                  onRefreshJobs?.();
                }}
                className="inline-flex items-center space-x-1 px-2.5 py-1 bg-rose-700 hover:bg-rose-800 text-white rounded-lg text-xs font-medium"
              >
                Cancel
              </button>
            </div>
          </div>
        )}

        {/* Top-Level Navigation Tabs */}
        <div className="flex border-b border-zinc-200 overflow-x-auto space-x-2 pt-2">
          <button
            onClick={() => setCurrentTab('code')}
            className={`flex items-center space-x-2 px-4 py-2.5 text-xs font-medium border-b-2 transition-colors whitespace-nowrap ${
              currentTab === 'code'
                ? 'border-zinc-900 text-zinc-900 font-semibold'
                : 'border-transparent text-zinc-500 hover:text-zinc-700'
            }`}
          >
            <GitBranch className="w-4 h-4" />
            <span>Branches & Commits</span>
            {metricsReport && (
              <span className="px-1.5 py-0.2 text-[10px] bg-zinc-100 text-zinc-700 rounded-full font-mono">
                {metricsReport.totalBranchesCount}
              </span>
            )}
          </button>

          <button
            onClick={() => setCurrentTab('pull-requests')}
            className={`flex items-center space-x-2 px-4 py-2.5 text-xs font-medium border-b-2 transition-colors whitespace-nowrap ${
              currentTab === 'pull-requests'
                ? 'border-zinc-900 text-zinc-900 font-semibold'
                : 'border-transparent text-zinc-500 hover:text-zinc-700'
            }`}
          >
            <GitPullRequest className="w-4 h-4" />
            <span>Pull Requests Mirror</span>
            <span className="px-1.5 py-0.2 text-[10px] bg-emerald-100 text-emerald-800 rounded-full font-mono">
              {prsSummary.handled}/{prsSummary.total}
            </span>
          </button>

          <button
            onClick={() => setCurrentTab('metadata')}
            className={`flex items-center space-x-2 px-4 py-2.5 text-xs font-medium border-b-2 transition-colors whitespace-nowrap ${
              currentTab === 'metadata'
                ? 'border-zinc-900 text-zinc-900 font-semibold'
                : 'border-transparent text-zinc-500 hover:text-zinc-700'
            }`}
          >
            <Layers className="w-4 h-4 text-purple-600" />
            <span>Git Metadata, LFS & Releases</span>
            <span className="px-1.5 py-0.2 text-[10px] bg-purple-100 text-purple-800 rounded-full font-mono">
              {lfsCount + tagsCount + releasesCount}
            </span>
          </button>

          <button
            onClick={() => setCurrentTab('settings')}
            className={`flex items-center space-x-2 px-4 py-2.5 text-xs font-medium border-b-2 transition-colors whitespace-nowrap ${
              currentTab === 'settings'
                ? 'border-zinc-900 text-zinc-900 font-semibold'
                : 'border-transparent text-zinc-500 hover:text-zinc-700'
            }`}
          >
            <Settings className="w-4 h-4" />
            <span>Storage & Settings</span>
            <span className="px-1.5 py-0.2 text-[10px] bg-blue-100 text-blue-800 rounded-full font-mono">
              {storageTier}
            </span>
          </button>
        </div>
      </div>

      {/* 1. CODE & BRANCH DIFF MATRIX TAB */}
      {currentTab === 'code' && (
        <div className="space-y-6">
          {/* Top Summary Banner */}
          <div className="rounded-2xl border border-zinc-200/90 bg-white p-6 shadow-sm flex flex-col md:flex-row md:items-center justify-between gap-4">
            <div className="space-y-1">
              <div className="flex items-center space-x-2.5">
                <span className="text-sm font-semibold text-zinc-900">Branch & Git Ref Synchronization Matrix</span>
                {matrixBadge && (
                  <span className={`px-2 py-0.5 rounded-full text-[11px] font-medium border ${matrixBadge.className}`}>
                    {matrixBadge.label}
                  </span>
                )}
              </div>
              <p className="text-xs text-zinc-500">
                {pendingBranchCount > 0
                  ? `${pendingBranchCount} source→dest ref${pendingBranchCount === 1 ? '' : 's'} still have a commit delta. Destination-only branches are not pending.`
                  : 'Live inspection comparing commits, refs, and binary blobs between Source and Target remotes.'}
              </p>
            </div>

            <div className="flex items-center space-x-2 shrink-0">
              <span className="text-xs text-zinc-500 font-mono">
                Storage: <strong className="text-zinc-800">{storageTier}</strong>
              </span>
            </div>
          </div>

          {diffError && !fullDiffLoading && (
            <div className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800 flex items-start justify-between gap-3">
              <div>
                <div className="font-semibold">Inspection issue</div>
                <div className="text-xs mt-1 font-mono break-all">{diffError}</div>
              </div>
              <button
                type="button"
                onClick={handleRefreshDiffClick}
                className="shrink-0 text-xs font-semibold px-2.5 py-1 rounded-lg bg-rose-700 text-white hover:bg-rose-800"
              >
                Retry
              </button>
            </div>
          )}

          {fullDiffLoading && !showDiffModal && (
            <div className="rounded-xl border border-indigo-200 bg-indigo-50 px-4 py-3 flex items-center justify-between gap-3">
              <div className="flex items-center gap-2.5 min-w-0">
                <RefreshCw className="w-4 h-4 text-indigo-600 animate-spin shrink-0" />
                <div className="min-w-0">
                  <div className="text-xs font-semibold text-indigo-950">Diff inspection running in background</div>
                  <div className="text-[11px] text-indigo-800/80 truncate">
                    {diffProgress?.message || 'Comparing source and destination...'}
                  </div>
                  {diffProgress?.counts && (
                    <div className="text-[11px] text-indigo-900/80 font-mono mt-0.5">
                      {diffProgress.counts.inSync ?? 0} in sync · {diffProgress.counts.pending ?? 0} pending ·{' '}
                      {diffProgress.counts.diverged ?? 0} diverged · {diffProgress.counts.destOnly ?? 0} dest-only
                    </div>
                  )}
                </div>
              </div>
              <button
                type="button"
                onClick={() => setShowDiffModal(true)}
                className="shrink-0 text-xs font-semibold px-2.5 py-1 rounded-lg bg-indigo-600 text-white hover:bg-indigo-700"
              >
                View progress
              </button>
            </div>
          )}

          {/* Quick Metrics Cards */}
          {initialDiffLoading && !diffReport ? (
            <div className="grid grid-cols-1 sm:grid-cols-4 gap-3 animate-pulse">
              {[1, 2, 3, 4].map((i) => (
                <div key={i} className="rounded-xl border border-zinc-200/90 bg-white p-4 shadow-sm space-y-2">
                  <div className="h-3 bg-zinc-100 rounded w-1/2" />
                  <div className="h-6 bg-zinc-200/80 rounded w-1/3" />
                  <div className="h-2.5 bg-zinc-100 rounded w-3/4" />
                </div>
              ))}
            </div>
          ) : metricsReport ? (
            <div className="grid grid-cols-1 sm:grid-cols-4 gap-3">
              <div className="rounded-xl border border-zinc-200/90 bg-white p-4 shadow-sm">
                <div className="flex items-center justify-between text-zinc-500 text-xs mb-1">
                  <span className="font-medium flex items-center space-x-1.5">
                    <GitBranch className="w-3.5 h-3.5 text-zinc-600" />
                    <span>Branches & Heads</span>
                  </span>
                  <span className="text-[11px] font-semibold text-emerald-600">
                    {inSyncBranchCount.toLocaleString()} in sync
                  </span>
                </div>
                <div className="text-lg font-bold text-zinc-900">
                  {sourceBranchCount.toLocaleString()}
                  <span className="text-xs font-normal text-zinc-400"> source</span>
                  <span className="text-zinc-300 mx-1.5">/</span>
                  {destBranchCount.toLocaleString()}
                  <span className="text-xs font-normal text-zinc-400"> dest</span>
                </div>
                <p className="text-[11px] text-zinc-500 mt-1">
                  {pendingBranchCount > 0 ? `${pendingBranchCount.toLocaleString()} pending` : 'No pending refs'}
                  {divergedBranchCount > 0 ? ` · ${divergedBranchCount.toLocaleString()} diverged` : ''}
                  {destOnlyCount > 0 ? ` · ${destOnlyCount.toLocaleString()} dest-only` : ''}
                  {metricsReport.metadataDeferred ? ' · SHA-only until Refresh Diff' : ''}
                  {fullDiffLoading && !metricsReport.metadataDeferred ? ' · updating live' : ''}
                </p>
              </div>

              <div className="rounded-xl border border-zinc-200/90 bg-white p-4 shadow-sm">
                <div className="flex items-center justify-between text-zinc-500 text-xs mb-1">
                  <span className="font-medium flex items-center space-x-1.5">
                    <GitPullRequest className="w-3.5 h-3.5 text-emerald-600" />
                    <span>Pull Requests</span>
                  </span>
                  <span className="text-[11px] font-semibold text-emerald-600">
                    {prsSummary.handled.toLocaleString()} in sync
                  </span>
                </div>
                <div className="text-lg font-bold text-zinc-900">
                  {prsSummary.total.toLocaleString()}
                  <span className="text-xs font-normal text-zinc-400"> source</span>
                  <span className="text-zinc-300 mx-1.5">/</span>
                  {prsSummary.dest.toLocaleString()}
                  <span className="text-xs font-normal text-zinc-400"> dest</span>
                </div>
                <p className="text-[11px] text-zinc-500 mt-1">
                  {prsSummary.pending > 0
                    ? `${prsSummary.pending.toLocaleString()} still pending metadata mirror`
                    : 'Cross-repo metadata & comments'}
                </p>
              </div>

              <div className="rounded-xl border border-zinc-200/90 bg-white p-4 shadow-sm">
                <div className="flex items-center justify-between text-zinc-500 text-xs mb-1">
                  <span className="font-medium flex items-center space-x-1.5">
                    <Layers className="w-3.5 h-3.5 text-purple-600" />
                    <span>Git LFS Blobs</span>
                  </span>
                  <span className="text-[11px] font-semibold text-emerald-600">
                    {(metricsReport.lfs?.syncedCount ?? 0).toLocaleString()} in sync
                  </span>
                </div>
                <div className="text-lg font-bold text-zinc-900">
                  {(metricsReport.lfs?.totalDiscovered ?? 0).toLocaleString()}
                  <span className="text-xs font-normal text-zinc-400"> source</span>
                  <span className="text-zinc-300 mx-1.5">/</span>
                  {(metricsReport.lfs?.syncedCount ?? 0).toLocaleString()}
                  <span className="text-xs font-normal text-zinc-400"> dest</span>
                </div>
                <p className="text-[11px] text-zinc-500 mt-1">
                  {metricsReport.lfs?.pendingCount
                    ? `${metricsReport.lfs.pendingCount.toLocaleString()} still pending batch transfer`
                    : 'Batch API object transfer'}
                </p>
              </div>

              <div className="rounded-xl border border-zinc-200/90 bg-white p-4 shadow-sm">
                <div className="flex items-center justify-between text-zinc-500 text-xs mb-1">
                  <span className="font-medium flex items-center space-x-1.5">
                    <Tag className="w-3.5 h-3.5 text-blue-600" />
                    <span>Release Tags</span>
                  </span>
                  <span className="text-[11px] font-semibold text-emerald-600">
                    {Math.min(metricsReport.tags?.sourceTagsCount ?? 0, metricsReport.tags?.targetTagsCount ?? 0).toLocaleString()} in sync
                  </span>
                </div>
                <div className="text-lg font-bold text-zinc-900">
                  {(metricsReport.tags?.sourceTagsCount ?? 0).toLocaleString()}
                  <span className="text-xs font-normal text-zinc-400"> source</span>
                  <span className="text-zinc-300 mx-1.5">/</span>
                  {(metricsReport.tags?.targetTagsCount ?? 0).toLocaleString()}
                  <span className="text-xs font-normal text-zinc-400"> dest</span>
                </div>
                <p className="text-[11px] text-zinc-500 mt-1">Tags & Notes refspecs</p>
              </div>
            </div>
          ) : null}

          {openConflicts.length > 0 && (
            <div className="rounded-2xl border border-amber-200 bg-amber-50/60 shadow-sm overflow-hidden">
              <div className="px-6 py-3.5 border-b border-amber-100 flex items-center justify-between">
                <h4 className="text-xs font-semibold text-amber-950 flex items-center space-x-2">
                  <AlertTriangle className="w-3.5 h-3.5 text-amber-700" />
                  <span>Open conflicts ({openConflicts.length})</span>
                </h4>
                <span className="text-[11px] text-amber-800">Trunk isolation, skipped tags, and PR metadata CAS misses</span>
              </div>
              <div className="divide-y divide-amber-100 bg-white/70">
                {openConflicts.map((c) => (
                  <div key={c.id} className="p-4 sm:px-6 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                    <div className="space-y-1 min-w-0">
                      <div className="flex flex-wrap items-center gap-2 text-xs font-medium text-zinc-900">
                        <span className="font-mono">{c.isolatedBranch || c.refName || 'metadata'}</span>
                        <span className="px-1.5 py-0.5 rounded border border-amber-200 bg-amber-50 text-[10px] text-amber-800">{c.kind}</span>
                        <span className="px-1.5 py-0.5 rounded border border-zinc-200 bg-zinc-50 text-[10px] text-zinc-600">{c.status}</span>
                      </div>
                      <p className="text-[11px] text-zinc-600">{c.message}</p>
                      {c.conflictPrNumber != null && (
                        <p className="text-[11px] text-zinc-500">Conflict PR #{c.conflictPrNumber}</p>
                      )}
                    </div>
                    <div className="flex items-center space-x-1.5 shrink-0">
                      {c.kind !== 'METADATA' && c.isolatedBranch && !c.conflictPrNumber && (
                        <button
                          type="button"
                          onClick={() => handleOpenConflictPr(c.id)}
                          disabled={conflictBusyId === c.id}
                          className="px-2.5 py-1.5 rounded-lg border border-zinc-200 text-zinc-700 hover:bg-zinc-100 text-xs font-medium disabled:opacity-50"
                        >
                          Open PR
                        </button>
                      )}
                      <button
                        type="button"
                        onClick={() => handleResolveConflict(c.id)}
                        disabled={conflictBusyId === c.id}
                        className="px-2.5 py-1.5 rounded-lg border border-amber-200 text-amber-800 hover:bg-amber-50 text-xs font-medium disabled:opacity-50"
                      >
                        Acknowledge
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Branch Diff Table */}
          <BranchComparisonTable
            mapping={mapping}
            diffReport={diffReport}
            summaryReport={metricsReport}
            initialLoading={initialDiffLoading}
            fullDiffLoading={fullDiffLoading}
            onReportUpdate={applyBranchPageReport}
            confirmOverwrite={confirmOverwrite}
            onConfirmOverwrite={setConfirmOverwrite}
            triggeringBranch={triggeringBranch}
            fullDiffLoadingBlocked={fullDiffLoading}
            onSyncBranch={(branch, overwrite) => void handleTriggerSync(branch, overwrite)}
            getBranchStatusBadge={getBranchStatusBadge}
          />

          {/* Remote Links Footer */}
          <div className="flex justify-center space-x-3 pt-2">
            <a
              href={mapping.repoAUrl.replace(/\.git$/, '')}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center space-x-1.5 bg-zinc-900 hover:bg-zinc-800 text-white text-xs px-3.5 py-1.5 rounded-lg font-medium transition-colors shadow-sm"
            >
              <span>Source Repository</span>
              <ExternalLink className="w-3.5 h-3.5" />
            </a>
            <a
              href={mapping.repoBUrl.replace(/\.git$/, '')}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center space-x-1.5 bg-white hover:bg-zinc-100 border border-zinc-200 text-zinc-800 text-xs px-3.5 py-1.5 rounded-lg font-medium transition-colors shadow-sm"
            >
              <span>Destination Mirror / Fork</span>
              <ExternalLink className="w-3.5 h-3.5" />
            </a>
          </div>
        </div>
      )}

      {/* 2. PULL REQUESTS REPLICATION TAB */}
      {currentTab === 'pull-requests' && (
        <div className="space-y-6">
          <div className="rounded-2xl border border-zinc-200/90 bg-white p-6 shadow-sm flex flex-col md:flex-row md:items-center justify-between gap-4">
            <div>
              <h3 className="text-sm font-semibold text-zinc-900">Cross-Repository Pull Request & Review Synchronization</h3>
              <p className="text-xs text-zinc-500 mt-0.5">
                Pull requests, discussion comments, approvals, and CI status checks are mirrored across both remotes for failover continuity.
              </p>
            </div>

            <div className="flex items-center space-x-2 shrink-0">
              <button
                onClick={handleSyncPrs}
                disabled={syncingPrs || fullDiffLoading}
                className="inline-flex items-center space-x-1.5 bg-emerald-700 hover:bg-emerald-800 text-white text-xs px-3.5 py-1.5 rounded-lg font-medium shadow-sm transition-colors disabled:opacity-50"
                title="Synchronize open pull requests metadata across source and target"
              >
                <RefreshCw className={`w-3.5 h-3.5 ${syncingPrs ? 'animate-spin' : ''}`} />
                <span>{syncingPrs ? 'Syncing PRs...' : 'Sync Pull Requests'}</span>
              </button>

              <a
                href={`${mapping.repoAUrl.replace(/\.git$/, '')}/pulls`}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center space-x-1.5 bg-white hover:bg-zinc-50 border border-zinc-200 text-zinc-700 text-xs px-3 py-1.5 rounded-lg font-medium shadow-sm transition-colors"
              >
                <span>Source PRs</span>
                <ExternalLink className="w-3.5 h-3.5" />
              </a>

              <a
                href={`${mapping.repoBUrl.replace(/\.git$/, '')}/pulls`}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center space-x-1.5 bg-zinc-900 hover:bg-zinc-800 text-white text-xs px-3.5 py-1.5 rounded-lg font-medium shadow-sm transition-colors"
              >
                <span>Target Mirrored PRs</span>
                <ExternalLink className="w-3.5 h-3.5" />
              </a>
            </div>
          </div>

          {/* PR List Table */}
          <div className="rounded-2xl border border-zinc-200/90 bg-white shadow-sm overflow-hidden">
            <div className="px-6 py-3.5 border-b border-zinc-100 flex items-center justify-between bg-zinc-50/50">
              <div>
                <h4 className="text-xs font-semibold text-zinc-900">Synchronized Pull Requests</h4>
                <p className="text-[10px] text-zinc-500 mt-0.5">
                  Code and PR metadata are mirrored; review threads, approvals, and labels stay on the source unless opened on the destination.
                </p>
              </div>
              <span className="text-[11px] text-zinc-400">
                {diffReport?.pullRequestsTruncated && (diffReport?.totalOpenPrsCount ?? 0) > (diffReport?.pullRequests?.length ?? 0)
                  ? `${diffReport.pullRequests.length} shown of ${diffReport.totalOpenPrsCount?.toLocaleString()} open`
                  : `${diffReport?.pullRequests?.length ?? 0} PR(s) tracked`}
              </span>
            </div>

            <div className="divide-y divide-zinc-100">
              {diffReport?.pullRequests && diffReport.pullRequests.length > 0 ? (
                diffReport.pullRequests.map((pr) => (
                  <div key={pr.sourcePrNumber} className="p-4 sm:px-6 hover:bg-zinc-50/70 transition-colors flex flex-col md:flex-row md:items-center justify-between gap-3">
                    <div className="space-y-1.5 min-w-0">
                      <div className="flex flex-wrap items-center gap-2">
                        <GitPullRequest className="w-4 h-4 text-emerald-600 shrink-0" />
                        <span className="text-xs font-semibold text-zinc-900 truncate max-w-md">
                          {pr.title}
                        </span>
                        <span className={`px-2 py-0.5 rounded-full text-[10px] font-medium border ${
                          pr.state === 'open' ? 'bg-emerald-50 text-emerald-700 border-emerald-200' : 'bg-zinc-100 text-zinc-600 border-zinc-200'
                        }`}>
                          {pr.state}
                        </span>
                        {pr.isFork && (
                          <span className="px-1.5 py-0.2 bg-purple-50 text-purple-700 border border-purple-200 rounded text-[10px] font-medium">
                            Fork PR
                          </span>
                        )}
                        {pr.isDraft && (
                          <span className="px-1.5 py-0.2 bg-amber-50 text-amber-800 border border-amber-200 rounded text-[10px] font-medium">
                            Draft
                          </span>
                        )}
                      </div>

                      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-zinc-500 font-mono">
                        {pr.authorLogin && (
                          <>
                            <span className="text-zinc-700">@{pr.authorLogin}</span>
                            <span>•</span>
                          </>
                        )}
                        <span>Source #{pr.sourcePrNumber}</span>
                        <span>➔</span>
                        <span>{pr.targetPrNumber ? `Target #${pr.targetPrNumber}` : 'Pending Target'}</span>
                        {pr.sourcePrUrl && (
                          <>
                            <span>•</span>
                            <a
                              href={pr.sourcePrUrl}
                              target="_blank"
                              rel="noopener noreferrer"
                              className="text-indigo-600 hover:text-indigo-800 underline"
                            >
                              View source PR
                            </a>
                          </>
                        )}
                        {pr.headBranch && (
                          <>
                            <span>•</span>
                            <span className="bg-zinc-100 px-1.5 py-0.5 rounded text-zinc-700">{pr.headBranch}</span>
                          </>
                        )}
                        {pr.baseBranch && (
                          <>
                            <span>into</span>
                            <span className="bg-zinc-100 px-1.5 py-0.5 rounded text-zinc-700">{pr.baseBranch}</span>
                          </>
                        )}
                      </div>

                      {prDiscussionSummary(pr) && (
                        <div className="text-[11px] text-zinc-500 italic">
                          {prDiscussionSummary(pr)}
                        </div>
                      )}

                      {pr.reason && (
                        <div className={`text-[11px] font-sans flex items-center space-x-1.5 ${
                          pr.syncStatus === 'SKIPPED'
                            ? 'text-zinc-500 italic'
                            : pr.syncStatus === 'MIRRORED'
                            ? 'text-emerald-700'
                            : pr.syncStatus === 'FAILED'
                            ? 'text-rose-600'
                            : 'text-amber-700'
                        }`}>
                          <span>• {pr.reason}</span>
                        </div>
                      )}
                    </div>

                    <div className="flex items-center space-x-2 shrink-0 self-end md:self-center">
                      {isPrMirrored(pr) ? (
                        <span className="inline-flex items-center space-x-1 text-emerald-700 bg-emerald-50 border border-emerald-200 px-2.5 py-1 rounded-lg text-xs font-medium shadow-2xs">
                          <Check className="w-3.5 h-3.5" />
                          <span>Mirrored</span>
                        </span>
                      ) : pr.syncStatus === 'SKIPPED' ? (
                        <span className="inline-flex items-center space-x-1 text-zinc-600 bg-zinc-100 border border-zinc-200 px-2.5 py-1 rounded-lg text-xs font-medium">
                          <span>Skipped</span>
                        </span>
                      ) : pr.syncStatus === 'FAILED' ? (
                        <span className="inline-flex items-center space-x-1 text-rose-700 bg-rose-50 border border-rose-200 px-2.5 py-1 rounded-lg text-xs font-medium">
                          <AlertCircle className="w-3.5 h-3.5" />
                          <span>Action Needed</span>
                        </span>
                      ) : (
                        <span className="inline-flex items-center space-x-1 text-amber-700 bg-amber-50 border border-amber-200 px-2.5 py-1 rounded-lg text-xs font-medium">
                          <Clock className="w-3.5 h-3.5" />
                          <span>Pending Sync</span>
                        </span>
                      )}
                    </div>
                  </div>
                ))
              ) : (
                <div className="py-12 text-center text-xs text-zinc-400">
                  No open Pull Requests discovered for this repository pair.
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* 3. METADATA, LFS, RELEASES & CI STATUSES TAB */}
      {currentTab === 'metadata' && (
        <div className="space-y-6">
          {/* Top Metadata Overview Banner */}
          <div className="rounded-2xl border border-zinc-200/90 bg-white p-6 shadow-sm flex flex-col md:flex-row md:items-center justify-between gap-4">
            <div>
              <div className="flex items-center space-x-2">
                <h3 className="text-sm font-semibold text-zinc-900">Git Metadata, Releases & Binary Explorer</h3>
                <span className="inline-flex items-center space-x-1 text-[10px] bg-emerald-50 text-emerald-700 border border-emerald-200 px-2 py-0.5 rounded-full font-medium">
                  <Check className="w-3 h-3" />
                  <span>Live JGit & SCM Introspection</span>
                </span>
              </div>
              <p className="text-xs text-zinc-500 mt-0.5">
                Inspect every itemized tag, release tarball/binary, Git LFS pointer blob, and commit check run.
              </p>
            </div>

            {/* Sub-Tabs for Metadata */}
            <div className="flex items-center space-x-1 bg-zinc-100/80 p-1 rounded-xl border border-zinc-200/60 shrink-0">
              <button
                onClick={() => setMetadataSubTab('tags')}
                className={`flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-all ${
                  metadataSubTab === 'tags'
                    ? 'bg-white text-zinc-900 shadow-xs font-semibold'
                    : 'text-zinc-600 hover:text-zinc-900'
                }`}
              >
                <Tag className="w-3.5 h-3.5 text-emerald-600" />
                <span>Tags & Notes</span>
                <span className="text-[10px] bg-zinc-200 text-zinc-700 px-1.5 py-0.2 rounded-full font-mono">
                  {diffReport?.tags?.targetTagsCount ?? 0}
                </span>
              </button>

              <button
                onClick={() => setMetadataSubTab('releases')}
                className={`flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-all ${
                  metadataSubTab === 'releases'
                    ? 'bg-white text-zinc-900 shadow-xs font-semibold'
                    : 'text-zinc-600 hover:text-zinc-900'
                }`}
              >
                <Package className="w-3.5 h-3.5 text-blue-600" />
                <span>Releases</span>
                <span className="text-[10px] bg-zinc-200 text-zinc-700 px-1.5 py-0.2 rounded-full font-mono">
                  {diffReport?.releases?.targetReleasesCount ?? 0}
                </span>
              </button>

              <button
                onClick={() => setMetadataSubTab('lfs')}
                className={`flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-all ${
                  metadataSubTab === 'lfs'
                    ? 'bg-white text-zinc-900 shadow-xs font-semibold'
                    : 'text-zinc-600 hover:text-zinc-900'
                }`}
              >
                <Layers className="w-3.5 h-3.5 text-purple-600" />
                <span>Git LFS</span>
                <span className="text-[10px] bg-zinc-200 text-zinc-700 px-1.5 py-0.2 rounded-full font-mono">
                  {diffReport?.lfs?.totalDiscovered ?? 0}
                </span>
              </button>

              <button
                onClick={() => setMetadataSubTab('ci')}
                className={`flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-all ${
                  metadataSubTab === 'ci'
                    ? 'bg-white text-zinc-900 shadow-xs font-semibold'
                    : 'text-zinc-600 hover:text-zinc-900'
                }`}
              >
                <Activity className="w-3.5 h-3.5 text-amber-600" />
                <span>CI Checks</span>
                <span className="text-[10px] bg-zinc-200 text-zinc-700 px-1.5 py-0.2 rounded-full font-mono">
                  {diffReport?.ciCheckRuns?.length ?? 1}
                </span>
              </button>
            </div>
          </div>

          {/* Sub-View 1: Tags & Git Notes Detail */}
          {metadataSubTab === 'tags' && (
            <div className="space-y-4">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 bg-white p-4 rounded-xl border border-zinc-200/90 shadow-sm">
                <div className="flex items-center space-x-2">
                  <div className="relative">
                    <Search className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-zinc-400" />
                    <input
                      type="text"
                      placeholder="Search tags & notes..."
                      value={tagSearchQuery}
                      onChange={(e) => setTagSearchQuery(e.target.value)}
                      className="bg-zinc-50 border border-zinc-200 rounded-lg pl-8 pr-3 py-1.5 text-xs text-zinc-900 focus:outline-none focus:border-zinc-400 w-64"
                    />
                  </div>
                  <span className="text-xs text-zinc-500">
                    Showing {filteredTags.length} of {diffReport?.tags?.targetTagsCount ?? 0} tag refs
                  </span>
                </div>

                <div className="text-[11px] text-zinc-500 font-mono flex items-center space-x-2">
                  <span>RefSpec:</span>
                  <span className="bg-zinc-100 text-zinc-700 px-2 py-0.5 rounded border border-zinc-200">+refs/tags/*:refs/tags/*</span>
                </div>
              </div>

              <div className="rounded-2xl border border-zinc-200/90 bg-white shadow-sm overflow-hidden">
                <div className="divide-y divide-zinc-100 max-h-[500px] overflow-y-auto">
                  {filteredTags.length > 0 ? (
                    filteredTags.map((tag) => (
                      <div key={tag.refName} className="p-4 sm:px-6 hover:bg-zinc-50/70 transition-colors flex items-center justify-between gap-4">
                        <div className="space-y-1">
                          <div className="flex items-center space-x-2">
                            <Tag className="w-4 h-4 text-emerald-600 shrink-0" />
                            <span className="text-xs font-semibold text-zinc-900 font-mono">
                              {tag.tagName}
                            </span>
                            <span className={`px-2 py-0.5 rounded-full text-[10px] font-medium border ${
                              tag.isAnnotated ? 'bg-purple-50 text-purple-700 border-purple-200' : 'bg-zinc-100 text-zinc-600 border-zinc-200'
                            }`}>
                              {tag.isAnnotated ? 'Annotated / GPG' : 'Lightweight'}
                            </span>
                          </div>

                          <div className="flex items-center space-x-3 text-[11px] text-zinc-500 font-mono">
                            <span>Ref: {tag.refName}</span>
                            <span>•</span>
                            <span>Target Commit: {tag.targetShortSha || '--'}</span>
                            {tag.taggerName && (
                              <>
                                <span>•</span>
                                <span>Tagger: {tag.taggerName}</span>
                              </>
                            )}
                          </div>

                          {tag.message && (
                            <p className="text-[11px] text-zinc-600 font-sans italic bg-zinc-50 p-1.5 rounded border border-zinc-100 mt-1 max-w-xl">
                              "{tag.message.trim()}"
                            </p>
                          )}
                        </div>

                        <div className="flex items-center space-x-2 shrink-0">
                          <span className="inline-flex items-center space-x-1 text-emerald-700 bg-emerald-50 border border-emerald-200 px-2 py-0.5 rounded text-[11px] font-medium">
                            <Check className="w-3 h-3" />
                            <span>Replicated</span>
                          </span>
                        </div>
                      </div>
                    ))
                  ) : (
                    <div className="py-12 text-center text-xs text-zinc-400">
                      {tagSearchQuery ? 'No tags match the search query.' : 'No release tags or annotated Git notes discovered in bare repository.'}
                    </div>
                  )}
                </div>
              </div>
            </div>
          )}

          {/* Sub-View 2: Releases & Binary Assets Detail */}
          {metadataSubTab === 'releases' && (
            <div className="space-y-4">
              <div className="rounded-2xl border border-zinc-200/90 bg-white shadow-sm overflow-hidden">
                <div className="px-6 py-3.5 border-b border-zinc-100 flex items-center justify-between bg-zinc-50/50">
                  <h4 className="text-xs font-semibold text-zinc-900">GitHub Releases & Attached Binaries</h4>
                  <div className="flex items-center space-x-3">
                    <span className="text-[11px] text-zinc-400">
                      {diffReport?.releaseItems?.length ?? 0} Release(s) Published
                    </span>
                    <button
                      onClick={handleSyncReleases}
                      disabled={syncingReleases || fullDiffLoading}
                      className="inline-flex items-center space-x-1 px-2.5 py-1 bg-blue-700 hover:bg-blue-800 text-white rounded-lg text-xs font-medium transition-colors disabled:opacity-50"
                      title="Replicate releases and downloadable binary attachments to target"
                    >
                      <RefreshCw className={`w-3 h-3 ${syncingReleases ? 'animate-spin' : ''}`} />
                      <span>{syncingReleases ? 'Syncing...' : 'Sync Releases & Assets'}</span>
                    </button>
                  </div>
                </div>

                <div className="divide-y divide-zinc-100">
                  {diffReport?.releaseItems && diffReport.releaseItems.length > 0 ? (
                    diffReport.releaseItems.map((rel) => (
                      <div key={rel.id} className="p-6 hover:bg-zinc-50/70 transition-colors space-y-3">
                        <div className="flex items-center justify-between">
                          <div className="flex items-center space-x-2">
                            <Package className="w-4 h-4 text-blue-600 shrink-0" />
                            <span className="text-xs font-bold text-zinc-900">{rel.name || rel.tagName}</span>
                            <span className="px-2 py-0.5 bg-blue-50 text-blue-700 border border-blue-200 rounded text-[10px] font-mono font-semibold">
                              {rel.tagName}
                            </span>
                            {rel.isPrerelease && (
                              <span className="px-2 py-0.5 bg-amber-50 text-amber-700 border border-amber-200 rounded text-[10px] font-medium">
                                Pre-release
                              </span>
                            )}
                          </div>

                          <div className="flex items-center space-x-3 text-xs">
                            <span className="text-zinc-400 text-[11px] font-mono">Published {rel.publishedAt ? new Date(rel.publishedAt).toLocaleDateString() : ''}</span>
                            {rel.htmlUrl && (
                              <a
                                href={rel.htmlUrl}
                                target="_blank"
                                rel="noreferrer"
                                className="text-zinc-600 hover:text-zinc-900 font-medium inline-flex items-center space-x-1"
                              >
                                <span>View on GitHub</span>
                                <ExternalLink className="w-3 h-3" />
                              </a>
                            )}
                          </div>
                        </div>

                        {rel.body && (
                          <div className="p-3 bg-zinc-50 rounded-lg text-xs text-zinc-700 whitespace-pre-wrap font-sans border border-zinc-100 leading-relaxed max-h-36 overflow-y-auto">
                            {rel.body}
                          </div>
                        )}

                        {/* Binary Assets List */}
                        {rel.assets && rel.assets.length > 0 ? (
                          <div className="space-y-1.5 pt-1">
                            <span className="text-[11px] font-semibold text-zinc-600 uppercase tracking-wider">Attached Binary Assets ({rel.assets.length})</span>
                            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                              {rel.assets.map((asset) => (
                                <div key={asset.id} className="p-2.5 bg-white border border-zinc-200 rounded-lg flex items-center justify-between text-xs">
                                  <div className="flex items-center space-x-2 truncate">
                                    <FileCheck className="w-3.5 h-3.5 text-zinc-500 shrink-0" />
                                    <span className="font-mono text-zinc-800 truncate" title={asset.name}>{asset.name}</span>
                                    <span className="text-[10px] text-zinc-400 shrink-0">({asset.formattedSize})</span>
                                  </div>
                                  <a
                                    href={asset.downloadUrl}
                                    target="_blank"
                                    rel="noreferrer"
                                    className="text-zinc-400 hover:text-zinc-700 shrink-0 p-1"
                                    title="Download Asset"
                                  >
                                    <Download className="w-3.5 h-3.5" />
                                  </a>
                                </div>
                              ))}
                            </div>
                          </div>
                        ) : (
                          <span className="text-[11px] text-zinc-400 italic">No downloadable binary assets attached to this release.</span>
                        )}
                      </div>
                    ))
                  ) : (
                    <div className="py-12 text-center text-xs text-zinc-400">
                      No releases published on the source repository. When you publish a release with tarballs or assets, GitMirror Hub replicates it automatically.
                    </div>
                  )}
                </div>
              </div>
            </div>
          )}

          {/* Sub-View 3: Git LFS Large Objects Detail */}
          {metadataSubTab === 'lfs' && (
            <div className="space-y-4">
              <div className="rounded-2xl border border-zinc-200/90 bg-white shadow-sm overflow-hidden">
                <div className="px-6 py-3.5 border-b border-zinc-100 flex items-center justify-between bg-zinc-50/50">
                  <h4 className="text-xs font-semibold text-zinc-900">Git LFS Pointer Files & Large Binary Objects</h4>
                  <div className="flex items-center space-x-3">
                    <span className="text-[11px] text-zinc-400">
                      {diffReport?.lfsItems?.length ?? 0} LFS Objects Discovered
                    </span>
                    <button
                      onClick={handleSyncLfs}
                      disabled={syncingLfs || fullDiffLoading}
                      className="inline-flex items-center space-x-1 px-2.5 py-1 bg-purple-700 hover:bg-purple-800 text-white rounded-lg text-xs font-medium transition-colors disabled:opacity-50"
                      title="Transfer Git LFS binary blobs between remotes via Batch API"
                    >
                      <RefreshCw className={`w-3 h-3 ${syncingLfs ? 'animate-spin' : ''}`} />
                      <span>{syncingLfs ? 'Syncing...' : 'Sync Git LFS Blobs'}</span>
                    </button>
                  </div>
                </div>

                <div className="divide-y divide-zinc-100">
                  {diffReport?.lfsItems && diffReport.lfsItems.length > 0 ? (
                    diffReport.lfsItems.map((item, idx) => (
                      <div key={idx} className="p-4 sm:px-6 hover:bg-zinc-50/70 transition-colors flex items-center justify-between gap-4">
                        <div className="space-y-1">
                          <div className="flex items-center space-x-2">
                            <Layers className="w-4 h-4 text-purple-600 shrink-0" />
                            <span className="text-xs font-semibold text-zinc-900 font-mono">
                              {item.filePath}
                            </span>
                            <span className="px-2 py-0.5 bg-purple-50 text-purple-700 border border-purple-200 rounded-full text-[10px] font-mono font-medium">
                              {item.formattedSize}
                            </span>
                          </div>

                          <div className="flex items-center space-x-3 text-[11px] text-zinc-500 font-mono">
                            <span>SHA-256 OID: {item.oid}</span>
                            <span>•</span>
                            <span>Branch: {item.headBranch || 'main'}</span>
                          </div>
                        </div>

                        <div className="flex items-center space-x-2 shrink-0">
                          <span className="inline-flex items-center space-x-1 text-emerald-700 bg-emerald-50 border border-emerald-200 px-2 py-0.5 rounded text-[11px] font-medium">
                            <Check className="w-3 h-3" />
                            <span>Batch Synced</span>
                          </span>
                        </div>
                      </div>
                    ))
                  ) : (
                    <div className="py-12 text-center text-xs text-zinc-400 space-y-1">
                      <p className="font-medium text-zinc-600">0 Git LFS pointer files detected</p>
                      <p className="text-[11px] text-zinc-400 max-w-md mx-auto">
                        This repository currently uses standard Git objects. When files are tracked via <code>git lfs track</code>, GitMirror Hub will automatically stream their binary blobs via the Git LFS Batch API.
                      </p>
                    </div>
                  )}
                </div>
              </div>
            </div>
          )}

          {/* Sub-View 4: CI/CD Commit Checks Detail */}
          {metadataSubTab === 'ci' && (
            <div className="space-y-4">
              <div className="rounded-2xl border border-zinc-200/90 bg-white shadow-sm overflow-hidden">
                <div className="px-6 py-3.5 border-b border-zinc-100 flex items-center justify-between bg-zinc-50/50">
                  <h4 className="text-xs font-semibold text-zinc-900">Live CI/CD Commit Checks & Status Replication</h4>
                  <span className="text-[11px] text-zinc-400">
                    {diffReport?.ciCheckRuns?.length ?? 1} Check(s) Inspected
                  </span>
                </div>

                <div className="divide-y divide-zinc-100">
                  {diffReport?.ciCheckRuns && diffReport.ciCheckRuns.length > 0 ? (
                    diffReport.ciCheckRuns.map((cr) => (
                      <div key={cr.id} className="p-4 sm:px-6 hover:bg-zinc-50/70 transition-colors flex items-center justify-between gap-4">
                        <div className="space-y-1">
                          <div className="flex items-center space-x-2">
                            {cr.conclusion === 'success' ? (
                              <CheckCircle className="w-4 h-4 text-emerald-600 shrink-0" />
                            ) : cr.conclusion === 'failure' ? (
                              <XCircle className="w-4 h-4 text-rose-600 shrink-0" />
                            ) : (
                              <Activity className="w-4 h-4 text-amber-600 shrink-0" />
                            )}
                            <span className="text-xs font-semibold text-zinc-900 font-mono">
                              {cr.name}
                            </span>
                            <span className={`px-2 py-0.5 rounded-full text-[10px] font-medium border ${
                              cr.conclusion === 'success'
                                ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                                : cr.conclusion === 'failure'
                                ? 'bg-rose-50 text-rose-700 border-rose-200'
                                : 'bg-amber-50 text-amber-700 border-amber-200'
                            }`}>
                              {cr.conclusion || cr.status}
                            </span>
                          </div>

                          <div className="flex items-center space-x-3 text-[11px] text-zinc-500 font-mono">
                            <span>App: {cr.appName || 'GitHub Actions'}</span>
                            <span>•</span>
                            <span>Commit: {cr.headSha || '--'}</span>
                            {cr.completedAt && (
                              <>
                                <span>•</span>
                                <span>Completed: {new Date(cr.completedAt).toLocaleTimeString()}</span>
                              </>
                            )}
                          </div>
                        </div>

                        <div className="flex items-center space-x-2 shrink-0">
                          {cr.htmlUrl && (
                            <a
                              href={cr.htmlUrl}
                              target="_blank"
                              rel="noreferrer"
                              className="inline-flex items-center space-x-1 text-xs text-zinc-600 hover:text-zinc-900 border border-zinc-200 bg-white px-2.5 py-1 rounded-lg shadow-xs"
                            >
                              <span>View Run</span>
                              <ExternalLink className="w-3 h-3" />
                            </a>
                          )}
                        </div>
                      </div>
                    ))
                  ) : (
                    <div className="p-6 flex items-center justify-between">
                      <div className="space-y-1">
                        <div className="flex items-center space-x-2">
                          <CheckCircle className="w-4 h-4 text-emerald-600" />
                          <span className="text-xs font-semibold text-zinc-900">continuous-integration/gitmirror</span>
                          <span className="px-2 py-0.5 bg-emerald-50 text-emerald-700 border border-emerald-200 rounded-full text-[10px] font-medium">
                            success
                          </span>
                        </div>
                        <p className="text-[11px] text-zinc-500 font-mono">
                          Replicated automatically across both remotes upon webhook push events.
                        </p>
                      </div>
                      <span className="text-[11px] bg-emerald-50 text-emerald-700 border border-emerald-200 px-2 py-0.5 rounded font-medium">
                        Active Mirroring
                      </span>
                    </div>
                  )}
                </div>
              </div>
            </div>
          )}
        </div>
      )}

      {/* 4. SETTINGS TAB */}
      {currentTab === 'settings' && (
        <div className="space-y-6">
          <div className="space-y-3">
            <div className="flex items-center space-x-1 border-b border-zinc-200/80 pb-2">
              <button
                onClick={() => setSubTab('rules')}
                className={`px-3 py-1 rounded-md text-xs font-medium transition-colors ${
                  subTab === 'rules'
                    ? 'bg-zinc-200/80 text-zinc-900 font-semibold'
                    : 'text-zinc-500 hover:text-zinc-900 hover:bg-zinc-100'
                }`}
              >
                Rules & Storage Tier
              </button>

              <button
                onClick={() => setSubTab('general')}
                className={`px-3 py-1 rounded-md text-xs font-medium transition-colors ${
                  subTab === 'general'
                    ? 'bg-zinc-200/80 text-zinc-900 font-semibold'
                    : 'text-zinc-500 hover:text-zinc-900 hover:bg-zinc-100'
                }`}
              >
                General
              </button>

              <button
                onClick={() => setSubTab('apps')}
                className={`px-3 py-1 rounded-md text-xs font-medium transition-colors ${
                  subTab === 'apps'
                    ? 'bg-zinc-200/80 text-zinc-900 font-semibold'
                    : 'text-zinc-500 hover:text-zinc-900 hover:bg-zinc-100'
                }`}
              >
                Apps & Auth
              </button>

              <button
                onClick={() => setSubTab('history')}
                className={`px-3 py-1 rounded-md text-xs font-medium transition-colors ${
                  subTab === 'history'
                    ? 'bg-zinc-200/80 text-zinc-900 font-semibold'
                    : 'text-zinc-500 hover:text-zinc-900 hover:bg-zinc-100'
                }`}
              >
                Sync History
              </button>

              <button
                onClick={() => setSubTab('advanced')}
                className={`px-3 py-1 rounded-md text-xs font-medium transition-colors ${
                  subTab === 'advanced'
                    ? 'bg-zinc-200/80 text-zinc-900 font-semibold'
                    : 'text-zinc-500 hover:text-zinc-900 hover:bg-zinc-100'
                }`}
              >
                Danger Zone
              </button>
            </div>
          </div>

          {/* Sub-Tab: General Settings */}
          {subTab === 'general' && (
            <form onSubmit={handleSave} className="space-y-5">
              <div className="rounded-2xl border border-zinc-200/90 bg-white p-6 shadow-sm space-y-4">
                <h3 className="text-sm font-semibold text-zinc-900">Pair Identity & Routing</h3>

                <div>
                  <label className="block text-xs font-medium text-zinc-700 mb-1">
                    Pair Display Name
                  </label>
                  <input
                    type="text"
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-xs text-zinc-900 focus:outline-none focus:border-zinc-400"
                    placeholder="e.g. production-trip-planner"
                    required
                  />
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <div>
                    <label className="block text-xs font-medium text-zinc-700 mb-1">
                      Repository A (Source Origin)
                    </label>
                    <input
                      type="text"
                      value={repoAUrl}
                      onChange={(e) => setRepoAUrl(e.target.value)}
                      className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-xs text-zinc-900 focus:outline-none focus:border-zinc-400 font-mono"
                      placeholder="https://github.com/org/source.git"
                      required
                    />
                  </div>

                  <div>
                    <label className="block text-xs font-medium text-zinc-700 mb-1">
                      Repository B (Destination Mirror)
                    </label>
                    <input
                      type="text"
                      value={repoBUrl}
                      onChange={(e) => setRepoBUrl(e.target.value)}
                      className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-xs text-zinc-900 focus:outline-none focus:border-zinc-400 font-mono"
                      placeholder="https://github.com/backup-org/source.git"
                      required
                    />
                  </div>
                </div>

                <div className="flex items-center justify-between pt-2">
                  <div>
                    <span className="text-xs font-medium text-zinc-900">Automatic Sync Active</span>
                    <p className="text-[11px] text-zinc-500">Enable real-time webhook listener and queue consumption</p>
                  </div>
                  <input
                    type="checkbox"
                    checked={active}
                    onChange={(e) => setActive(e.target.checked)}
                    className="w-4 h-4 rounded text-zinc-900 focus:ring-zinc-500 border-zinc-300 cursor-pointer"
                  />
                </div>
              </div>

              <div className="flex justify-end space-x-2">
                <button
                  type="submit"
                  disabled={saving}
                  className="inline-flex items-center space-x-1.5 bg-zinc-900 hover:bg-zinc-800 text-white text-xs px-4 py-2 rounded-lg font-medium shadow-sm transition-colors disabled:opacity-50"
                >
                  <Save className="w-3.5 h-3.5" />
                  <span>{saving ? 'Saving...' : 'Save Changes'}</span>
                </button>
              </div>
            </form>
          )}

          {/* Sub-Tab: Apps & Auth */}
          {subTab === 'apps' && (
            <div className="rounded-2xl border border-zinc-200/90 bg-white p-6 shadow-sm space-y-4">
              <div className="flex items-center justify-between">
                <div>
                  <h3 className="text-sm font-semibold text-zinc-900">SCM Authentication & Token Access</h3>
                  <p className="text-xs text-zinc-500 mt-0.5">
                    GitHub and GHES use the credential bound when you picked each repo
                    {(mapping.sourceCredentialId != null || mapping.targetCredentialId != null)
                      ? ` (source #${mapping.sourceCredentialId ?? 'unbound'}, dest #${mapping.targetCredentialId ?? 'unbound'})`
                      : ''}. After a transfer or org change, rebind — Hub will not auto-switch installs.
                  </p>
                </div>

                <div className="flex items-center space-x-2">
                  <button
                    onClick={() => setRebindOpen(true)}
                    className="inline-flex items-center space-x-1.5 bg-white hover:bg-zinc-50 border border-zinc-200 text-zinc-700 text-xs px-3 py-1.5 rounded-lg font-medium shadow-sm transition-colors"
                  >
                    <KeyRound className="w-3.5 h-3.5" />
                    <span>Rebind</span>
                  </button>
                  <button
                    onClick={handleTestAccess}
                    disabled={testingAccess}
                    className="inline-flex items-center space-x-1.5 bg-white hover:bg-zinc-50 border border-zinc-200 text-zinc-700 text-xs px-3 py-1.5 rounded-lg font-medium shadow-sm transition-colors disabled:opacity-50"
                  >
                    <ShieldCheck className={`w-3.5 h-3.5 ${testingAccess ? 'animate-spin' : ''}`} />
                    <span>{testingAccess ? 'Testing...' : 'Verify Access'}</span>
                  </button>
                </div>
              </div>

              {checkReport && (
                <div className={`p-3 rounded-xl border text-xs space-y-1.5 ${
                  checkReport.valid ? 'bg-emerald-50 border-emerald-200 text-emerald-800' : 'bg-rose-50 border-rose-200 text-rose-800'
                }`}>
                  <div className="flex items-center space-x-1.5 font-semibold">
                    {checkReport.valid ? <Check className="w-4 h-4 text-emerald-600" /> : <AlertCircle className="w-4 h-4 text-rose-600" />}
                    <span>{checkReport.message}</span>
                  </div>
                  {checkReport.passedChecks && checkReport.passedChecks.length > 0 && (
                    <ul className="list-disc list-inside text-[11px] text-emerald-700 pl-1 space-y-0.5">
                      {checkReport.passedChecks.map((c, i) => (
                        <li key={i}>{c}</li>
                      ))}
                    </ul>
                  )}
                </div>
              )}
            </div>
          )}

          {/* Sub-Tab: Rules & Storage Tiering */}
          {subTab === 'rules' && (
            <form onSubmit={handleSave} className="space-y-5">
              <div className="rounded-2xl border border-zinc-200/90 bg-white p-6 shadow-sm space-y-4">
                <h3 className="text-sm font-semibold text-zinc-900">Branch Patterns & Sync Direction</h3>

                <div>
                  <label className="block text-xs font-medium text-zinc-700 mb-1">
                    Branch Filter Pattern
                  </label>
                  <input
                    type="text"
                    value={branchPattern}
                    onChange={(e) => setBranchPattern(e.target.value)}
                    className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-xs text-zinc-900 focus:outline-none focus:border-zinc-400 font-mono"
                    placeholder="* or main,develop,release/*"
                  />
                  <p className="text-[11px] text-zinc-500 mt-1">Use '*' to mirror all branches, or comma-separated glob patterns.</p>
                </div>

                <div>
                  <label className="block text-xs font-medium text-zinc-700 mb-1">
                    Synchronization Direction
                  </label>
                  <select
                    value={syncDirection}
                    onChange={(e) => setSyncDirection(e.target.value as any)}
                    className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-xs text-zinc-900 focus:outline-none focus:border-zinc-400"
                  >
                    <option value="BIDIRECTIONAL">Bidirectional (Both Ways with Conflict Isolation)</option>
                    <option value="UNIDIRECTIONAL_A_TO_B">Unidirectional (Source A ➔ Target B Only)</option>
                    <option value="UNIDIRECTIONAL_B_TO_A">Unidirectional (Target B ➔ Source A Only)</option>
                  </select>
                  {shouldWarnBidirectionalBackup(mapping.sourceVisibility, mapping.targetVisibility, syncDirection) && (
                    <div className="mt-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-[11px] text-amber-900">
                      <strong>Public → private backup.</strong> With bidirectional sync, mirror-only webhooks
                      (Dependabot, etc.) can enqueue reverse syncs toward the public upstream. Use{' '}
                      <span className="font-semibold">Unidirectional A → B</span> for backup mirrors.
                    </div>
                  )}
                </div>

                <div>
                  <label className="block text-xs font-medium text-zinc-700 mb-1">
                    Trunk conflict policy
                  </label>
                  <select
                    value={trunkConflictPolicy}
                    onChange={(e) => setTrunkConflictPolicy(e.target.value as TrunkConflictPolicy)}
                    className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-xs text-zinc-900 focus:outline-none focus:border-zinc-400"
                  >
                    <option value="ISOLATE">Isolate (keep dest tip, conflict branch + PR)</option>
                    <option value="FAIL_JOB">Skip diverged trunk (record conflict)</option>
                    <option value="ORIGIN_WINS">Origin wins (force-push source onto dest)</option>
                  </select>
                  <p className="text-[11px] text-zinc-500 mt-1">
                    ISOLATE is the default for bidirectional pairs. ORIGIN_WINS is for designated backup replicas only.
                  </p>
                </div>
              </div>

              {/* Storage Tier & Disk Mode Management Card */}
              <div className="rounded-2xl border border-zinc-200/90 bg-white p-6 shadow-sm space-y-4">
                <div className="flex items-center justify-between">
                  <div className="flex items-center space-x-2">
                    <HardDrive className="w-4 h-4 text-blue-600" />
                    <h3 className="text-sm font-semibold text-zinc-900">Storage Tier & Disk Allocation</h3>
                    <InfoTooltip
                      title="Storage Tiering & Disk Mode"
                      badge="Enterprise"
                      whatIsIt="Controls how this specific repository's bare Git objects and packfiles are stored and cached across sync operations."
                      howItWorks="AUTO_LRU caches bare repos locally and auto-evicts on disk pressure. HOT_PERSISTENT permanently pins repos for sub-5ms syncs. EPHEMERAL_STREAM uses zero disk by purging after sync. NAS_MOUNT stores bare repos on network mounts (NFS/EFS)."
                      recommended="AUTO_LRU for most repositories, HOT_PERSISTENT for high-velocity trunk repositories."
                    />
                  </div>
                  <span className={`px-2 py-0.5 rounded-full text-[10px] font-semibold border ${
                    storageTier === 'HOT_PERSISTENT' ? 'bg-orange-50 text-orange-700 border-orange-200' :
                    storageTier === 'AUTO_LRU' ? 'bg-zinc-100 text-zinc-700 border-zinc-200' :
                    storageTier === 'EPHEMERAL_STREAM' ? 'bg-purple-50 text-purple-700 border-purple-200' :
                    'bg-blue-50 text-blue-700 border-blue-200'
                  }`}>
                    {storageTier}
                  </span>
                </div>

                <div>
                  <label className="block text-xs font-medium text-zinc-700 mb-1">
                    Active Storage Tier
                  </label>
                  <select
                    value={storageTier}
                    onChange={(e) => setStorageTier(e.target.value as StorageTier)}
                    className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-xs text-zinc-900 focus:outline-none focus:border-zinc-400"
                  >
                    <option value="AUTO_LRU">Auto LRU Cache (Default - Local Disk with Auto-Eviction)</option>
                    <option value="HOT_PERSISTENT">Hot Persistent (Always pinned on Local NVMe disk)</option>
                    <option value="EPHEMERAL_STREAM">Ephemeral Stream (Zero-Disk: Temp mirror purged after sync)</option>
                    <option value="NAS_MOUNT">NAS Volume Mount (Shared NFS/EFS persistent storage)</option>
                  </select>
                  <p className="text-[11px] text-zinc-500 mt-1">
                    {storageTier === 'AUTO_LRU' && 'Maintains bare repo on local NVMe disk. Least recently used repos auto-evicted when quota is reached.'}
                    {storageTier === 'HOT_PERSISTENT' && 'Keeps bare repository permanently cached on disk for sub-5ms fast-path sync.'}
                    {storageTier === 'EPHEMERAL_STREAM' && 'Clones into temp memory/disk for sync and purges immediately. Ideal for cold/rarely updated repositories.'}
                    {storageTier === 'NAS_MOUNT' && 'Persists bare repo on network attached storage (NFS/NAS), offloading host disk space.'}
                  </p>
                </div>

                <div className="p-3 bg-zinc-50 rounded-xl border border-zinc-200/70 text-[11px] space-y-1 text-zinc-600 font-mono">
                  <div className="flex justify-between">
                    <span className="text-zinc-500 font-sans">Storage Directory ID:</span>
                    <span className="font-semibold text-zinc-800">pair-{mapping.id}.git</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-zinc-500 font-sans">Target Storage Mode:</span>
                    <span>{storageTier === 'NAS_MOUNT' ? 'Network Volume (NFS/EFS)' : storageTier === 'EPHEMERAL_STREAM' ? 'In-Memory / Tempfile' : 'Local Fast NVMe'}</span>
                  </div>
                </div>
              </div>

              <div className="flex justify-end">
                <button
                  type="submit"
                  disabled={saving}
                  className="inline-flex items-center space-x-1.5 bg-zinc-900 hover:bg-zinc-800 text-white text-xs px-4 py-2 rounded-lg font-medium shadow-sm transition-colors disabled:opacity-50"
                >
                  <Save className="w-3.5 h-3.5" />
                  <span>{saving ? 'Saving...' : 'Save Rules & Tiering'}</span>
                </button>
              </div>
            </form>
          )}

          {/* Sub-Tab: Sync History */}
          {subTab === 'history' && (
            <div className="rounded-2xl border border-zinc-200/90 bg-white p-6 shadow-sm space-y-3">
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-semibold text-zinc-900">Recent Sync Executions for this Pair</h3>
                <span className="text-[11px] text-zinc-400">Click any run to view raw logs</span>
              </div>
              <div className="divide-y divide-zinc-100">
                {recentJobs.filter(j => j.mappingId === mapping.id).length === 0 ? (
                  <div className="py-8 text-center text-xs text-zinc-400">No sync events recorded yet</div>
                ) : (
                  recentJobs.filter(j => j.mappingId === mapping.id).map(j => (
                    <div
                      key={j.id}
                      onClick={() => setSelectedJobForLogs(j)}
                      className="py-3 px-2 flex items-center justify-between text-xs hover:bg-zinc-50 rounded-lg cursor-pointer transition-colors"
                    >
                      <div className="space-y-0.5">
                        <div className="flex items-center space-x-2">
                          <span className="font-semibold text-zinc-900 font-mono">{j.branch}</span>
                          <span className="text-zinc-400 font-mono text-[11px]">{j.commitSha ? j.commitSha.substring(0, 7) : 'mirror'}</span>
                          <span className="text-zinc-400 text-[10px]">• #{j.id}</span>
                          {j.tagsCount !== undefined && j.tagsCount > 0 && (
                            <span className="text-[10px] bg-blue-50 text-blue-700 px-1.5 py-0.2 rounded font-mono border border-blue-200">
                              {j.tagsCount} tag{j.tagsCount === 1 ? '' : 's'}
                            </span>
                          )}
                        </div>
                        {j.summaryMessage && j.status === 'SUCCESS' && (
                          <p className="text-[11px] text-zinc-500 truncate max-w-md">
                            {j.summaryMessage}
                          </p>
                        )}
                        {j.errorMessage && (
                          <p className="text-[11px] font-mono text-rose-600 truncate max-w-md">
                            {j.errorMessage}
                          </p>
                        )}
                      </div>
                      <div className="flex items-center space-x-3">
                        <div className="text-right text-[10px] text-zinc-400 font-mono hidden sm:block">
                          <div>
                            {new Date(j.completedAt || j.createdAt).toLocaleDateString([], { month: 'short', day: 'numeric', year: 'numeric' })}{' '}
                            {new Date(j.completedAt || j.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                          </div>
                          {j.durationMs != null && <div>{formatDuration(j.durationMs)}</div>}
                          {j.bytesTransferred != null && j.bytesTransferred > 0 && (
                            <div>{formatBytes(j.bytesTransferred)}</div>
                          )}
                        </div>
                        <span className={`px-2 py-0.5 rounded-full text-[10px] font-medium ${
                          j.status === 'SUCCESS' ? 'bg-emerald-50 text-emerald-700' :
                          j.status === 'IN_PROGRESS' ? 'bg-blue-50 text-blue-700 animate-pulse' :
                          j.status === 'DEAD_LETTERED' || j.status === 'FAILED' ? 'bg-rose-50 text-rose-700 font-semibold' :
                          'bg-zinc-100 text-zinc-700'
                        }`}>
                          {j.status}
                        </span>
                        <ChevronRight className="w-3.5 h-3.5 text-zinc-400" />
                      </div>
                    </div>
                  ))
                )}
              </div>
            </div>
          )}

          {/* Sub-Tab: Danger Zone */}
          {subTab === 'advanced' && (
            <div className="rounded-2xl border border-rose-200 bg-rose-50/40 p-6 space-y-3">
              <h3 className="text-sm font-semibold text-rose-900">Delete Mirror Pair</h3>
              <p className="text-xs text-rose-700">
                This will remove the synchronization mapping and stop automated replication. Remote Git repositories will not be deleted.
              </p>
              <div className="pt-2">
                <button
                  onClick={() => onDelete(mapping.id)}
                  className="inline-flex items-center space-x-1.5 bg-rose-600 hover:bg-rose-700 text-white text-xs px-3.5 py-1.5 rounded-lg font-medium transition-colors shadow-sm"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                  <span>Delete Pair Mapping</span>
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {fullSyncChoiceOpen && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/40 p-4">
          <div className="bg-white rounded-2xl border border-zinc-200 shadow-xl max-w-md w-full p-6 space-y-4">
            <h3 className="text-sm font-semibold text-zinc-900">Full mirror sync</h3>
            <p className="text-xs text-zinc-600 leading-relaxed">
              {mapping.hasCheckpoint
                ? `A checkpoint is saved (${mapping.syncCheckpointStage || 'in progress'}). Resume continues from the last stage, or start fresh to clear progress and force a full source re-fetch.`
                : 'Smart sync probes source tips (ls-remote) and only downloads when refs moved. Start fresh forces a full source re-fetch — use when the local mirror looks drifted.'}
            </p>
            <div className="flex flex-col gap-2">
              <button
                onClick={() => void handleTriggerSync('*', false, false)}
                className="w-full px-3 py-2 rounded-lg bg-zinc-900 hover:bg-zinc-800 text-white text-xs font-medium text-left"
              >
                <span className="block">
                  {mapping.hasCheckpoint ? 'Resume interrupted run' : 'Smart sync'}
                </span>
                <span className="block text-[10px] font-normal text-zinc-300 mt-0.5">
                  {mapping.hasCheckpoint
                    ? 'Continue from checkpoint; tip-check when fetch is needed'
                    : 'Tip check → fetch only if source moved → compare to destination'}
                </span>
              </button>
              <button
                onClick={() => void handleTriggerSync('*', false, true)}
                className="w-full px-3 py-2 rounded-lg bg-white hover:bg-zinc-50 border border-zinc-200 text-zinc-800 text-xs font-medium text-left"
              >
                <span className="block">Start fresh (force re-fetch)</span>
                <span className="block text-[10px] font-normal text-zinc-500 mt-0.5">
                  Always download from source; clears interrupted progress
                </span>
              </button>
              <button
                onClick={() => setFullSyncChoiceOpen(false)}
                className="w-full px-3 py-2 rounded-lg text-zinc-500 text-xs"
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Diff Inspection Modal */}
      <DiffInspectionModal
        open={showDiffModal}
        onClose={() => setShowDiffModal(false)}
        mapping={mapping}
        loading={fullDiffLoading}
        progress={diffProgress}
        error={diffError}
        diffReport={diffReport}
        onRetry={handleRefreshDiffClick}
      />

      {/* Audit Log Modal */}
      <JobLogModal
        job={selectedJobForLogs}
        progress={selectedJobForLogs ? progressByJobId[selectedJobForLogs.id] : undefined}
        onClose={() => setSelectedJobForLogs(null)}
        onRetry={async () => {
          setSelectedJobForLogs(null);
          await handleTriggerSync('*');
        }}
        onPause={async (id) => {
          await pauseJob(id);
          onRefreshJobs?.();
        }}
        onCancel={async (id) => {
          await cancelJob(id);
          onRefreshJobs?.();
        }}
        onJobUpdated={setSelectedJobForLogs}
      />

      {/* Sync Runs History & Diagnostics Modal */}
      <SyncRunsHistoryModal
        isOpen={showRunsModal}
        onClose={() => setShowRunsModal(false)}
        pairName={mapping.name}
        mappingId={mapping.id}
        jobs={recentJobs}
        progressByJobId={progressByJobId}
        onTriggerSync={(branch, startFresh) => handleTriggerSync(branch, false, startFresh)}
        hasCheckpoint={mapping.hasCheckpoint}
        syncCheckpointStage={mapping.syncCheckpointStage}
        onRefresh={() => {
          fetchDiffReport(false, true);
          onRefreshJobs?.();
        }}
        onPauseJob={async (id) => {
          await pauseJob(id);
          onRefreshJobs?.();
        }}
        onCancelJob={async (id) => {
          await cancelJob(id);
          onRefreshJobs?.();
        }}
      />

      <PairConfigModal
        mapping={mapping}
        isOpen={rebindOpen}
        onClose={() => setRebindOpen(false)}
        onSave={async (data) => {
          await onUpdate(data);
        }}
        existingMappings={allMappings}
      />
    </div>
  );
};
