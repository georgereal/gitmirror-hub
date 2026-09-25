import React, { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { Layers, GitFork, Globe, Lock, Server, Loader2, X, AlertTriangle, Check, ListChecks } from 'lucide-react';
import { RepoMapping, GitHubRepoOption, ScmCredential, ScmInstallationOption, TrunkConflictPolicy, SyncDirection, StorageTier } from '../types';
import { submitBulkMigration, probeRepoHasCommits, probeRepoExists, listScmInstallations, BulkMirrorRequest, BulkMirrorResponse, BulkMirrorRow } from '../services/api';
import { RepoPickerModal } from './RepoPickerModal';
import { InfoTooltip } from './InfoTooltip';
import { useMessagingModule } from '../hooks/useMessagingModule';
import { MessagingModuleInfo } from '../types';
import { normalizeRepoKey } from '../utils/repoUrl';
import { visibilityFromRepo, type RepoVisibilityState } from '../utils/repoVisibility';

/** Derive the repo name from a clone URL (last path segment, no .git). */
const deriveRepoName = (url: string): string => {
  try {
    const match = (url || '').trim().replace(/\/+$/, '').replace(/\.git$/, '').match(/\/([^/]+)$/);
    if (match && match[1]) return match[1];
  } catch {
    /* ignore */
  }
  return '';
};

interface BulkMigrationTabProps {
  existingMappings: RepoMapping[];
  githubCredentials: ScmCredential[];
  publicReposEnabled: boolean;
  onClose: () => void;
  onSubmitted: (response: BulkMirrorResponse) => Promise<void>;
}

type DestStrategy = 'BULK_CREATE' | 'SELECTIVE';

interface RowState {
  repo: GitHubRepoOption;
  destName: string;
  /** Selective mode: create a new repo, or map an existing one. */
  destKind: 'CREATE' | 'MAP';
  /** Option 2 / selective map: chosen existing destination repo. */
  destRepo?: GitHubRepoOption;
  /** Create rows: destination name is already taken on the host. */
  nameTaken?: boolean;
  /** Option 2: server probe result — destination already has commits. */
  destHasCommits?: boolean;
  probingDest?: boolean;
  /** Option 2 operator decision on a non-empty destination (submitted as includeNonEmptyDest). */
  includeNonEmptyDest: boolean;
  excluded: boolean;
}

/** How many bulk jobs can run at once on the active messaging module. */
function parallelismHint(messaging: MessagingModuleInfo): { label: string; raise: string } {
  if (messaging.provider === 'none') {
    const threads = messaging.workerThreads ?? 8;
    return {
      label: `In-process threads · ${threads}`,
      raise: 'Raise GIT_MESSAGING_NONE_WORKER_THREADS and restart.',
    };
  }
  if (messaging.provider === 'kafka') {
    return {
      label: 'Kafka lane · not implemented',
      raise: 'Kafka is reserved. Hub is still on RabbitMQ or in-process execution.',
    };
  }
  const consumers = messaging.laneMaxConcurrency ?? 5;
  return {
    label: `Rabbit lane · up to ${consumers} consumers`,
    raise: 'Raise spring.rabbitmq.listener.simple.max-concurrency and restart.',
  };
}

const renderProviderIcon = (p?: string) => {
  switch (p?.toUpperCase()) {
    case 'GITLAB':
      return <Layers className="w-4 h-4 text-orange-500 shrink-0" />;
    case 'BITBUCKET':
      return <Globe className="w-4 h-4 text-blue-500 shrink-0" />;
    case 'ORIGIN':
      return <Lock className="w-4 h-4 text-purple-500 shrink-0" />;
    case 'GHES':
    case 'GITHUB_ENTERPRISE':
      return <Server className="w-4 h-4 text-indigo-500 shrink-0" />;
    default:
      return <GitFork className="w-4 h-4 text-zinc-900 shrink-0" />;
  }
};

export const BulkMigrationTab: React.FC<BulkMigrationTabProps> = ({
  existingMappings,
  githubCredentials,
  publicReposEnabled,
  onClose,
  onSubmitted,
}) => {
  const { messaging } = useMessagingModule();
  const parallelism = parallelismHint(messaging);
  const [strategy, setStrategy] = useState<DestStrategy>('BULK_CREATE');
  const [rows, setRows] = useState<RowState[]>([]);
  const [pickerOpen, setPickerOpen] = useState<null | 'SOURCE' | 'DEST'>(null);
  const [mapRow, setMapRow] = useState<number | null>(null);
  const [destCredentialId, setDestCredentialId] = useState<string | ''>('');
  const [destOwner, setDestOwner] = useState('');
  const [createVisibility, setCreateVisibility] = useState<'private' | 'public' | 'internal'>('private');
  const [destInstallations, setDestInstallations] = useState<ScmInstallationOption[]>([]);
  const [destInstallationsLoading, setDestInstallationsLoading] = useState(false);
  const [destInstallationId, setDestInstallationId] = useState<string | undefined>(undefined);
  const [branchPattern, setBranchPattern] = useState('*');
  const [syncDirection, setSyncDirection] = useState<SyncDirection>('BIDIRECTIONAL');
  const [trunkConflictPolicy, setTrunkConflictPolicy] = useState<TrunkConflictPolicy>('ISOLATE');
  const [storageTier, setStorageTier] = useState<StorageTier>('AUTO_LRU');
  const [active, setActive] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [result, setResult] = useState<BulkMirrorResponse | null>(null);
  // Resolve the destination Owner like the single-pair create panel: App credentials pick the
  // preferred installation's account login (installations carry the real owner/org); PAT
  // credentials populate from the credential's own login. The input stays editable.
  React.useEffect(() => {
    if (destCredentialId === '') {
      setDestInstallations([]);
      setDestInstallationId(undefined);
      return;
    }
    const cred = githubCredentials.find((c) => c.id === destCredentialId);
    let cancelled = false;
    if (cred?.authMode !== 'GITHUB_APP') {
      if (cred?.accountLogin) setDestOwner(cred.accountLogin);
      setDestInstallations([]);
      setDestInstallationId(undefined);
      return;
    }
    setDestInstallationsLoading(true);
    void listScmInstallations(destCredentialId)
      .then((installed) => {
        if (cancelled) return;
        const rows = installed || [];
        setDestInstallations(rows);
        // Case-insensitive login match (GitHub logins), else the first installation.
        const preferred = (destOwner ? rows.find((i) => i.accountLogin?.toLowerCase() === destOwner.toLowerCase()) : undefined) || rows[0];
        if (preferred?.accountLogin) {
          setDestOwner(preferred.accountLogin);
          if (preferred.installationId) setDestInstallationId(preferred.installationId);
        } else if (cred?.accountLogin) {
          setDestOwner(cred.accountLogin);
        }
      })
      .catch(() => {
        if (cancelled) return;
        setDestInstallations([]);
        if (cred?.accountLogin) setDestOwner(cred.accountLogin);
      })
      .finally(() => {
        if (!cancelled) setDestInstallationsLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // destOwner intentionally omitted — only re-resolve when credential changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [destCredentialId, githubCredentials]);

  // Pre-select the first App-preferred destination credential so the owner populates immediately.
  React.useEffect(() => {
    if (destCredentialId === '' && githubCredentials.length > 0) {
      setDestCredentialId(githubCredentials[0].id);
    }
  }, [githubCredentials, destCredentialId]);

  const destCredential = githubCredentials.find((c) => c.id === destCredentialId);
  const effectiveOwner = (destOwner || destCredential?.accountLogin || '').trim();

  /** Selected App installation for the current owner + create-permission gate (single-pair parity). */
  const selectedDestInstall = destInstallations.find(
    (i) => i.accountLogin?.toLowerCase() === destOwner.toLowerCase()
  );
  const destCreateBlocked =
    destCredential?.authMode === 'GITHUB_APP' && selectedDestInstall?.canCreateRepo === false;
  const destOwnerInstallMissing =
    destCredential?.authMode === 'GITHUB_APP' && destInstallations.length > 0 && !selectedDestInstall;

  /** Normalized URLs already used by an active pair — flagged in the picker, skipped server-side. */
  const conflictKeys = new Set<string>();
  for (const m of existingMappings) {
    if (m.active === false) continue;
    const a = normalizeRepoKey(m.repoAUrl);
    const b = normalizeRepoKey(m.repoBUrl);
    if (a) conflictKeys.add(a);
    if (b) conflictKeys.add(b);
  }

  const rowCreates = (row: RowState) => strategy === 'BULK_CREATE' || row.destKind !== 'MAP';

  const destUrlPreview = (row: RowState): string => {
    if (!rowCreates(row)) {
      return row.destRepo?.cloneUrl || '—';
    }
    const owner = effectiveOwner || 'owner';
    return `https://github.com/${owner}/${row.destName || deriveRepoName(row.repo.cloneUrl) || 'name'}.git`;
  };

  const duplicateCreateIndexes = (() => {
    const buckets = new Map<string, number[]>();
    rows.forEach((row, index) => {
      if (!rowCreates(row)) return;
      const name = row.destName.trim().toLowerCase();
      if (!name) return;
      const list = buckets.get(name) || [];
      list.push(index);
      buckets.set(name, list);
    });
    const dups = new Set<number>();
    for (const indexes of buckets.values()) {
      if (indexes.length > 1) indexes.forEach((index) => dups.add(index));
    }
    return dups;
  })();

  const createProbeSig = [
    strategy,
    effectiveOwner,
    destCredentialId,
    rows.map((row) => `${row.destKind}:${row.destName}`).join('|'),
  ].join('~');

  useEffect(() => {
    if (!effectiveOwner || destCredentialId === '') return;
    const pending = rows
      .map((row, index) => ({ row, index }))
      .filter(({ row }) => strategy === 'BULK_CREATE' || row.destKind !== 'MAP');
    if (pending.length === 0) return;
    let cancelled = false;
    const timer = window.setTimeout(() => {
      void (async () => {
        const CONCURRENCY = 5;
        for (let n = 0; n < pending.length; n += CONCURRENCY) {
          const batch = pending.slice(n, n + CONCURRENCY);
          await Promise.all(batch.map(async ({ row, index }) => {
            const name = (row.destName || deriveRepoName(row.repo.cloneUrl)).trim();
            if (!name) return;
            const exists = await probeRepoExists(
              `https://github.com/${effectiveOwner}/${name}.git`,
              destCredentialId
            );
            if (cancelled) return;
            setRows((prev) => prev.map((current, i) => (
              i === index && current.nameTaken !== exists ? { ...current, nameTaken: exists } : current
            )));
          }));
        }
      })();
    }, 450);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
    // createProbeSig is the stable input; rows object identity changes when badges update.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [createProbeSig]);

  const addSources = (repos: GitHubRepoOption[]) => {
    setRows((prev) => {
      const seen = new Set(prev.map((r) => normalizeRepoKey(r.repo.cloneUrl)));
      const next = [...prev];
      for (const repo of repos) {
        const key = normalizeRepoKey(repo.cloneUrl);
        if (seen.has(key)) continue;
        seen.add(key);
        next.push({
          repo,
          destName: deriveRepoName(repo.cloneUrl),
          destKind: 'CREATE',
          includeNonEmptyDest: false,
          excluded: false,
        });
      }
      return next;
    });
  };

  const removeRow = (index: number) => setRows((prev) => prev.filter((_, i) => i !== index));

  /** Probes picked destination repos (bounded, 5 at a time) for existing commits. */
  const probeDestinations = async (targetRows: RowState[]) => {
    const pending = targetRows.filter((r) => r.destRepo && r.destHasCommits === undefined && !r.probingDest);
    if (pending.length === 0) return;
    setRows((prev) => prev.map((r) => (pending.some((p) => p.repo.cloneUrl === r.repo.cloneUrl) ? { ...r, probingDest: true } : r)));
    const CONCURRENCY = 5;
    for (let i = 0; i < pending.length; i += CONCURRENCY) {
      const batch = pending.slice(i, i + CONCURRENCY);
      await Promise.all(batch.map(async (row) => {
        const hasCommits = await probeRepoHasCommits(row.destRepo!.cloneUrl, row.destRepo!.credentialId);
        setRows((prev) => prev.map((r) => (r.repo.cloneUrl === row.repo.cloneUrl
          ? { ...r, destHasCommits: hasCommits, probingDest: false, includeNonEmptyDest: hasCommits ? r.includeNonEmptyDest : false }
          : r)));
      }));
    }
  };

  const flagAllNonEmpty = (include: boolean) =>
    setRows((prev) => prev.map((r) => (
      r.destHasCommits ? { ...r, includeNonEmptyDest: include, excluded: !include } : r
    )));

  const nonEmptyUndecided = rows.filter((r) => r.destHasCommits && !r.includeNonEmptyDest && !r.excluded).length;

  const readyRows = rows.filter((r) => !r.excluded
    && (rowCreates(r)
      ? Boolean(r.destName.trim()) && r.nameTaken === false && !duplicateCreateIndexes.has(rows.indexOf(r))
      : Boolean(r.destRepo))
    && !(!rowCreates(r) && r.destHasCommits && !r.includeNonEmptyDest));

  const publicOnlySource = rows.some((r) => !r.excluded
    && visibilityFromRepo(r.repo) === 'PUBLIC'
    && !r.repo.credentialId);

  useEffect(() => {
    if (publicOnlySource && syncDirection !== 'UNIDIRECTIONAL_A_TO_B') {
      setSyncDirection('UNIDIRECTIONAL_A_TO_B');
    }
  }, [publicOnlySource, syncDirection]);

  const handleSubmit = async () => {
    setSubmitting(true);
    setSubmitError(null);
    try {
      const anyMap = strategy === 'SELECTIVE' && readyRows.some((r) => r.destKind === 'MAP');
      const anyCreate = readyRows.some((r) => rowCreates(r));
      const mode: BulkMirrorRequest['mode'] = !anyMap
        ? 'CREATE_DEST'
        : anyCreate
          ? 'SELECTIVE'
          : 'USE_EXISTING';
      const payload: BulkMirrorRequest = {
        mode,
        items: readyRows.map((r) => {
          const sourceVisibility: RepoVisibilityState = visibilityFromRepo(r.repo);
          const creating = rowCreates(r);
          return {
            sourceUrl: r.repo.cloneUrl,
            sourceProvider: r.repo.provider,
            sourceCredentialId: r.repo.credentialId,
            sourceInstallationId: r.repo.installationId,
            sourceVisibility,
            sourcePublicRead: sourceVisibility === 'PUBLIC' && !r.repo.credentialId,
            destName: creating ? r.destName.trim() : undefined,
            destUrl: creating ? undefined : r.destRepo!.cloneUrl,
            destCredentialId: creating ? undefined : r.destRepo!.credentialId,
            destInstallationId: creating ? undefined : r.destRepo!.installationId,
            destVisibility: creating ? undefined : visibilityFromRepo(r.destRepo!),
            includeNonEmptyDest: creating ? undefined : r.includeNonEmptyDest,
          };
        }),
        branchPattern,
        syncDirection: publicOnlySource ? 'UNIDIRECTIONAL_A_TO_B' : syncDirection,
        trunkConflictPolicy,
        storageTier,
        active,
        destOwner: anyCreate ? effectiveOwner : undefined,
        destCredentialId: anyCreate ? (destCredentialId === '' ? undefined : destCredentialId) : undefined,
        destInstallationId: anyCreate
          ? (selectedDestInstall?.installationId ?? destInstallationId)
          : undefined,
        destVisibility: anyCreate ? createVisibility.toUpperCase() as 'PUBLIC' | 'PRIVATE' | 'INTERNAL' : undefined,
      };
      const response = await submitBulkMigration(payload);
      setResult(response);
      await onSubmitted(response);
    } catch (e: any) {
      setSubmitError(e.response?.data?.error || e.response?.data?.message || e.message || 'Bulk submission failed');
    } finally {
      setSubmitting(false);
    }
  };

  if (result) {
    return (
      <div className="p-6 space-y-4 overflow-y-auto flex-1 min-h-0">
        <div className="flex items-center space-x-2">
          <ListChecks className="w-5 h-5 text-emerald-600" />
          <h3 className="text-sm font-semibold text-zinc-900">Bulk migration submitted</h3>
        </div>
        <div className="grid grid-cols-3 gap-3">
          <div className="p-3 bg-emerald-50 border border-emerald-200 rounded-xl">
            <div className="text-2xl font-semibold text-emerald-700">{result.createdQueuedCount}</div>
            <div className="text-[11px] text-emerald-700/80">pairs created &amp; queued</div>
          </div>
          <div className="p-3 bg-amber-50 border border-amber-200 rounded-xl">
            <div className="text-2xl font-semibold text-amber-700">{result.skippedCount}</div>
            <div className="text-[11px] text-amber-700/80">skipped (mirror already present)</div>
          </div>
          <div className="p-3 bg-rose-50 border border-rose-200 rounded-xl">
            <div className="text-2xl font-semibold text-rose-700">{result.failedValidationCount}</div>
            <div className="text-[11px] text-rose-700/80">invalid — fix and resubmit</div>
          </div>
        </div>
        <div className="border border-zinc-200 rounded-xl overflow-hidden">
          <table className="w-full text-xs">
            <thead className="bg-zinc-50 text-zinc-500">
              <tr>
                <th className="text-left px-3 py-2 font-medium">Source</th>
                <th className="text-left px-3 py-2 font-medium">Destination</th>
                <th className="text-left px-3 py-2 font-medium">Outcome</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-100">
              {(result.rows || []).map((row: BulkMirrorRow, idx: number) => (
                <tr key={idx} className="text-zinc-700">
                  <td className="px-3 py-2 font-mono truncate max-w-[220px]">{row.sourceUrl || '—'}</td>
                  <td className="px-3 py-2 font-mono truncate max-w-[220px]">{row.destUrl || '—'}</td>
                  <td className="px-3 py-2">
                    <span className={`px-2 py-0.5 rounded-full text-[10px] font-semibold ${
                      row.outcome === 'CREATED_QUEUED' ? 'bg-emerald-100 text-emerald-700'
                        : row.outcome === 'SKIPPED' ? 'bg-amber-100 text-amber-700'
                        : 'bg-rose-100 text-rose-700'
                    }`}>
                      {row.outcome}
                    </span>
                    {row.reason && <span className="ml-2 text-zinc-500">{row.reason}</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <button
          type="button"
          onClick={onClose}
          className="px-4 py-2 bg-zinc-900 text-white text-xs font-medium rounded-xl hover:bg-zinc-800"
        >
          Done — track jobs in Queue Manager
        </button>
      </div>
    );
  }

  return (
    <div className="p-6 space-y-5 overflow-y-auto flex-1 min-h-0">
      <p className="text-[11px] text-zinc-600 bg-zinc-50 border border-zinc-200 rounded-xl px-3 py-2">
        <span className="font-semibold text-zinc-900">{parallelism.label}</span>
        {' · '}
        Extra repos wait as QUEUED. {parallelism.raise}{' '}
        <Link
          to="/settings/system-engine#job-lane-parallelism"
          className="font-medium text-zinc-900 underline underline-offset-2"
        >
          Concurrency settings
        </Link>
      </p>
      {/* Sources first — destination options appear after repositories are selected */}
      <section className="space-y-2">
        <div className="flex items-center justify-between">
          <div className="text-xs font-semibold text-zinc-900">
            Sources {rows.length > 0 && <span className="text-zinc-400">({rows.length})</span>}
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => { setMapRow(null); setPickerOpen('SOURCE'); }}
              className="px-3 py-1.5 rounded-lg bg-zinc-900 text-white text-xs font-medium hover:bg-zinc-800"
            >
              + Browse sources
            </button>
          </div>
        </div>

        {rows.length === 0 ? (
          <div className="py-8 text-center text-zinc-400 text-xs border border-dashed border-zinc-300 rounded-xl">
            Select one or more source repositories. Destination options appear after sources are chosen.
          </div>
        ) : (
          <>
            <div className="grid grid-cols-2 gap-3">
              <button
                type="button"
                onClick={() => setStrategy('BULK_CREATE')}
                className={`p-3 rounded-xl border text-left transition-colors ${strategy === 'BULK_CREATE' ? 'border-zinc-900 bg-zinc-50 ring-1 ring-zinc-900' : 'border-zinc-200 bg-white hover:bg-zinc-50'}`}
              >
                <div className="text-xs font-semibold text-zinc-900">Bulk creation</div>
                <p className="text-[11px] text-zinc-500 mt-1">Create every destination when its mirror job starts. Names already taken can be changed here.</p>
              </button>
              <button
                type="button"
                onClick={() => setStrategy('SELECTIVE')}
                className={`p-3 rounded-xl border text-left transition-colors ${strategy === 'SELECTIVE' ? 'border-zinc-900 bg-zinc-50 ring-1 ring-zinc-900' : 'border-zinc-200 bg-white hover:bg-zinc-50'}`}
              >
                <div className="text-xs font-semibold text-zinc-900">Selective create or map</div>
                <p className="text-[11px] text-zinc-500 mt-1">Per source, create a new repository or map one that already exists.</p>
              </button>
            </div>

            {(strategy === 'BULK_CREATE' || rows.some((r) => r.destKind !== 'MAP')) && (
              <div className="p-3 bg-zinc-50/70 border border-zinc-200 rounded-xl space-y-2">
                <div className="text-xs font-semibold text-zinc-900">New destinations</div>
                <p className="text-[10px] text-zinc-500">Repositories are created when each mirror job starts, not when this form is submitted.</p>
                <div className="grid grid-cols-2 gap-2">
                  <select
                    value={destCredentialId}
                    onChange={(e) => {
                      setDestCredentialId(e.target.value);
                      setDestOwner('');
                      setDestInstallationId(undefined);
                    }}
                    className="bg-white border border-zinc-200 rounded-xl px-3 py-2 text-xs"
                  >
                    <option value="">Destination credential…</option>
                    {githubCredentials.map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.label} {c.accountLogin ? `· ${c.accountLogin}` : ''} {c.authMode === 'GITHUB_APP' ? '(App)' : '(PAT)'}
                      </option>
                    ))}
                  </select>
                  <div className="relative">
                    <input
                      type="text"
                      value={destOwner}
                      onChange={(e) => setDestOwner(e.target.value)}
                      placeholder={destCredential?.accountLogin || 'owner / org'}
                      className="w-full bg-white border border-zinc-200 rounded-xl px-3 py-2 text-xs"
                    />
                    {destInstallationsLoading && (
                      <Loader2 className="w-3.5 h-3.5 animate-spin text-zinc-400 absolute right-2.5 top-1/2 -translate-y-1/2" />
                    )}
                  </div>
                </div>
                {destCredential?.authMode === 'GITHUB_APP' && destInstallations.length > 0 && (
                  <select
                    value={selectedDestInstall?.installationId ?? ''}
                    onChange={(e) => {
                      const install = destInstallations.find((i) => i.installationId === e.target.value);
                      if (install) {
                        setDestOwner(install.accountLogin || destOwner);
                        setDestInstallationId(install.installationId);
                      }
                    }}
                    className="w-full bg-white border border-zinc-200 rounded-xl px-3 py-2 text-xs"
                  >
                    <option value="">{destInstallations.length > 1 ? 'Choose the App installation (owner/org)…' : 'App installation'}</option>
                    {destInstallations.map((i) => (
                      <option key={i.installationId} value={i.installationId}>
                        @{i.accountLogin || 'unknown'}
                        {i.accountType ? ` · ${i.accountType}` : ''}
                        {i.canCreateRepo === false ? ' · needs Administration' : ''}
                      </option>
                    ))}
                  </select>
                )}
                {destCreateBlocked && (
                  <div className="flex items-start gap-1.5 p-2 bg-amber-50 border border-amber-200 rounded-lg text-[10px] text-amber-800">
                    <AlertTriangle className="w-3.5 h-3.5 shrink-0 mt-0.5" />
                    <span>The selected App installation lacks Administration (write) — repository creation would be rejected.</span>
                  </div>
                )}
                <div className="flex flex-wrap items-center gap-1.5 text-[10px]">
                  <span className="text-zinc-500 font-medium">Create as</span>
                  {(['private', 'public', 'internal'] as const).map((v) => (
                    <button
                      key={v}
                      type="button"
                      disabled={v === 'public' && !publicReposEnabled}
                      onClick={() => setCreateVisibility(v)}
                      className={`px-2 py-0.5 rounded-md border capitalize ${createVisibility === v ? 'bg-zinc-900 text-white border-zinc-900' : 'bg-white text-zinc-600 border-zinc-200'} disabled:opacity-40`}
                    >
                      {v}
                    </button>
                  ))}
                </div>
              </div>
            )}

          <div className="border border-zinc-200 rounded-xl overflow-hidden">
            <table className="w-full text-xs">
              <thead className="bg-zinc-50 text-zinc-500">
                <tr>
                  <th className="text-left px-3 py-2 font-medium">Source</th>
                  <th className="text-left px-3 py-2 font-medium">Destination</th>
                  <th className="w-8" />
                </tr>
              </thead>
              <tbody className="divide-y divide-zinc-100">
                {rows.map((row, idx) => {
                  const conflict = conflictKeys.has(normalizeRepoKey(row.repo.cloneUrl));
                  return (
                    <tr key={normalizeRepoKey(row.repo.cloneUrl)} className={row.excluded ? 'opacity-50' : ''}>
                      <td className="px-3 py-2">
                        <div className="flex items-center gap-2 min-w-0">
                          {renderProviderIcon(row.repo.provider)}
                          <div className="min-w-0">
                            <div className="font-semibold text-zinc-900 truncate">{row.repo.fullName}</div>
                            {conflict && (
                              <span className="inline-block mt-0.5 px-1.5 py-0.5 rounded-full bg-amber-100 text-amber-700 text-[9px] font-semibold uppercase">
                                already mirrored — will be skipped
                              </span>
                            )}
                          </div>
                        </div>
                      </td>
                      <td className="px-3 py-2">
                        {strategy === 'SELECTIVE' && (
                          <div className="flex gap-1 mb-1">
                            <button
                              type="button"
                              onClick={() => setRows((prev) => prev.map((r, i) => (i === idx ? { ...r, destKind: 'CREATE' } : r)))}
                              className={`px-2 py-0.5 rounded-md border text-[10px] ${row.destKind !== 'MAP' ? 'bg-zinc-900 text-white border-zinc-900' : 'bg-white text-zinc-600 border-zinc-200'}`}
                            >
                              Create
                            </button>
                            <button
                              type="button"
                              onClick={() => setRows((prev) => prev.map((r, i) => (i === idx ? { ...r, destKind: 'MAP' } : r)))}
                              className={`px-2 py-0.5 rounded-md border text-[10px] ${row.destKind === 'MAP' ? 'bg-zinc-900 text-white border-zinc-900' : 'bg-white text-zinc-600 border-zinc-200'}`}
                            >
                              Map existing
                            </button>
                          </div>
                        )}
                        {rowCreates(row) ? (
                          <div className="space-y-1">
                            <input
                              type="text"
                              value={row.destName}
                              onChange={(e) => setRows((prev) => prev.map((r, i) => (i === idx ? { ...r, destName: e.target.value, nameTaken: undefined } : r)))}
                              className="w-full bg-white border border-zinc-200 rounded-lg px-2 py-1.5 text-xs"
                            />
                            <p className="text-[10px] font-mono text-zinc-400 truncate">{destUrlPreview(row)}</p>
                            {duplicateCreateIndexes.has(idx) && (
                              <span className="inline-block px-1.5 py-0.5 rounded-full bg-amber-100 text-amber-800 text-[9px] font-semibold uppercase">Duplicate name in this batch</span>
                            )}
                            {row.nameTaken && (
                              <span className="inline-block px-1.5 py-0.5 rounded-full bg-rose-100 text-rose-700 text-[9px] font-semibold uppercase">Name taken — change it</span>
                            )}
                            {row.nameTaken === false && !duplicateCreateIndexes.has(idx) && (
                              <span className="inline-block px-1.5 py-0.5 rounded-full bg-emerald-100 text-emerald-700 text-[9px] font-semibold uppercase">Available</span>
                            )}
                          </div>
                        ) : row.destRepo ? (
                          <div className="space-y-1">
                            <div className="flex items-center gap-1.5 min-w-0">
                              <span className="font-mono text-zinc-700 truncate">{row.destRepo.fullName}</span>
                              {row.probingDest && <Loader2 className="w-3 h-3 animate-spin text-zinc-400" />}
                              {row.destHasCommits && (
                                <span className="px-1.5 py-0.5 rounded-full bg-amber-100 text-amber-700 text-[9px] font-semibold uppercase">
                                  has content
                                </span>
                              )}
                            </div>
                            {row.destHasCommits && !row.excluded && (
                              <label className="flex items-center gap-1.5 text-[10px] text-amber-700">
                                <input
                                  type="checkbox"
                                  checked={row.includeNonEmptyDest}
                                  onChange={(e) => setRows((prev) => prev.map((r, i) => (i === idx
                                    ? { ...r, includeNonEmptyDest: e.target.checked, excluded: !e.target.checked } : r)))}
                                />
                                Mirror into it anyway (overwrite risk)
                              </label>
                            )}
                            <button
                              type="button"
                              onClick={() => { setMapRow(idx); setPickerOpen('DEST'); }}
                              className="text-[10px] text-zinc-500 underline"
                            >
                              Change
                            </button>
                          </div>
                        ) : (
                          <button
                            type="button"
                            onClick={() => { setMapRow(idx); setPickerOpen('DEST'); }}
                            className="px-2 py-1 rounded-lg border border-zinc-300 text-zinc-700 text-[11px]"
                          >
                            Choose repository
                          </button>
                        )}
                      </td>
                      <td className="px-1">
                        <button
                          type="button"
                          onClick={() => removeRow(idx)}
                          className="p-1 text-zinc-400 hover:text-rose-600"
                          title="Remove"
                        >
                          <X className="w-3.5 h-3.5" />
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          </>
        )}

        {strategy === 'SELECTIVE' && nonEmptyUndecided > 0 && (
          <div className="flex items-center justify-between p-2 bg-amber-50 border border-amber-200 rounded-xl">
            <span className="text-[11px] text-amber-800">
              {nonEmptyUndecided} destination(s) already have content — decide per row, or:
            </span>
            <div className="flex gap-1.5">
              <button type="button" onClick={() => flagAllNonEmpty(false)} className="px-2 py-1 rounded-lg border border-amber-300 text-amber-800 text-[10px] font-medium hover:bg-amber-100">
                Exclude all flagged
              </button>
              <button type="button" onClick={() => flagAllNonEmpty(true)} className="px-2 py-1 rounded-lg bg-amber-600 text-white text-[10px] font-medium hover:bg-amber-700">
                Include all flagged
              </button>
            </div>
          </div>
        )}
      </section>

      {/* Shared parameters */}
      <section className="p-3 bg-zinc-50/70 border border-zinc-200 rounded-xl space-y-2">
        <div className="flex items-center gap-1.5 text-xs font-semibold text-zinc-900">
          Shared parameters <span className="font-normal text-zinc-400">(applied to every pair)</span>
          <InfoTooltip title="Shared parameters" whatIsIt="Same options as a single pair. Each repository gets its own mirror pair and its own bootstrap job." />
        </div>
        <div className="grid grid-cols-4 gap-2">
          <label className="text-[10px] text-zinc-500 space-y-1">
            Branch pattern
            <input type="text" value={branchPattern} onChange={(e) => setBranchPattern(e.target.value)} className="w-full bg-white border border-zinc-200 rounded-lg px-2 py-1.5 text-xs" />
          </label>
          <label className="text-[10px] text-zinc-500 space-y-1">
            Direction
            <select
              value={publicOnlySource ? 'UNIDIRECTIONAL_A_TO_B' : syncDirection}
              onChange={(e) => setSyncDirection(e.target.value as SyncDirection)}
              className="w-full bg-white border border-zinc-200 rounded-lg px-2 py-1.5 text-xs"
            >
              <option value="BIDIRECTIONAL" disabled={publicOnlySource}>Bidirectional</option>
              <option value="UNIDIRECTIONAL_A_TO_B">A → B only</option>
              <option value="UNIDIRECTIONAL_B_TO_A" disabled={publicOnlySource}>B → A only</option>
            </select>
          </label>
          <label className="text-[10px] text-zinc-500 space-y-1">
            Trunk conflicts
            <select value={trunkConflictPolicy} onChange={(e) => setTrunkConflictPolicy(e.target.value as TrunkConflictPolicy)} className="w-full bg-white border border-zinc-200 rounded-lg px-2 py-1.5 text-xs">
              <option value="ISOLATE">Isolate</option>
              <option value="FAIL_JOB">Fail job</option>
              <option value="ORIGIN_WINS">Origin wins</option>
            </select>
          </label>
          <label className="text-[10px] text-zinc-500 space-y-1">
            Storage tier
            <select value={storageTier} onChange={(e) => setStorageTier(e.target.value as StorageTier)} className="w-full bg-white border border-zinc-200 rounded-lg px-2 py-1.5 text-xs">
              <option value="AUTO_LRU">Auto LRU</option>
              <option value="HOT_PERSISTENT">Hot persistent</option>
              <option value="EPHEMERAL_STREAM">Ephemeral</option>
              <option value="NAS_MOUNT">NAS mount</option>
            </select>
          </label>
        </div>
        <label className="flex items-center gap-2 text-xs text-zinc-600">
          <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
          Active (bootstrap mirror job is enqueued for each pair on submit)
        </label>
      </section>

      {submitError && (
        <div className="flex items-start gap-2 p-3 bg-rose-50 border border-rose-200 rounded-xl text-xs text-rose-700">
          <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5" />
          <span>{submitError}</span>
        </div>
      )}

      {/* Footer */}
      <div className="flex items-center justify-between border-t border-zinc-200 pt-4">
        <div className="text-xs text-zinc-500">
          {readyRows.length} pair{readyRows.length === 1 ? '' : 's'} will be created and queued
          {readyRows.length > 0 && <> · jobs run in parallel on available workers, extras wait as queued</>}
        </div>
        <div className="flex items-center gap-2">
          <button type="button" onClick={onClose} className="px-3 py-2 rounded-xl border border-zinc-200 text-zinc-600 text-xs hover:bg-zinc-50">
            Cancel
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={submitting || readyRows.length === 0}
            className="px-4 py-2 bg-zinc-900 text-white text-xs font-medium rounded-xl hover:bg-zinc-800 disabled:opacity-40 flex items-center gap-1.5"
          >
            {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}
            Submit bulk migration
          </button>
        </div>
      </div>

      <RepoPickerModal
        isOpen={pickerOpen !== null}
        onClose={() => { setPickerOpen(null); setMapRow(null); }}
        onSelectRepo={(repo) => {
          if (mapRow == null) return;
          setRows((prev) => prev.map((r, i) => (i === mapRow
            ? { ...r, destKind: 'MAP', destRepo: repo, destHasCommits: undefined, probingDest: false, includeNonEmptyDest: false }
            : r)));
          setMapRow(null);
          setPickerOpen(null);
          void probeDestinations(rows.map((r, i) => (i === mapRow
            ? { ...r, destKind: 'MAP' as const, destRepo: repo, destHasCommits: undefined }
            : r)));
        }}
        title={pickerOpen === 'DEST' ? 'Select an existing destination' : 'Select source repositories'}
        access={pickerOpen === 'DEST' ? 'PUSH' : 'PULL'}
        multiSelect={pickerOpen === 'SOURCE'}
        initialSelection={pickerOpen === 'SOURCE' ? rows.map((r) => r.repo) : []}
        onConfirmMulti={addSources}
        conflictKeys={pickerOpen === 'DEST' ? undefined : conflictKeys}
      />
    </div>
  );
};
