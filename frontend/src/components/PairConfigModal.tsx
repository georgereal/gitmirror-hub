import React, { useState, useEffect, useMemo } from 'react';
import {
  X, Save, CheckCircle2, AlertCircle, Check, Sparkles, Plus,
  HardDrive, Database, Search, Globe, GitFork, Layers, Lock, ArrowRight, Sparkle, AlertTriangle
} from 'lucide-react';
import { RepoMapping, SyncDirection, StorageTier, PermissionCheckReport, GitHubRepoOption, TrunkConflictPolicy, ScmCredential, ScmInstallationOption } from '../types';
import { testRepoConnection, createRemoteRepository, listScmCredentials, listScmInstallations } from '../services/api';
import { RepoPickerModal } from './RepoPickerModal';
import { CredentialPickModal } from './CredentialPickModal';
import { InfoTooltip } from './InfoTooltip';
import { findRepoCollision } from '../utils/repoUrl';
import { shouldWarnBidirectionalBackup } from '../utils/mirrorTopology';
import { useFeatureFlags } from '../hooks/useFeatureFlags';

const isGithubCloudUrl = (url: string) => (url || '').toLowerCase().includes('github.com');

const formatCredentialLabel = (c: ScmCredential | undefined, fallbackId?: number) => {
  if (!c) {
    return fallbackId != null ? `credential #${fallbackId}` : 'credential';
  }
  const kind = c.authMode === 'GITHUB_APP' ? 'App' : 'PAT';
  const account = c.accountLogin ? ` · ${c.accountLogin}` : '';
  return `${c.label}${account} (${kind})`;
};

const deriveRepoNameFromUrl = (url: string) => {
  try {
    const match = (url || '').trim().match(/\/([^\/]+?)(?:\.git)?$/);
    if (match && match[1]) return match[1];
  } catch {
    /* ignore */
  }
  return '';
};

/** Jackson+Lombok historically emitted {@code private} instead of {@code isPrivate}. */
const reportIsPrivate = (res: PermissionCheckReport | null | undefined) => {
  if (!res) return false;
  if (res.isPrivate === true) return true;
  return (res as { private?: boolean }).private === true;
};

