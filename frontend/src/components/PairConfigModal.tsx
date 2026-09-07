import React, { useState, useEffect, useMemo } from 'react';
import {
  X, Save, CheckCircle2, AlertCircle, ShieldCheck, Check, Sparkles, Plus,
  HardDrive, Database, Search, Globe, GitFork, Layers, Lock, ArrowRight, Sparkle, AlertTriangle
} from 'lucide-react';
import { RepoMapping, SyncDirection, StorageTier, PermissionCheckReport, GitHubRepoOption, TrunkConflictPolicy } from '../types';
import { testRepoConnection, createRemoteRepository } from '../services/api';
import { RepoPickerModal } from './RepoPickerModal';
import { InfoTooltip } from './InfoTooltip';
import { findRepoCollision } from '../utils/repoUrl';
import { shouldWarnBidirectionalBackup } from '../utils/mirrorTopology';

interface PairConfigModalProps {
  mapping: RepoMapping | null;
  isOpen: boolean;
  onClose: () => void;
  onSave: (data: Partial<RepoMapping>) => Promise<void>;
  existingMappings?: RepoMapping[];
}

export const PairConfigModal: React.FC<PairConfigModalProps> = ({
  mapping,
  isOpen,
  onClose,
  onSave,
  existingMappings = [],
}) => {
  const [repoAUrl, setRepoAUrl] = useState('');
  const [repoBUrl, setRepoBUrl] = useState('');
  const [branchPattern, setBranchPattern] = useState('*');
  const [syncDirection, setSyncDirection] = useState<SyncDirection>('BIDIRECTIONAL');
  const [trunkConflictPolicy, setTrunkConflictPolicy] = useState<TrunkConflictPolicy>('ISOLATE');
  const [active, setActive] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  // Verification state
  const [testingA, setTestingA] = useState(false);
  const [reportA, setReportA] = useState<PermissionCheckReport | null>(null);
  const [testingB, setTestingB] = useState(false);
  const [reportB, setReportB] = useState<PermissionCheckReport | null>(null);
  const [creatingRepoB, setCreatingRepoB] = useState(false);
  const [createFeedback, setCreateFeedback] = useState<string | null>(null);
  const [visibilityA, setVisibilityA] = useState<'UNKNOWN' | 'PUBLIC' | 'PRIVATE'>('UNKNOWN');
  const [visibilityB, setVisibilityB] = useState<'UNKNOWN' | 'PUBLIC' | 'PRIVATE'>('UNKNOWN');

  // Lazy Repository Picker modal state
  const [pickerTarget, setPickerTarget] = useState<'A' | 'B' | null>(null);
  const [sourceCredentialId, setSourceCredentialId] = useState<number | undefined>(undefined);
  const [targetCredentialId, setTargetCredentialId] = useState<number | undefined>(undefined);

  useEffect(() => {
    if (mapping) {
      setRepoAUrl(mapping.repoAUrl || '');
      setRepoBUrl(mapping.repoBUrl || '');
      setBranchPattern(mapping.branchPattern || '*');
      setSyncDirection(mapping.syncDirection || 'BIDIRECTIONAL');
      setTrunkConflictPolicy(mapping.trunkConflictPolicy || 'ISOLATE');
      setActive(mapping.active ?? true);
      setVisibilityA(mapping.sourceVisibility || 'UNKNOWN');
      setVisibilityB(mapping.targetVisibility || 'UNKNOWN');
      setSourceCredentialId(mapping.sourceCredentialId);
      setTargetCredentialId(mapping.targetCredentialId);
    } else {
      setRepoAUrl('');
      setRepoBUrl('');
      setBranchPattern('*');
      setSyncDirection('BIDIRECTIONAL');
      setTrunkConflictPolicy('ISOLATE');
      setActive(true);
      setVisibilityA('UNKNOWN');
      setVisibilityB('UNKNOWN');
      setSourceCredentialId(undefined);
      setTargetCredentialId(undefined);
    }
    setReportA(null);
    setReportB(null);
    setSaveError(null);
  }, [mapping, isOpen]);

  const collisionA = useMemo(() => findRepoCollision(repoAUrl, existingMappings, mapping?.id), [repoAUrl, existingMappings, mapping?.id]);
  const collisionB = useMemo(() => findRepoCollision(repoBUrl, existingMappings, mapping?.id), [repoBUrl, existingMappings, mapping?.id]);
  const hasCollision = Boolean(collisionA || collisionB);

  if (!isOpen) return null;

  const deriveRepoName = (url: string) => {
    try {
      const match = url.trim().match(/\/([^\/]+?)(?:\.git)?$/);
      if (match && match[1]) return match[1];
    } catch {}
    return 'repo-' + Date.now();
  };

  const extractOwnerAndRepo = (url: string) => {
    try {
      const trimmed = url.trim().replace(/\.git$/, '');
      const match = trimmed.match(/(?:[:\/])([^\/:]+)\/([^\/:]+)$/);
      if (match) {
        return { owner: match[1], repo: match[2] };
      }
    } catch {}
    return null;
  };

  const reportBannerClass = (report: PermissionCheckReport) => {
    if (report.valid && report.accessMode === 'PUBLIC') {
      return 'bg-sky-50 border-sky-200 text-sky-800';
    }
    if (report.valid) {
      return 'bg-emerald-50 border-emerald-200 text-emerald-800';
    }
    return 'bg-rose-50 border-rose-200 text-rose-800';
  };

  const detectProviderInfo = (url: string) => {
    if (!url) return null;
    const lower = url.toLowerCase();
    if (lower.includes('github.com')) {
      return { provider: 'GitHub', icon: <GitFork className="w-3.5 h-3.5 text-zinc-900" />, color: 'bg-zinc-100 text-zinc-800' };
    }
    if (lower.includes('gitlab.com') || lower.includes('gitlab')) {
      return { provider: 'GitLab', icon: <Layers className="w-3.5 h-3.5 text-orange-600" />, color: 'bg-orange-50 text-orange-700 border-orange-200' };
    }
    if (lower.includes('bitbucket.org')) {
      return { provider: 'Bitbucket', icon: <Globe className="w-3.5 h-3.5 text-blue-600" />, color: 'bg-blue-50 text-blue-700 border-blue-200' };
    }
    if (lower.includes('origin.cursor.com')) {
      return { provider: 'Cursor Origin', icon: <Lock className="w-3.5 h-3.5 text-purple-600" />, color: 'bg-purple-50 text-purple-700 border-purple-200' };
    }
    if (lower.includes('azure') || lower.includes('visualstudio.com')) {
      return { provider: 'Azure DevOps', icon: <Globe className="w-3.5 h-3.5 text-cyan-600" />, color: 'bg-cyan-50 text-cyan-700 border-cyan-200' };
    }
    return { provider: 'Git Remote', icon: <GitFork className="w-3.5 h-3.5 text-zinc-500" />, color: 'bg-zinc-100 text-zinc-600' };
  };

  const getSmartMirrorSuggestions = () => {
    if (!repoAUrl) return [];
    const parsed = extractOwnerAndRepo(repoAUrl);
    if (!parsed) return [];

    const { owner, repo } = parsed;
    const suggestions = [];

    if (!repoAUrl.includes('gitlab.com')) {
      suggestions.push({
        label: 'GitLab',
        url: `https://gitlab.com/${owner}/${repo}.git`,
        icon: <Layers className="w-3 h-3 text-orange-500" />
      });
    }
    if (!repoAUrl.includes('github.com')) {
      suggestions.push({
        label: 'GitHub',
        url: `https://github.com/${owner}/${repo}.git`,
        icon: <GitFork className="w-3 h-3 text-zinc-800" />
      });
    }
    if (!repoAUrl.includes('bitbucket.org')) {
      suggestions.push({
        label: 'Bitbucket',
        url: `https://bitbucket.org/${owner}/${repo}.git`,
        icon: <Globe className="w-3 h-3 text-blue-500" />
      });
    }
    if (!repoAUrl.includes('origin.cursor.com')) {
      suggestions.push({
        label: 'Cursor Origin',
        url: `https://origin.cursor.com/${repo}`,
        icon: <Lock className="w-3 h-3 text-purple-500" />
      });
    }

    return suggestions;
  };

  const handleTestConnectionA = async () => {
    if (!repoAUrl) return;
    setTestingA(true);
    try {
      const res = await testRepoConnection({
        repoUrl: repoAUrl,
        requiredAccess: 'READ',
        knownPrivate: visibilityA === 'PRIVATE',
        credentialId: sourceCredentialId,
      });
      setReportA(res);
      if (res.accessMode === 'PUBLIC') setVisibilityA('PUBLIC');
      else if (res.isPrivate) setVisibilityA('PRIVATE');
    } catch (e: any) {
      setReportA({
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
      setTestingA(false);
    }
  };

  const handleTestConnectionB = async () => {
    if (!repoBUrl) return;
    setTestingB(true);
    try {
      const res = await testRepoConnection({
        repoUrl: repoBUrl,
        requiredAccess: syncDirection === 'UNIDIRECTIONAL_B_TO_A' ? 'READ' : 'WRITE',
        knownPrivate: visibilityB === 'PRIVATE' || syncDirection !== 'UNIDIRECTIONAL_B_TO_A',
        credentialId: targetCredentialId,
      });
      setReportB(res);
      if (res.accessMode === 'PUBLIC' && syncDirection === 'UNIDIRECTIONAL_B_TO_A') setVisibilityB('PUBLIC');
      else if (res.isPrivate) setVisibilityB('PRIVATE');
    } catch (e: any) {
      setReportB({
        valid: false,
        repoFullName: repoBUrl,
        isPrivate: false,
        httpStatusCode: 500,
        message: e.message || 'Verification call failed',
        passedChecks: [],
        warnings: [],
        errors: [e.message || 'Connection error'],
      });
    } finally {
      setTestingB(false);
    }
  };

  const handleCreateRemoteRepoB = async () => {
    if (!repoBUrl) return;
    const destNeedsCredential = /github/i.test(repoBUrl) && !/gitlab|bitbucket|origin\.cursor/i.test(repoBUrl);
    if (destNeedsCredential && !targetCredentialId) {
      setCreateFeedback('Pick the destination from Browse Repos so this pair knows which GitHub/GHES credential should create it.');
      return;
    }
    setCreatingRepoB(true);
    setCreateFeedback(null);
    try {
      const parsed = extractOwnerAndRepo(repoBUrl);
      const created = await createRemoteRepository({
        repoUrl: repoBUrl,
        isPrivate: true,
        credentialId: targetCredentialId,
        owner: parsed?.owner,
        name: parsed?.repo,
      });
      setCreateFeedback(`Successfully created ${created.fullName}!`);
      await handleTestConnectionB();
    } catch (e: any) {
      setCreateFeedback(`Failed to create repository: ${e.response?.data?.error || e.response?.data?.message || e.message}`);
    } finally {
      setCreatingRepoB(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (hasCollision) return;
    setSaving(true);
    setSaveError(null);
    try {
      const autoName = deriveRepoName(repoAUrl);
      await onSave({
        name: autoName,
        repoAUrl,
        repoBUrl,
        branchPattern,
        syncDirection,
        trunkConflictPolicy,
        storageTier: mapping?.storageTier || 'AUTO_LRU',
        active,
        sourceProvider: detectProviderInfo(repoAUrl)?.provider,
        targetProvider: detectProviderInfo(repoBUrl)?.provider,
        sourceVisibility: visibilityA,
        targetVisibility: visibilityB,
        sourceCredentialId,
        targetCredentialId,
      });
      onClose();
    } catch (err: any) {
      console.error('Error saving mapping:', err);
      setSaveError(err.response?.data?.error || err.message || 'Failed to save mirror pair configuration.');
    } finally {
      setSaving(false);
    }
  };

  const providerA = detectProviderInfo(repoAUrl);
  const providerB = detectProviderInfo(repoBUrl);
  const smartSuggestions = getSmartMirrorSuggestions();

  return (
    <>
      <div className="fixed inset-0 z-50 bg-black/40 backdrop-blur-sm flex items-center justify-center p-4">
        <div className="bg-white border border-zinc-200 rounded-2xl w-full max-w-xl shadow-2xl overflow-hidden flex flex-col my-auto max-h-[90vh]">
          {/* Header */}
          <div className="px-6 py-4 border-b border-zinc-100 flex items-center justify-between shrink-0">
            <div>
              <h3 className="text-sm font-semibold text-zinc-900">
                {mapping && mapping.id ? 'Edit Mirror Repository' : 'Add New Mirror Repository'}
              </h3>
              <p className="text-xs text-zinc-500">Configure source repository and destination mirror endpoint</p>
            </div>
            <button onClick={onClose} className="p-1.5 text-zinc-400 hover:text-zinc-700 rounded-lg hover:bg-zinc-100 transition-colors">
              <X className="w-4 h-4" />
            </button>
          </div>

          {/* Form Body */}
          <form onSubmit={handleSubmit} className="p-6 space-y-4 text-xs overflow-y-auto flex-1">
            {/* Source Repository Card */}
            <div className="p-4 rounded-xl bg-zinc-50/70 border border-zinc-200/80 space-y-2.5">
              <div className="flex items-center justify-between">
                <div className="flex items-center space-x-2">
                  <span className="font-semibold text-zinc-800">Source Repository (Origin)</span>
                  {providerA && (
                    <span className={`inline-flex items-center space-x-1 px-2 py-0.5 rounded-full text-[10px] font-medium border ${providerA.color}`}>
                      {providerA.icon}
                      <span>{providerA.provider}</span>
                    </span>
                  )}
                </div>

                <button
                  type="button"
                  onClick={() => setPickerTarget('A')}
                  className="flex items-center space-x-1 text-zinc-600 hover:text-zinc-900 font-medium text-[11px] bg-white hover:bg-zinc-100 border border-zinc-200 px-2.5 py-1 rounded-md shadow-xs transition-colors"
                >
                  <Search className="w-3 h-3 text-zinc-500" />
                  <span>Browse Repos...</span>
                </button>
              </div>

              <div className="flex items-center space-x-2">
                <input
                  type="text"
                  value={repoAUrl}
                  onChange={(e) => {
                    setRepoAUrl(e.target.value);
                    setReportA(null);
                  }}
                  placeholder="https://github.com/owner/source-repo.git or gitlab.com/..."
                  className="flex-1 bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                  required
                />
                <button
                  type="button"
                  onClick={handleTestConnectionA}
                  disabled={testingA || !repoAUrl}
                  className="flex items-center space-x-1.5 bg-white hover:bg-zinc-100 text-zinc-700 px-3 py-2 rounded-lg border border-zinc-200 font-medium transition-colors text-xs shrink-0 disabled:opacity-40"
                >
                  <ShieldCheck className={`w-3.5 h-3.5 ${testingA ? 'animate-spin' : ''}`} />
                  <span>{testingA ? 'Checking...' : 'Check Access'}</span>
                </button>
              </div>
              {sourceCredentialId != null && (
                <p className="text-[10px] text-zinc-500">
                  Bound to GitHub/GHES credential #{sourceCredentialId}. Browse again to rebind after a transfer or org change.
                </p>
              )}
              <div className="flex items-center space-x-1.5 text-[10px]">
                <span className="text-zinc-500 font-medium">Visibility</span>
                {(['UNKNOWN', 'PUBLIC', 'PRIVATE'] as const).map((v) => (
                  <button
                    key={v}
                    type="button"
                    onClick={() => setVisibilityA(v)}
                    className={`px-2 py-0.5 rounded-md border ${
                      visibilityA === v ? 'bg-zinc-900 text-white border-zinc-900' : 'bg-white text-zinc-600 border-zinc-200'
                    }`}
                  >
                    {v === 'UNKNOWN' ? 'Auto' : v === 'PUBLIC' ? 'Public' : 'Private'}
                  </button>
                ))}
                <span className="text-zinc-400">Auto = probe public HTTPS first. Private = App/token only.</span>
              </div>

              {reportA && (
                <div className="space-y-1.5">
                  <div className={`p-2 rounded-lg border text-[11px] flex items-center space-x-1.5 ${reportBannerClass(reportA)}`}>
                    {reportA.valid ? <Check className={`w-3.5 h-3.5 shrink-0 ${reportA.accessMode === 'PUBLIC' ? 'text-sky-600' : 'text-emerald-600'}`} /> : <AlertCircle className="w-3.5 h-3.5 text-rose-600 shrink-0" />}
                    <span className="font-medium">{reportA.message}</span>
                  </div>
                  {reportA.accessMode === 'PUBLIC' && reportA.passedChecks && reportA.passedChecks.length > 0 && (
                    <div className="p-2 bg-sky-50/60 border border-sky-200/80 rounded-lg space-y-1 text-[11px] text-sky-800">
                      {reportA.passedChecks.map((check, idx) => (
                        <div key={idx} className="flex items-start space-x-1.5">
                          <span className="text-sky-500 font-bold">•</span>
                          <span>{check}</span>
                        </div>
                      ))}
                    </div>
                  )}
                  {reportA.errors && reportA.errors.length > 0 && (
                    <div className="p-2 bg-rose-50/60 border border-rose-200/80 rounded-lg space-y-1 text-[11px] text-rose-700">
                      {reportA.errors.map((err, idx) => (
                        <div key={idx} className="flex items-start space-x-1.5">
                          <span className="text-rose-500 font-bold">•</span>
                          <span>{err}</span>
                        </div>
                      ))}
                    </div>
                  )}
                  {reportA.warnings && reportA.warnings.length > 0 && (
                    <div className="p-2 bg-amber-50/60 border border-amber-200/80 rounded-lg space-y-1 text-[11px] text-amber-800">
                      {reportA.warnings.map((warn, idx) => (
                        <div key={idx} className="flex items-start space-x-1.5">
                          <span className="text-amber-500 font-bold">!</span>
                          <span>{warn}</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </div>

            {/* Smart Recommendation Chips */}
            {repoAUrl && smartSuggestions.length > 0 && !repoBUrl && (
              <div className="p-3 bg-zinc-50/80 border border-zinc-200/80 rounded-xl space-y-2">
                <div className="flex items-center space-x-1.5 text-zinc-600 text-[11px] font-medium">
                  <Sparkle className="w-3.5 h-3.5 text-amber-500" />
                  <span>Quick Mirror Targets:</span>
                </div>
                <div className="flex flex-wrap gap-1.5">
                  {smartSuggestions.map((sug) => (
                    <button
                      key={sug.label}
                      type="button"
                      onClick={() => {
                        setRepoBUrl(sug.url);
                        setReportB(null);
                      }}
                      className="inline-flex items-center space-x-1.5 px-2.5 py-1 bg-white hover:bg-zinc-100 border border-zinc-200 rounded-lg text-zinc-700 text-[11px] font-medium transition-colors shadow-xs"
                    >
                      {sug.icon}
                      <span>Mirror to {sug.label}</span>
                    </button>
                  ))}
                </div>
              </div>
            )}

            {/* Destination Mirror Card */}
            <div className="p-4 rounded-xl bg-zinc-50/70 border border-zinc-200/80 space-y-2.5">
              <div className="flex items-center justify-between">
                <div className="flex items-center space-x-2">
                  <span className="font-semibold text-zinc-800">Destination Repository (Mirror)</span>
                  {providerB && (
                    <span className={`inline-flex items-center space-x-1 px-2 py-0.5 rounded-full text-[10px] font-medium border ${providerB.color}`}>
                      {providerB.icon}
                      <span>{providerB.provider}</span>
                    </span>
                  )}
                </div>

                <button
                  type="button"
                  onClick={() => setPickerTarget('B')}
                  className="flex items-center space-x-1 text-zinc-600 hover:text-zinc-900 font-medium text-[11px] bg-white hover:bg-zinc-100 border border-zinc-200 px-2.5 py-1 rounded-md shadow-xs transition-colors"
                >
                  <Search className="w-3 h-3 text-zinc-500" />
                  <span>Browse Repos...</span>
                </button>
              </div>

              <div className="flex items-center space-x-2">
                <input
                  type="text"
                  value={repoBUrl}
                  onChange={(e) => {
                    setRepoBUrl(e.target.value);
                    setReportB(null);
                  }}
                  placeholder="https://gitlab.com/owner/mirror-repo.git or github.com/..."
                  className="flex-1 bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                  required
                />
                <button
                  type="button"
                  onClick={handleTestConnectionB}
                  disabled={testingB || !repoBUrl}
                  className="flex items-center space-x-1.5 bg-white hover:bg-zinc-100 text-zinc-700 px-3 py-2 rounded-lg border border-zinc-200 font-medium transition-colors text-xs shrink-0 disabled:opacity-40"
                >
                  <ShieldCheck className={`w-3.5 h-3.5 ${testingB ? 'animate-spin' : ''}`} />
                  <span>{testingB ? 'Checking...' : 'Check Access'}</span>
                </button>
              </div>
              {targetCredentialId != null && (
                <p className="text-[10px] text-zinc-500">
                  Bound to GitHub/GHES credential #{targetCredentialId}. Browse again to rebind after a transfer or org change.
                </p>
              )}
              <div className="flex items-center space-x-1.5 text-[10px]">
                <span className="text-zinc-500 font-medium">Visibility</span>
                {(['UNKNOWN', 'PUBLIC', 'PRIVATE'] as const).map((v) => (
                  <button
                    key={v}
                    type="button"
                    onClick={() => setVisibilityB(v)}
                    className={`px-2 py-0.5 rounded-md border ${
                      visibilityB === v ? 'bg-zinc-900 text-white border-zinc-900' : 'bg-white text-zinc-600 border-zinc-200'
                    }`}
                  >
                    {v === 'UNKNOWN' ? 'Auto' : v === 'PUBLIC' ? 'Public' : 'Private'}
                  </button>
                ))}
                <span className="text-zinc-400">Dest write always uses App/token. Mark Private if this mirror is not public.</span>
              </div>

              {reportB && (
                <div className="space-y-2">
                  <div className={`p-2 rounded-lg border text-[11px] flex items-center space-x-1.5 ${reportBannerClass(reportB)}`}>
                    {reportB.valid ? <Check className={`w-3.5 h-3.5 shrink-0 ${reportB.accessMode === 'PUBLIC' ? 'text-sky-600' : 'text-emerald-600'}`} /> : <AlertCircle className="w-3.5 h-3.5 text-rose-600 shrink-0" />}
                    <span className="font-medium">{reportB.message}</span>
                  </div>

                  {reportB.errors && reportB.errors.length > 0 && (
                    <div className="p-2 bg-rose-50/60 border border-rose-200/80 rounded-lg space-y-1 text-[11px] text-rose-700">
                      {reportB.errors.map((err, idx) => (
                        <div key={idx} className="flex items-start space-x-1.5">
                          <span className="text-rose-500 font-bold">•</span>
                          <span>{err}</span>
                        </div>
                      ))}
                    </div>
                  )}

                  {reportB.warnings && reportB.warnings.length > 0 && (
                    <div className="p-2 bg-amber-50/60 border border-amber-200/80 rounded-lg space-y-1 text-[11px] text-amber-800">
                      {reportB.warnings.map((warn, idx) => (
                        <div key={idx} className="flex items-start space-x-1.5">
                          <span className="text-amber-500 font-bold">!</span>
                          <span>{warn}</span>
                        </div>
                      ))}
                    </div>
                  )}

                  {!reportB.valid && (reportB.httpStatusCode === 404 || reportB.message.toLowerCase().includes('not found')) && repoBUrl.includes('github.com') && (
                    <div className="flex items-center justify-between p-2.5 bg-indigo-50/80 border border-indigo-200/90 rounded-lg text-indigo-900">
                      <div>
                        <p className="font-medium text-[11px]">Repository does not exist yet?</p>
                        <p className="text-[10px] text-indigo-600">Auto-create this empty private repository on GitHub.</p>
                      </div>
                      <button
                        type="button"
                        onClick={handleCreateRemoteRepoB}
                        disabled={creatingRepoB}
                        className="inline-flex items-center space-x-1 px-2.5 py-1.5 rounded-md bg-indigo-600 hover:bg-indigo-700 text-white font-medium text-[11px] shadow-sm transition-colors disabled:opacity-50 shrink-0"
                      >
                        <Sparkles className={`w-3 h-3 ${creatingRepoB ? 'animate-spin' : ''}`} />
                        <span>{creatingRepoB ? 'Creating...' : 'Create on GitHub'}</span>
                      </button>
                    </div>
                  )}
                </div>
              )}

              {createFeedback && (
                <div className={`p-2 rounded-lg border text-[11px] ${
                  createFeedback.includes('Successfully') ? 'bg-emerald-50 border-emerald-200 text-emerald-800' : 'bg-rose-50 border-rose-200 text-rose-800'
                }`}>
                  {createFeedback}
                </div>
              )}
            </div>

            {/* Sync Direction & Branch Pattern */}
            <div className="grid grid-cols-2 gap-3">
              <div>
                <div className="flex items-center space-x-1 mb-1">
                  <label className="block text-zinc-700 font-medium">Sync Direction</label>
                  <InfoTooltip
                    title="Mirror Sync Direction"
                    whatIsIt="Configures the traffic flow and active replication routes between Repository A and Repository B."
                    howItWorks="BIDIRECTIONAL enables real-time 2-way sync with automated commit loop dedup. UNIDIRECTIONAL synchronizes changes strictly in one direction."
                    recommended="BIDIRECTIONAL for active team collaboration, UNIDIRECTIONAL for backups."
                  />
                </div>
                <select
                  value={syncDirection}
                  onChange={(e) => setSyncDirection(e.target.value as SyncDirection)}
                  className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 text-xs focus:outline-none focus:border-zinc-400"
                >
                  <option value="BIDIRECTIONAL">Bidirectional (A ⇄ B)</option>
                  <option value="UNIDIRECTIONAL_A_TO_B">Unidirectional (A → B)</option>
                  <option value="UNIDIRECTIONAL_B_TO_A">Unidirectional (B → A)</option>
                </select>
                {shouldWarnBidirectionalBackup(visibilityA, visibilityB, syncDirection) && (
                  <div className="mt-2 rounded-lg border border-amber-200 bg-amber-50 px-2.5 py-2 text-[11px] text-amber-900">
                    <strong>Public → private backup detected.</strong> Bidirectional sync will reverse-propagate
                    mirror-only changes (e.g. Dependabot) back to the public upstream. Use{' '}
                    <span className="font-semibold">Unidirectional (A → B)</span> for backup mirrors.
                  </div>
                )}
              </div>

              <div>
                <div className="flex items-center space-x-1 mb-1">
                  <label className="block text-zinc-700 font-medium">Branch Pattern</label>
                  <InfoTooltip
                    title="Branch Filtering Pattern"
                    whatIsIt="Pattern matching which branch refs are mirrored across repositories."
                    howItWorks="Use '*' for all branches, or comma-separated glob/exact names (e.g. 'main, develop, release/*'). Non-matching branches are ignored."
                    recommended="* for full repository mirror"
                  />
                </div>
                <input
                  type="text"
                  value={branchPattern}
                  onChange={(e) => setBranchPattern(e.target.value)}
                  placeholder="* (All branches)"
                  className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                />
              </div>
            </div>

            <div>
              <div className="flex items-center space-x-1 mb-1">
                <label className="block text-zinc-700 font-medium">Trunk conflict policy</label>
                <InfoTooltip
                  title="Trunk split-brain policy"
                  whatIsIt="What happens when both sides of a shared trunk (main/master/release) diverge and neither tip is a fast-forward of the other."
                  howItWorks="ISOLATE keeps the destination tip and pushes incoming commits to sync-conflict/<branch>-<timestamp>, then opens a PR. FAIL_JOB records the conflict and skips that trunk. ORIGIN_WINS force-pushes source onto destination (backup/DR pairs only)."
                  recommended="ISOLATE for bidirectional collaboration. ORIGIN_WINS only when one side is a designated replica."
                />
              </div>
              <select
                value={trunkConflictPolicy}
                onChange={(e) => setTrunkConflictPolicy(e.target.value as TrunkConflictPolicy)}
                className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 text-xs focus:outline-none focus:border-zinc-400"
              >
                <option value="ISOLATE">Isolate (keep dest tip, conflict branch + PR)</option>
                <option value="FAIL_JOB">Skip diverged trunk (record conflict)</option>
                <option value="ORIGIN_WINS">Origin wins (force-push source)</option>
              </select>
            </div>

            {/* Active Toggle */}
            <div className="flex items-center justify-between p-3 bg-zinc-50 border border-zinc-200/80 rounded-xl">
              <div>
                <span className="text-zinc-800 font-medium text-xs block">Active Mirroring</span>
                <p className="text-[11px] text-zinc-500">Automatically processes inbound webhooks and queued events</p>
              </div>
              <input
                type="checkbox"
                id="pairActive"
                checked={active}
                onChange={(e) => setActive(e.target.checked)}
                className="w-4 h-4 rounded text-zinc-900 focus:ring-zinc-900 cursor-pointer"
              />
            </div>

            {/* Collision & Validation Warnings */}
            {hasCollision && (
              <div className="p-3 bg-rose-50 border border-rose-200 rounded-xl space-y-1 text-rose-900">
                <div className="flex items-center space-x-1.5 font-bold text-xs text-rose-950">
                  <AlertTriangle className="w-4 h-4 text-rose-600 shrink-0" />
                  <span>Repository Assignment Conflict</span>
                </div>
                {collisionA && (
                  <p className="text-[11px] text-rose-800">
                    Source repository <span className="font-mono font-semibold">{repoAUrl}</span> is already mapped in active pair <span className="font-semibold font-mono">'{collisionA.pairName}'</span>.
                  </p>
                )}
                {collisionB && (
                  <p className="text-[11px] text-rose-800">
                    Destination repository <span className="font-mono font-semibold">{repoBUrl}</span> is already mapped in active pair <span className="font-semibold font-mono">'{collisionB.pairName}'</span>.
                  </p>
                )}
                <p className="text-[10px] text-rose-700/90 pt-0.5">
                  A repository cannot be mapped across multiple active mirror pairs simultaneously to prevent destructive history collisions and branch pruning.
                </p>
              </div>
            )}

            {saveError && (
              <div className="p-3 bg-rose-50 border border-rose-200 rounded-xl flex items-center space-x-2 text-xs text-rose-900">
                <AlertCircle className="w-4 h-4 text-rose-600 shrink-0" />
                <span>{saveError}</span>
              </div>
            )}

            {/* Footer Buttons */}
            <div className="flex items-center justify-end space-x-2 pt-4 border-t border-zinc-100">
              <button
                type="button"
                onClick={onClose}
                className="px-4 py-2 bg-white hover:bg-zinc-100 text-zinc-700 rounded-lg border border-zinc-200 font-medium text-xs transition-colors"
              >
                Cancel
              </button>
              <button
                type="submit"
                disabled={saving || !repoAUrl || !repoBUrl || hasCollision}
                className="px-4 py-2 bg-zinc-900 hover:bg-zinc-800 text-white rounded-lg font-medium text-xs transition-colors disabled:opacity-40 flex items-center space-x-1.5"
              >
                <Save className="w-3.5 h-3.5" />
                <span>{saving ? 'Saving...' : 'Save Mirror Pair'}</span>
              </button>
            </div>
          </form>
        </div>
      </div>

      {/* Lazy Repository Explorer Modal */}
      {pickerTarget && (
        <RepoPickerModal
          isOpen={true}
          onClose={() => setPickerTarget(null)}
          onSelectRepo={(selectedRepo) => {
            if (pickerTarget === 'A') {
              setRepoAUrl(selectedRepo.cloneUrl);
              setReportA(null);
              setVisibilityA(selectedRepo.isPrivate ? 'PRIVATE' : 'PUBLIC');
              setSourceCredentialId(selectedRepo.credentialId);
            } else {
              setRepoBUrl(selectedRepo.cloneUrl);
              setReportB(null);
              setVisibilityB(selectedRepo.isPrivate ? 'PRIVATE' : 'PUBLIC');
              setTargetCredentialId(selectedRepo.credentialId);
            }
            setPickerTarget(null);
          }}
          title={pickerTarget === 'A' ? 'Select Source Repository (Origin)' : 'Select Destination Repository (Mirror)'}
          access={pickerTarget === 'B' ? 'PUSH' : 'PULL'}
        />
      )}
    </>
  );
};