const repoAccessDetailChecks = (checks?: string[]) =>
  (checks || []).filter(
    (c) =>
      !/Installation Verified|Authentication Verified|Authentication Token|Authentication Active|Connected with access to \d+ repositories/i.test(
        c
      )
  );

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
  const { flags } = useFeatureFlags();
  const publicReposEnabled = flags.publicReposEnabled;
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
  const [sourceInstallationId, setSourceInstallationId] = useState<string | undefined>(undefined);
  const [targetInstallationId, setTargetInstallationId] = useState<string | undefined>(undefined);
  const [credPickTarget, setCredPickTarget] = useState<'A' | 'B' | null>(null);
  /** Side confirmed via Add repo (auto access check). */
  const [sourceReady, setSourceReady] = useState(false);
  const [destReady, setDestReady] = useState(false);
  const [sourcePublicRead, setSourcePublicRead] = useState<boolean | undefined>(undefined);
  const [credentialCatalog, setCredentialCatalog] = useState<ScmCredential[]>([]);
  /** Explicit anonymous Access opt-in (never the silent default when an App is available). */
  const [sourceAccessAnonymous, setSourceAccessAnonymous] = useState(false);
  const [destAccessAnonymous, setDestAccessAnonymous] = useState(false);
  const [alsoPublicHintA, setAlsoPublicHintA] = useState(false);
  const [alsoPublicHintB, setAlsoPublicHintB] = useState(false);
  const [createDestName, setCreateDestName] = useState('');
  const [createDestOwner, setCreateDestOwner] = useState('');
  const [createNameTouched, setCreateNameTouched] = useState(false);
  const [createPanelOpen, setCreatePanelOpen] = useState(false);
  const [createInstallOptions, setCreateInstallOptions] = useState<ScmInstallationOption[]>([]);
  const [createOwnerLoading, setCreateOwnerLoading] = useState(false);

  useEffect(() => {
    if (!isOpen) return;
    void listScmCredentials()
      .then((rows) => setCredentialCatalog(rows.filter((r) => r.enabled)))
      .catch(() => setCredentialCatalog([]));
  }, [isOpen]);

  const githubCredentials = useMemo(
    () => credentialCatalog.filter((c) => c.provider === 'GITHUB' || c.provider === 'GITHUB_ENTERPRISE'),
    [credentialCatalog]
  );

  const sourceCredentialLabel = formatCredentialLabel(
    githubCredentials.find((c) => c.id === sourceCredentialId),
    sourceCredentialId
  );
  const destCredentialLabel = formatCredentialLabel(
    githubCredentials.find((c) => c.id === targetCredentialId),
    targetCredentialId
  );

  const destWriteRequired = syncDirection !== 'UNIDIRECTIONAL_B_TO_A';
  const destAllowsAnonymous = publicReposEnabled && !destWriteRequired;

  useEffect(() => {
    if (mapping) {
      setRepoAUrl(mapping.repoAUrl || '');
      setRepoBUrl(mapping.repoBUrl || '');
      setBranchPattern(mapping.branchPattern || '*');
      setSyncDirection(mapping.syncDirection || 'BIDIRECTIONAL');
      setTrunkConflictPolicy(mapping.trunkConflictPolicy || 'ISOLATE');
      setActive(mapping.active ?? true);
      setVisibilityA(
        !publicReposEnabled && mapping.sourceVisibility === 'PUBLIC'
          ? 'PRIVATE'
          : mapping.sourceVisibility || 'UNKNOWN'
      );
      setVisibilityB(
        !publicReposEnabled && mapping.targetVisibility === 'PUBLIC'
          ? 'PRIVATE'
          : mapping.targetVisibility || 'UNKNOWN'
      );
      setSourceCredentialId(mapping.sourceCredentialId ?? undefined);
      setTargetCredentialId(mapping.targetCredentialId ?? undefined);
      setSourceInstallationId(mapping.sourceInstallationId ?? undefined);
      setTargetInstallationId(mapping.targetInstallationId ?? undefined);
      setSourcePublicRead(mapping.sourcePublicRead ?? undefined);
      setSourceAccessAnonymous(
        publicReposEnabled
          && mapping.sourceCredentialId == null
          && mapping.sourceVisibility === 'PUBLIC'
      );
      setDestAccessAnonymous(false);
      setSourceReady(Boolean(mapping.repoAUrl));
      setDestReady(Boolean(mapping.repoBUrl));
      setAlsoPublicHintA(false);
      setAlsoPublicHintB(false);
      setCreateNameTouched(false);
      setCreateDestName(deriveRepoNameFromUrl(mapping.repoAUrl || '') || '');
      setCreateDestOwner('');
      setCreatePanelOpen(false);
      setCreateInstallOptions([]);
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
      setSourceInstallationId(undefined);
      setTargetInstallationId(undefined);
      setSourcePublicRead(undefined);
      setSourceAccessAnonymous(false);
      setDestAccessAnonymous(false);
      setSourceReady(false);
      setDestReady(false);
      setAlsoPublicHintA(false);
      setAlsoPublicHintB(false);
      setCreateNameTouched(false);
      setCreateDestName('');
      setCreateDestOwner('');
      setCreatePanelOpen(false);
      setCreateInstallOptions([]);
    }
    setReportA(null);
    setReportB(null);
    setSaveError(null);
    setCredPickTarget(null);
    setCreateFeedback(null);
  }, [mapping, isOpen, publicReposEnabled]);

  // Default Access to first GitHub credential when not using anonymous.
  useEffect(() => {
    if (!isOpen || githubCredentials.length === 0) return;
    if (!sourceAccessAnonymous && sourceCredentialId == null) {
      setSourceCredentialId(githubCredentials[0].id);
    }
    if (!destAccessAnonymous && targetCredentialId == null) {
      setTargetCredentialId(githubCredentials[0].id);
      if (!createDestOwner) {
        setCreateDestOwner(githubCredentials[0].accountLogin || '');
      }
    } else if (!createDestOwner && targetCredentialId != null) {
      const cred = githubCredentials.find((c) => c.id === targetCredentialId);
      if (cred?.accountLogin) setCreateDestOwner(cred.accountLogin);
    }
  }, [isOpen, githubCredentials, sourceAccessAnonymous, destAccessAnonymous, sourceCredentialId, targetCredentialId, createDestOwner]);

  // Prefill create-dest name from source unless the operator edited it.
  useEffect(() => {
    if (createNameTouched) return;
    const n = deriveRepoNameFromUrl(repoAUrl);
    if (n) setCreateDestName(n);
  }, [repoAUrl, createNameTouched]);

  /** Resolve create Owner from Access credential / App installations when the create panel is open. */
  useEffect(() => {
    if (!createPanelOpen || targetCredentialId == null || destAccessAnonymous) {
      setCreateInstallOptions([]);
      return;
    }
    const cred = githubCredentials.find((c) => c.id === targetCredentialId);
    if (cred?.authMode !== 'GITHUB_APP') {
      if (cred?.accountLogin) setCreateDestOwner(cred.accountLogin);
      setCreateInstallOptions([]);
      setCreateOwnerLoading(false);
      return;
    }
    let cancelled = false;
    setCreateOwnerLoading(true);
    void listScmInstallations(targetCredentialId)
      .then((installs) => {
        if (cancelled) return;
        setCreateInstallOptions(installs);
        const preferred =
          (createDestOwner
            ? installs.find((i) => i.accountLogin?.toLowerCase() === createDestOwner.toLowerCase())
            : undefined)
          || installs[0];
        if (preferred?.accountLogin) {
          setCreateDestOwner(preferred.accountLogin);
          if (preferred.installationId) setTargetInstallationId(preferred.installationId);
        } else if (cred?.accountLogin) {
          setCreateDestOwner(cred.accountLogin);
        }
      })
      .catch(() => {
        if (cancelled) return;
        setCreateInstallOptions([]);
        if (cred?.accountLogin) setCreateDestOwner(cred.accountLogin);
      })
      .finally(() => {
        if (!cancelled) setCreateOwnerLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // Intentionally omit createDestOwner — only re-resolve when panel/credential changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [createPanelOpen, targetCredentialId, destAccessAnonymous, githubCredentials]);

  const canOpenCreatePrivate = Boolean(deriveRepoNameFromUrl(repoAUrl) || createDestName.trim());

  /** Create-repo permission gate (App credentials only; PATs cannot be introspected). */
  const createCred = githubCredentials.find((c) => c.id === targetCredentialId);
  /** GitHub logins are case-insensitive — match the installation without relying on exact owner casing. */
  const selectedCreateInstall = createInstallOptions.find(
    (i) => i.accountLogin?.toLowerCase() === createDestOwner?.toLowerCase()
  );
  /** Server-derived: the selected App installation positively lacks Administration (write) — creation would 403. */
  const createPermissionBlocked =
    createCred?.authMode === 'GITHUB_APP' && selectedCreateInstall?.canCreateRepo === false;
  /** App is not installed for the chosen owner — creation could land under a different account. */
  const createOwnerInstallMissing =
    createCred?.authMode === 'GITHUB_APP'
    && createInstallOptions.length > 0
    && !!createDestOwner
    && !selectedCreateInstall;

  useEffect(() => {
    if (!publicReposEnabled) {
      setSourceAccessAnonymous(false);
      setDestAccessAnonymous(false);
    }
    if (!publicReposEnabled && visibilityA === 'PUBLIC') {
      setVisibilityA('PRIVATE');
      setSourcePublicRead(undefined);
      setSourceReady(false);
      setReportA(null);
    }
    if (!publicReposEnabled && visibilityB === 'PUBLIC') {
      setVisibilityB('PRIVATE');
      setDestReady(false);
      setReportB(null);
    }
  }, [publicReposEnabled, visibilityA, visibilityB]);

  useEffect(() => {
    if (destWriteRequired && destAccessAnonymous) {
      setDestAccessAnonymous(false);
    }
  }, [destWriteRequired, destAccessAnonymous]);

  const collisionA = useMemo(() => findRepoCollision(repoAUrl, existingMappings, mapping?.id), [repoAUrl, existingMappings, mapping?.id]);
  const collisionB = useMemo(() => findRepoCollision(repoBUrl, existingMappings, mapping?.id), [repoBUrl, existingMappings, mapping?.id]);
  const hasCollision = Boolean(collisionA || collisionB);

  if (!isOpen) return null;

  const deriveRepoName = (url: string) => deriveRepoNameFromUrl(url) || `repo-${Date.now()}`;

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

  const reportBannerClass = (report: PermissionCheckReport, treatAsAuthenticated = false) => {
    if (report.valid && report.accessMode === 'PUBLIC' && !treatAsAuthenticated) {
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

    if (flags.providerGitlabEnabled && !repoAUrl.includes('gitlab.com')) {
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
    if (flags.providerBitbucketEnabled && !repoAUrl.includes('bitbucket.org')) {
      suggestions.push({
        label: 'Bitbucket',
        url: `https://bitbucket.org/${owner}/${repo}.git`,
        icon: <Globe className="w-3 h-3 text-blue-500" />
      });
    }
    if (flags.providerOriginEnabled && !repoAUrl.includes('origin.cursor.com')) {
      suggestions.push({
        label: 'Cursor Origin',
        url: `https://origin.cursor.com/${repo}`,
        icon: <Lock className="w-3 h-3 text-purple-500" />
      });
    }

    return suggestions;
  };

  const applySourceReport = (res: PermissionCheckReport, usedCredentialId?: number) => {
    setReportA(res);
    if (!res.valid) {
      setSourceReady(false);
      setAlsoPublicHintA(false);
      return;
    }
    setSourceReady(true);

    if (reportIsPrivate(res)) {
      setVisibilityA('PRIVATE');
      setSourcePublicRead(false);
      setAlsoPublicHintA(false);
    } else if (publicReposEnabled) {
      setVisibilityA('PUBLIC');
      setSourcePublicRead(true);
      // App/PAT access on a public repo — keep Access; surface optional hint.
      setAlsoPublicHintA(usedCredentialId != null);
    } else {
      setVisibilityA('PRIVATE');
      setSourcePublicRead(false);
      setAlsoPublicHintA(false);
    }

    if (usedCredentialId != null) {
      setSourceCredentialId(usedCredentialId);
      setSourceAccessAnonymous(false);
    } else {
      setSourceCredentialId(undefined);
      setSourceInstallationId(undefined);
      setSourceAccessAnonymous(true);
    }
  };

  const applyDestReport = (res: PermissionCheckReport, usedCredentialId?: number) => {
    setReportB(res);
    if (!res.valid) {
      setDestReady(false);
      setAlsoPublicHintB(false);
      return;
    }
    setDestReady(true);

    if (reportIsPrivate(res)) {
      setVisibilityB('PRIVATE');
      setAlsoPublicHintB(false);
    } else if (publicReposEnabled) {
      setVisibilityB('PUBLIC');
      setAlsoPublicHintB(usedCredentialId != null);
    } else {
      setVisibilityB('PRIVATE');
      setAlsoPublicHintB(false);
    }

    if (usedCredentialId != null) {
      setTargetCredentialId(usedCredentialId);
      setDestAccessAnonymous(false);
    } else {
      setTargetCredentialId(undefined);
      setDestAccessAnonymous(true);
    }
  };

  const runSourceCheck = async (
    credentialId?: number,
    knownPrivate?: boolean,
    publishReport = true
  ) => {
    setTestingA(true);
    try {
      const res = await testRepoConnection({
        repoUrl: repoAUrl,
        requiredAccess: 'READ',
        knownPrivate: knownPrivate ?? visibilityA === 'PRIVATE',
        credentialId,
      });
      if (publishReport) applySourceReport(res, credentialId);
      return res;
    } catch (e: any) {
      const fail: PermissionCheckReport = {
        valid: false,
        repoFullName: repoAUrl,
        isPrivate: false,
        httpStatusCode: 500,
        message: e.message || 'Verification call failed',
        passedChecks: [],
        warnings: [],
        errors: [e.message || 'Connection error'],
      };
      if (publishReport) setReportA(fail);
      return fail;
    } finally {
      setTestingA(false);
    }
  };

  const runDestCheck = async (
    credentialId?: number,
    knownPrivate?: boolean,
    publishReport = true
  ) => {
    const writeRequired = syncDirection !== 'UNIDIRECTIONAL_B_TO_A';
    setTestingB(true);
    try {
      const res = await testRepoConnection({
        repoUrl: repoBUrl,
        requiredAccess: writeRequired ? 'WRITE' : 'READ',
        knownPrivate: knownPrivate ?? (visibilityB === 'PRIVATE' || writeRequired),
        credentialId,
      });
      if (publishReport) applyDestReport(res, credentialId);
      return res;
    } catch (e: any) {
      const fail: PermissionCheckReport = {
        valid: false,
        repoFullName: repoBUrl,
        isPrivate: false,
        httpStatusCode: 500,
        message: e.message || 'Verification call failed',
        passedChecks: [],
        warnings: [],
        errors: [e.message || 'Connection error'],
      };
      if (publishReport) setReportB(fail);
      return fail;
    } finally {
      setTestingB(false);
    }
  };

  /**
   * Add repo (source): uses Access choice only.
   * Credential selected → check with that App/PAT (even if repo is public).
   * Anonymous → only when explicitly opted in.
   */
  const handleAddSourceRepo = async () => {
    if (!repoAUrl) return;
    setReportA(null);
    setSourceReady(false);
    setAlsoPublicHintA(false);

    if (sourceAccessAnonymous && publicReposEnabled) {
      await runSourceCheck(undefined, false);
      return;
    }

    if (sourceCredentialId == null) {
      if (isGithubCloudUrl(repoAUrl)) {
        setCredPickTarget('A');
        return;
      }
      await runSourceCheck(undefined, visibilityA === 'PRIVATE');
      return;
    }

    await runSourceCheck(sourceCredentialId, visibilityA === 'PRIVATE');
  };

  /**
   * Add repo (destination): Access credential for write; anonymous only for B→A opt-in.
   */
  const handleAddDestRepo = async () => {
    if (!repoBUrl) return;
    setReportB(null);
    setDestReady(false);
    setAlsoPublicHintB(false);
    const writeRequired = syncDirection !== 'UNIDIRECTIONAL_B_TO_A';

    if (destAccessAnonymous && destAllowsAnonymous) {
      await runDestCheck(undefined, false);
      return;
    }

    if (targetCredentialId == null) {
      if (isGithubCloudUrl(repoBUrl) || writeRequired) {
        setCredPickTarget('B');
        return;
      }
      await runDestCheck(undefined, visibilityB === 'PRIVATE' || writeRequired);
      return;
    }

    await runDestCheck(targetCredentialId, visibilityB === 'PRIVATE' || writeRequired);
  };

  const handleCredentialPicked = async (credentialId: number) => {
    const target = credPickTarget;
    setCredPickTarget(null);
    if (target === 'A') {
      setSourceCredentialId(credentialId);
      setSourceAccessAnonymous(false);
      await runSourceCheck(credentialId, visibilityA === 'PRIVATE');
    } else if (target === 'B') {
      setTargetCredentialId(credentialId);
      setDestAccessAnonymous(false);
      const writeRequired = syncDirection !== 'UNIDIRECTIONAL_B_TO_A';
      const cred = githubCredentials.find((c) => c.id === credentialId);
      if (cred?.accountLogin) setCreateDestOwner(cred.accountLogin);
      if (repoBUrl) {
        await runDestCheck(credentialId, visibilityB === 'PRIVATE' || writeRequired);
      }
    }
  };

  const handleCreateRemoteRepoB = async () => {
    if (!targetCredentialId) {
      setCreateFeedback('Select a destination Access credential, then create the private repo.');
      return;
    }
    if (createPermissionBlocked) {
      setCreateFeedback(`GitHub App installation @${createDestOwner} lacks Administration (write) — repository creation would fail with 403. Grant it on the App, then retry.`);
      return;
    }
    const name = (createDestName || deriveRepoName(repoAUrl)).trim();
    const owner = createDestOwner.trim();
    const selectedInstall = createInstallOptions.find((i) => i.accountLogin === owner);
    const accountType =
      selectedInstall?.accountType
      || githubCredentials.find((c) => c.id === targetCredentialId)?.accountType;
    if (!name) {
      setCreateFeedback('Repository name is required to create a private destination.');
      return;
    }
    if (!owner) {
      setCreateFeedback('Access credential has no GitHub account — check Settings installs, then try again.');
      return;
    }
    const cloneUrl = `https://github.com/${owner}/${name}.git`;
    setCreatingRepoB(true);
    setCreateFeedback(null);
    try {
      const created = await createRemoteRepository({
        repoUrl: cloneUrl,
        isPrivate: true,
        credentialId: targetCredentialId,
        owner,
        name,
        accountType,
      });
      const finalUrl = created.cloneUrl || cloneUrl;
      setRepoBUrl(finalUrl);
      setVisibilityB('PRIVATE');
      setDestAccessAnonymous(false);
      setCreateFeedback(`Successfully created ${created.fullName || `${owner}/${name}`} (private).`);
      setCreatePanelOpen(false);
      setTestingB(true);
      try {
        const res = await testRepoConnection({
          repoUrl: finalUrl,
          requiredAccess: 'WRITE',
          knownPrivate: true,
          credentialId: targetCredentialId,
        });
        applyDestReport(res, targetCredentialId);
      } finally {
        setTestingB(false);
      }
    } catch (e: any) {
      setCreateFeedback(`Failed to create repository: ${e.response?.data?.error || e.response?.data?.message || e.message}`);
    } finally {
      setCreatingRepoB(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (hasCollision) return;
    if (!sourceReady || (reportA != null && !reportA.valid)) {
      setSaveError('Add the source repository first (verifies public or credential access).');
      return;
    }
    if (!destReady || (reportB != null && !reportB.valid)) {
      setSaveError('Add the destination repository first (verifies write or public read access).');
      return;
    }
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
        sourceCredentialId: sourceAccessAnonymous ? null : sourceCredentialId ?? null,
        targetCredentialId: destAccessAnonymous ? null : targetCredentialId ?? null,
        sourceInstallationId: sourceAccessAnonymous ? null : sourceInstallationId ?? null,
        targetInstallationId: destAccessAnonymous ? null : targetInstallationId ?? null,
        sourcePublicRead: visibilityA === 'PUBLIC' ? true : sourcePublicRead ?? null,
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
                  <span className="font-semibold text-zinc-800">Source Repository</span>
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
                    setSourceReady(false);
                    setSourcePublicRead(undefined);
                    setSourceInstallationId(undefined);
                    setAlsoPublicHintA(false);
                  }}
                  placeholder="https://github.com/owner/source-repo.git or gitlab.com/..."
                  className="flex-1 bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                  required
                />
                <button
                  type="button"
                  onClick={handleAddSourceRepo}
                  disabled={testingA || !repoAUrl}
                  className="flex items-center space-x-1.5 bg-zinc-900 hover:bg-zinc-800 text-white px-3 py-2 rounded-lg font-medium transition-colors text-xs shrink-0 disabled:opacity-40"
                >
                  <Plus className={`w-3.5 h-3.5 ${testingA ? 'animate-pulse' : ''}`} />
                  <span>{testingA ? 'Adding...' : sourceReady && reportA?.valid ? 'Re-check' : 'Add repo'}</span>
                </button>
              </div>
              {sourceReady && reportA?.valid && (
                <div className="space-y-0.5 text-[10px]">
                  <p className="text-emerald-700 font-medium">
                    Added
                    {` · Visibility ${visibilityA === 'PUBLIC' ? 'Public' : visibilityA === 'PRIVATE' ? 'Private' : 'Auto'}`}
                    {sourceAccessAnonymous
                      ? ' · Access anonymous HTTPS'
                      : sourceCredentialId != null
                        ? ` · Access via ${sourceCredentialLabel}`
                        : ' · Access authenticated'}
                  </p>
                  {alsoPublicHintA && (
                    <p className="text-sky-700">
                      Also publicly readable over anonymous HTTPS — Access stays on your App/PAT.
                    </p>
                  )}
                </div>
              )}
              <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-[10px]">
                <span className="text-zinc-500 font-medium">Visibility</span>
                {((publicReposEnabled
                  ? (['UNKNOWN', 'PUBLIC', 'PRIVATE'] as const)
                  : (['UNKNOWN', 'PRIVATE'] as const)
                )).map((v) => (
                  <button
                    key={v}
                    type="button"
                    onClick={() => {
                      setVisibilityA(v);
                      setSourceReady(false);
                      setReportA(null);
                      setAlsoPublicHintA(false);
                      if (v === 'PUBLIC' && publicReposEnabled) {
                        setSourcePublicRead(true);
                      } else if (v === 'PRIVATE') {
                        setSourcePublicRead(false);
                      }
                    }}
                    className={`px-2 py-0.5 rounded-md border ${
                      visibilityA === v ? 'bg-zinc-900 text-white border-zinc-900' : 'bg-white text-zinc-600 border-zinc-200'
                    }`}
                  >
                    {v === 'UNKNOWN' ? 'Auto' : v === 'PUBLIC' ? 'Public' : 'Private'}
                  </button>
                ))}
              </div>
              <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-[10px]">
                <span className="text-zinc-500 font-medium">Access</span>
                {!sourceAccessAnonymous && githubCredentials.length <= 1 ? (
                  <span className="text-zinc-800 font-medium">
                    {githubCredentials.length === 0
                      ? 'No GitHub credentials — add in Settings'
                      : sourceCredentialLabel}
                  </span>
                ) : !sourceAccessAnonymous ? (
                  <select
                    value={String(sourceCredentialId ?? '')}
                    onChange={(e) => {
                      const v = e.target.value;
                      setSourceReady(false);
                      setReportA(null);
                      setAlsoPublicHintA(false);
                      if (v === '') return;
                      setSourceAccessAnonymous(false);
                      setSourceCredentialId(Number(v));
                      const cred = githubCredentials.find((c) => c.id === Number(v));
                      if (cred?.authMode !== 'GITHUB_APP') setSourceInstallationId(undefined);
                    }}
                    className="max-w-[220px] bg-white border border-zinc-200 rounded-md px-2 py-0.5 text-zinc-800"
                  >
                    {githubCredentials.map((c) => (
                      <option key={c.id} value={c.id}>
                        {formatCredentialLabel(c)}
                      </option>
                    ))}
                  </select>
                ) : (
                  <span className="text-sky-800 font-medium">Anonymous HTTPS</span>
                )}
                {publicReposEnabled && (
                  <button
                    type="button"
                    onClick={() => {
                      const next = !sourceAccessAnonymous;
                      setSourceAccessAnonymous(next);
                      setSourceReady(false);
                      setReportA(null);
                      setAlsoPublicHintA(false);
                      if (next) {
                        setSourceCredentialId(undefined);
                        setSourceInstallationId(undefined);
                        setSourcePublicRead(true);
                        if (visibilityA === 'UNKNOWN') setVisibilityA('PUBLIC');
                      } else if (githubCredentials[0]) {
                        setSourceCredentialId(githubCredentials[0].id);
                      }
                    }}
                    className={`px-2 py-0.5 rounded-md border ${
                      sourceAccessAnonymous
                        ? 'bg-sky-700 text-white border-sky-700'
                        : 'bg-white text-zinc-600 border-zinc-200'
                    }`}
                  >
                    Anonymous
                  </button>
                )}
                {githubCredentials.length >= 2 && !sourceAccessAnonymous && (
                  <span className="text-zinc-400">Which Settings credential this side uses</span>
                )}
                {sourceAccessAnonymous && (
                  <span className="text-zinc-400">Anonymous HTTPS (explicit)</span>
                )}
              </div>

              {reportA && (
                <div className="space-y-1.5">
                  <div className={`p-2 rounded-lg border text-[11px] flex items-center space-x-1.5 ${reportBannerClass(reportA, !sourceAccessAnonymous)}`}>
                    {reportA.valid ? (
                      <Check className={`w-3.5 h-3.5 shrink-0 ${sourceAccessAnonymous && reportA.accessMode === 'PUBLIC' ? 'text-sky-600' : 'text-emerald-600'}`} />
                    ) : (
                      <AlertCircle className="w-3.5 h-3.5 text-rose-600 shrink-0" />
                    )}
                    <span className="font-medium">
                      {!sourceAccessAnonymous && reportA.valid && reportA.accessMode === 'PUBLIC'
                        ? 'Repository access verified via App/PAT.'
                        : reportA.message}
                    </span>
                  </div>
                  {reportA.valid && repoAccessDetailChecks(reportA.passedChecks).length > 0 && (
                    <details className="text-[10px] text-zinc-500">
                      <summary className="cursor-pointer select-none hover:text-zinc-700">Show details</summary>
                      <ul className="mt-1 space-y-0.5 pl-3 list-disc">
                        {repoAccessDetailChecks(reportA.passedChecks).map((check, idx) => (
                          <li key={idx}>{check}</li>
                        ))}
                      </ul>
                    </details>
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
                    <details className="text-[10px] text-amber-800">
                      <summary className="cursor-pointer select-none">Show warnings</summary>
                      <ul className="mt-1 space-y-0.5 pl-3 list-disc">
                        {reportA.warnings.map((warn, idx) => (
                          <li key={idx}>{warn}</li>
                        ))}
                      </ul>
                    </details>
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
                  <span className="font-semibold text-zinc-800">Destination Repository</span>
                  {providerB && (
                    <span className={`inline-flex items-center space-x-1 px-2 py-0.5 rounded-full text-[10px] font-medium border ${providerB.color}`}>
                      {providerB.icon}
                      <span>{providerB.provider}</span>
                    </span>
                  )}
                </div>

                <div className="flex items-center gap-1.5">
                  <button
                    type="button"
                    onClick={() => setPickerTarget('B')}
                    className="flex items-center space-x-1 text-zinc-600 hover:text-zinc-900 font-medium text-[11px] bg-white hover:bg-zinc-100 border border-zinc-200 px-2.5 py-1 rounded-md shadow-xs transition-colors"
                  >
                    <Search className="w-3 h-3 text-zinc-500" />
                    <span>Browse Repos...</span>
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      if (!canOpenCreatePrivate) return;
                      setDestAccessAnonymous(false);
                      if (targetCredentialId == null && githubCredentials[0]) {
                        setTargetCredentialId(githubCredentials[0].id);
                        if (githubCredentials[0].accountLogin) {
                          setCreateDestOwner(githubCredentials[0].accountLogin);
                        }
                      }
                      setCreateFeedback(null);
                      setCreatePanelOpen(true);
                    }}
                    disabled={!canOpenCreatePrivate || destAccessAnonymous}
                    title={
                      !canOpenCreatePrivate
                        ? 'Add a source repository first (name defaults from source)'
                        : destAccessAnonymous
                          ? 'Switch Access off Anonymous to create under an App/PAT'
                          : 'Create a new private GitHub repo under Access'
                    }
                    className="flex items-center space-x-1 text-zinc-600 hover:text-zinc-900 font-medium text-[11px] bg-white hover:bg-zinc-100 border border-zinc-200 px-2.5 py-1 rounded-md shadow-xs transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    <Sparkles className="w-3 h-3 text-zinc-500" />
                    <span>Create private…</span>
                  </button>
                </div>
              </div>

              {createPanelOpen && !destAccessAnonymous && (
                <div className="p-2.5 bg-white border border-zinc-200 rounded-lg space-y-2">
                  <div className="flex items-center justify-between gap-2">
                    <p className="font-medium text-[11px] text-zinc-800">Create private destination</p>
                    <button
                      type="button"
                      onClick={() => {
                        setCreatePanelOpen(false);
                        setCreateFeedback(null);
                      }}
                      className="text-[10px] text-zinc-500 hover:text-zinc-800"
                    >
                      Cancel
                    </button>
                  </div>

                  <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-[10px]">
                    <span className="text-zinc-500 font-medium">Credential</span>
                    {githubCredentials.length === 0 ? (
                      <span className="text-amber-700">No GitHub credentials — add in Settings</span>
                    ) : (
                      <select
                        value={String(targetCredentialId ?? '')}
                        onChange={(e) => {
                          const v = e.target.value;
                          if (v === '') return;
                          setTargetCredentialId(Number(v));
                          setDestAccessAnonymous(false);
                          const cred = githubCredentials.find((c) => c.id === Number(v));
                          if (cred?.accountLogin) {
                            setCreateDestOwner(cred.accountLogin);
                          } else {
                            setCreateDestOwner('');
                          }
                          setCreateInstallOptions([]);
                          if (cred?.authMode !== 'GITHUB_APP') setTargetInstallationId(undefined);
                          setCreateFeedback(null);
                        }}
                        className="min-w-[180px] max-w-full flex-1 bg-zinc-50 border border-zinc-200 rounded-md px-2 py-1 text-zinc-800"
                      >
                        {githubCredentials.map((c) => (
                          <option key={c.id} value={c.id}>
                            {formatCredentialLabel(c)}
                          </option>
                        ))}
                      </select>
                    )}
                  </div>

                  <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-[10px]">
                    <span className="text-zinc-500 font-medium">Owner</span>
                    {createOwnerLoading ? (
                      <span className="text-zinc-400">Resolving account…</span>
                    ) : createInstallOptions.length > 0 ? (
                      <select
                        value={createDestOwner}
                        onChange={(e) => {
                          const login = e.target.value;
                          setCreateDestOwner(login);
                          const inst = createInstallOptions.find((i) => i.accountLogin === login);
                          if (inst?.installationId) setTargetInstallationId(inst.installationId);
                          setCreateFeedback(null);
                        }}
                        className="min-w-[140px] max-w-[220px] bg-zinc-50 border border-zinc-200 rounded-md px-2 py-1 text-zinc-800"
                      >
                        {createInstallOptions.map((i) => (
                          <option key={i.installationId || i.accountLogin} value={i.accountLogin || ''}>
                            @{i.accountLogin || 'unknown'}
                            {i.accountType ? ` · ${i.accountType}` : ''}
                            {i.canCreateRepo === false ? ' · needs Administration' : ''}
                          </option>
                        ))}
                      </select>
                    ) : createDestOwner ? (
                      <span className="text-zinc-800 font-medium font-mono">under @{createDestOwner}</span>
                    ) : (
                      <span className="text-amber-700">No GitHub account on credential — check Settings installs</span>
                    )}
                  </div>

                  <label className="block space-y-0.5">
                    <span className="text-[10px] text-zinc-500 font-medium">Name</span>
                    <input
                      type="text"
                      value={createDestName}
                      onChange={(e) => {
                        setCreateNameTouched(true);
                        setCreateDestName(e.target.value);
                        setCreateFeedback(null);
                      }}
                      placeholder="repo-name"
                      className="w-full bg-zinc-50 border border-zinc-200 rounded-md px-2 py-1 text-zinc-900 font-mono text-[11px] focus:outline-none focus:border-zinc-400"
                    />
                    <span className="text-[10px] text-zinc-400">Defaults to source name — edit to rename.</span>
                  </label>

                  <p className="text-[10px] text-zinc-500 font-mono">
                    {createDestOwner && createDestName.trim()
                      ? `github.com/${createDestOwner}/${createDestName.trim()} · Private`
                      : 'github.com/{owner}/{name} · Private'}
                  </p>

                  {createPermissionBlocked ? (
                    <div className="p-2 rounded-lg border text-[11px] bg-rose-50 border-rose-200 text-rose-800 space-y-0.5">
                      <p className="font-medium">Cannot create — the App installation for @{createDestOwner} lacks Administration (write) access.</p>
                      <p className="text-rose-700">
                        On GitHub: App → Permissions → Repository permissions → set{' '}
                        <span className="font-mono">Administration: Read and write</span>, accept the pending permission
                        change on the installation, then retry.
                      </p>
                    </div>
                  ) : createOwnerInstallMissing ? (
                    <div className="p-2 rounded-lg border text-[11px] bg-amber-50 border-amber-200 text-amber-800">
                      The GitHub App may not be installed on @{createDestOwner} — the repository could be created under a
                      different account. Install the App on this account first.
                    </div>
                  ) : null}

                  <div className="flex items-center justify-end gap-2">
                    <button
                      type="button"
                      onClick={handleCreateRemoteRepoB}
                      disabled={creatingRepoB || !targetCredentialId || !createDestOwner || !createDestName.trim() || createPermissionBlocked}
                      className="inline-flex items-center space-x-1 px-2.5 py-1.5 rounded-md bg-zinc-900 hover:bg-zinc-800 text-white font-medium text-[11px] transition-colors disabled:opacity-40"
                    >
                      <Sparkles className={`w-3 h-3 ${creatingRepoB ? 'animate-spin' : ''}`} />
                      <span>{creatingRepoB ? 'Creating...' : 'Create'}</span>
                    </button>
                  </div>

                  {createFeedback && !createFeedback.includes('Successfully') && (
                    <div className="p-2 rounded-lg border text-[11px] bg-rose-50 border-rose-200 text-rose-800">
                      {createFeedback}
                    </div>
                  )}
                </div>
              )}

              {createFeedback && createFeedback.includes('Successfully') && !createPanelOpen && (
                <div className="p-2 rounded-lg border text-[11px] bg-emerald-50 border-emerald-200 text-emerald-800">
                  {createFeedback}
                </div>
              )}

              <div className="flex items-center space-x-2">
                <input
                  type="text"
                  value={repoBUrl}
                  onChange={(e) => {
                    setRepoBUrl(e.target.value);
                    setReportB(null);
                    setDestReady(false);
                    setTargetInstallationId(undefined);
                    setAlsoPublicHintB(false);
                  }}
                  placeholder="https://gitlab.com/owner/mirror-repo.git or github.com/..."
                  className="flex-1 bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                  required
                />
                <button
                  type="button"
                  onClick={handleAddDestRepo}
                  disabled={testingB || !repoBUrl}
                  className="flex items-center space-x-1.5 bg-zinc-900 hover:bg-zinc-800 text-white px-3 py-2 rounded-lg font-medium transition-colors text-xs shrink-0 disabled:opacity-40"
                >
                  <Plus className={`w-3.5 h-3.5 ${testingB ? 'animate-pulse' : ''}`} />
                  <span>{testingB ? 'Adding...' : destReady && reportB?.valid ? 'Re-check' : 'Add repo'}</span>
                </button>
              </div>
              {destReady && reportB?.valid && (
                <div className="space-y-0.5 text-[10px]">
                  <p className="text-emerald-700 font-medium">
                    Added
                    {` · Visibility ${visibilityB === 'PUBLIC' ? 'Public' : visibilityB === 'PRIVATE' ? 'Private' : 'Auto'}`}
                    {destAccessAnonymous
                      ? ' · Access anonymous HTTPS'
                      : targetCredentialId != null
                        ? ` · Access via ${destCredentialLabel}`
                        : ' · Access authenticated'}
                  </p>
                  {alsoPublicHintB && (
                    <p className="text-sky-700">
                      Also publicly readable over anonymous HTTPS — Access stays on your App/PAT.
                    </p>
                  )}
                </div>
              )}
              <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-[10px]">
                <span className="text-zinc-500 font-medium">Visibility</span>
                {((publicReposEnabled
                  ? (['UNKNOWN', 'PUBLIC', 'PRIVATE'] as const)
                  : (['UNKNOWN', 'PRIVATE'] as const)
                )).map((v) => (
                  <button
                    key={v}
                    type="button"
                    onClick={() => {
                      setVisibilityB(v);
                      setDestReady(false);
                      setReportB(null);
                      setAlsoPublicHintB(false);
                    }}
                    className={`px-2 py-0.5 rounded-md border ${
                      visibilityB === v ? 'bg-zinc-900 text-white border-zinc-900' : 'bg-white text-zinc-600 border-zinc-200'
                    }`}
                  >
                    {v === 'UNKNOWN' ? 'Auto' : v === 'PUBLIC' ? 'Public' : 'Private'}
                  </button>
                ))}
              </div>
              <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-[10px]">
                <span className="text-zinc-500 font-medium">Access</span>
                {!destAccessAnonymous && githubCredentials.length <= 1 ? (
                  <span className="text-zinc-800 font-medium">
                    {githubCredentials.length === 0
                      ? 'No GitHub credentials — add in Settings'
                      : destCredentialLabel}
                  </span>
                ) : !destAccessAnonymous ? (
                  <select
                    value={String(targetCredentialId ?? '')}
                    onChange={(e) => {
                      const v = e.target.value;
                      setDestReady(false);
                      setReportB(null);
                      setAlsoPublicHintB(false);
                      if (v === '') return;
                      setDestAccessAnonymous(false);
                      setTargetCredentialId(Number(v));
                      const cred = githubCredentials.find((c) => c.id === Number(v));
                      if (cred?.accountLogin) setCreateDestOwner(cred.accountLogin);
                      if (cred?.authMode !== 'GITHUB_APP') setTargetInstallationId(undefined);
                    }}
                    className="max-w-[220px] bg-white border border-zinc-200 rounded-md px-2 py-0.5 text-zinc-800"
                  >
                    {githubCredentials.map((c) => (
                      <option key={c.id} value={c.id}>
                        {formatCredentialLabel(c)}
                      </option>
                    ))}
                  </select>
                ) : (
                  <span className="text-sky-800 font-medium">Anonymous HTTPS</span>
                )}
                {destAllowsAnonymous && (
                  <button
                    type="button"
                    onClick={() => {
                      const next = !destAccessAnonymous;
                      setDestAccessAnonymous(next);
                      setDestReady(false);
                      setReportB(null);
                      setAlsoPublicHintB(false);
                      if (next) {
                        setTargetCredentialId(undefined);
                        setTargetInstallationId(undefined);
                        if (visibilityB === 'UNKNOWN') setVisibilityB('PUBLIC');
                      } else if (githubCredentials[0]) {
                        setTargetCredentialId(githubCredentials[0].id);
                        if (githubCredentials[0].accountLogin) {
                          setCreateDestOwner(githubCredentials[0].accountLogin);
                        }
                      }
                    }}
                    className={`px-2 py-0.5 rounded-md border ${
                      destAccessAnonymous
                        ? 'bg-sky-700 text-white border-sky-700'
                        : 'bg-white text-zinc-600 border-zinc-200'
                    }`}
                  >
                    Anonymous
                  </button>
                )}
                {destWriteRequired && !destAccessAnonymous && (
                  <span className="text-zinc-400">Write needs App/PAT</span>
                )}
                {githubCredentials.length >= 2 && !destAccessAnonymous && !destWriteRequired && (
                  <span className="text-zinc-400">Which Settings credential this side uses</span>
                )}
                {destAccessAnonymous && (
                  <span className="text-zinc-400">Anonymous HTTPS (B→A read)</span>
                )}
              </div>

              {reportB && (
                <div className="space-y-2">
                  <div className={`p-2 rounded-lg border text-[11px] flex items-center space-x-1.5 ${reportBannerClass(reportB, !destAccessAnonymous)}`}>
                    {reportB.valid ? (
                      <Check className={`w-3.5 h-3.5 shrink-0 ${destAccessAnonymous && reportB.accessMode === 'PUBLIC' ? 'text-sky-600' : 'text-emerald-600'}`} />
                    ) : (
                      <AlertCircle className="w-3.5 h-3.5 text-rose-600 shrink-0" />
                    )}
                    <span className="font-medium">
                      {!destAccessAnonymous && reportB.valid && reportB.accessMode === 'PUBLIC'
                        ? 'Repository access verified via App/PAT.'
                        : reportB.message}
                    </span>
                  </div>

                  {reportB.valid && repoAccessDetailChecks(reportB.passedChecks).length > 0 && (
                    <details className="text-[10px] text-zinc-500">
                      <summary className="cursor-pointer select-none hover:text-zinc-700">Show details</summary>
                      <ul className="mt-1 space-y-0.5 pl-3 list-disc">
                        {repoAccessDetailChecks(reportB.passedChecks).map((check, idx) => (
                          <li key={idx}>{check}</li>
                        ))}
                      </ul>
                    </details>
                  )}

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
                    <details className="text-[10px] text-amber-800">
                      <summary className="cursor-pointer select-none">Show warnings</summary>
                      <ul className="mt-1 space-y-0.5 pl-3 list-disc">
                        {reportB.warnings.map((warn, idx) => (
                          <li key={idx}>{warn}</li>
                        ))}
                      </ul>
                    </details>
                  )}

                  {!reportB.valid && (reportB.httpStatusCode === 404 || reportB.message.toLowerCase().includes('not found')) && (
                    <p className="text-[10px] text-zinc-500 px-0.5">
                      Repo not found — use <span className="font-medium text-zinc-700">Create private…</span> beside Browse,
                      or fix the URL and re-check.
                    </p>
                  )}
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
                disabled={saving || !repoAUrl || !repoBUrl || hasCollision || !sourceReady || !destReady}
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
              setSourceReady(false);
              setAlsoPublicHintA(false);
              setVisibilityA(selectedRepo.isPrivate || !publicReposEnabled ? 'PRIVATE' : 'PUBLIC');
              // Browse always binds the picker credential — Visibility ≠ Access.
              setSourceCredentialId(selectedRepo.credentialId);
              setSourceAccessAnonymous(false);
              setSourceInstallationId(selectedRepo.installationId);
              setSourcePublicRead(publicReposEnabled && !selectedRepo.isPrivate);
              setPickerTarget(null);
              void (async () => {
                setTestingA(true);
                try {
                  const res = await testRepoConnection({
                    repoUrl: selectedRepo.cloneUrl,
                    requiredAccess: 'READ',
                    knownPrivate: !!selectedRepo.isPrivate,
                    credentialId: selectedRepo.credentialId,
                  });
                  applySourceReport(res, selectedRepo.credentialId);
                  if (res.valid && selectedRepo.installationId) {
                    setSourceInstallationId(selectedRepo.installationId);
                  }
                } catch (e: any) {
                  setReportA({
                    valid: false,
                    repoFullName: selectedRepo.cloneUrl,
                    isPrivate: !!selectedRepo.isPrivate,
                    httpStatusCode: 500,
                    message: e.message || 'Add repo failed',
                    passedChecks: [],
                    warnings: [],
                    errors: [e.message || 'Connection error'],
                  });
                  setSourceReady(false);
                } finally {
                  setTestingA(false);
                }
              })();
            } else {
              setRepoBUrl(selectedRepo.cloneUrl);
              setReportB(null);
              setDestReady(false);
              setAlsoPublicHintB(false);
              setVisibilityB(
                selectedRepo.isPrivate || !publicReposEnabled ? 'PRIVATE' : 'PUBLIC'
              );
              setTargetCredentialId(selectedRepo.credentialId);
              setDestAccessAnonymous(false);
              setTargetInstallationId(selectedRepo.installationId);
              const browsedCred = githubCredentials.find((c) => c.id === selectedRepo.credentialId);
              if (browsedCred?.accountLogin) setCreateDestOwner(browsedCred.accountLogin);
              setPickerTarget(null);
              void (async () => {
                const writeRequired = syncDirection !== 'UNIDIRECTIONAL_B_TO_A';
                setTestingB(true);
                try {
                  const res = await testRepoConnection({
                    repoUrl: selectedRepo.cloneUrl,
                    requiredAccess: writeRequired ? 'WRITE' : 'READ',
                    knownPrivate: !!selectedRepo.isPrivate || writeRequired,
                    credentialId: selectedRepo.credentialId,
                  });
                  applyDestReport(res, selectedRepo.credentialId);
                } catch (e: any) {
                  setReportB({
                    valid: false,
                    repoFullName: selectedRepo.cloneUrl,
                    isPrivate: !!selectedRepo.isPrivate,
                    httpStatusCode: 500,
                    message: e.message || 'Add repo failed',
                    passedChecks: [],
                    warnings: [],
                    errors: [e.message || 'Connection error'],
                  });
                  setDestReady(false);
                } finally {
                  setTestingB(false);
                }
              })();
            }
          }}
          title={pickerTarget === 'A' ? 'Select Source Repository' : 'Select Destination Repository'}
          access={pickerTarget === 'B' ? 'PUSH' : 'PULL'}
        />
      )}

      <CredentialPickModal
        isOpen={credPickTarget != null}
        title={credPickTarget === 'B' ? 'Destination GitHub credential' : 'Source GitHub credential'}
        reason={
          credPickTarget === 'B'
            ? 'Write or private destination access needs a GitHub App or PAT.'
            : visibilityA === 'PRIVATE'
              ? 'Private source selected — choose a GitHub App or PAT to verify access.'
              : 'Public access failed. Choose a GitHub App or PAT to continue Add repo.'
        }
        initialCredentialId={credPickTarget === 'B' ? targetCredentialId : sourceCredentialId}
        onCancel={() => setCredPickTarget(null)}
        onConfirm={handleCredentialPicked}
      />
    </>
  );
};
