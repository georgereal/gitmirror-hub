import React, { useState, useEffect, useRef, useMemo } from 'react';
import {
  Search, X, Globe, GitFork, Layers, Lock,
  Loader2, ArrowRight, Filter, Server, Check
} from 'lucide-react';
import { GitHubRepoOption, RepoSearchResult, ScmCredential, ScmInstallationOption } from '../types';
import { searchRemoteRepositories, listScmCredentials, searchCredentialRepositories, listScmInstallations } from '../services/api';
import { isProviderUiEnabled, useFeatureFlags } from '../hooks/useFeatureFlags';

interface RepoPickerModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSelectRepo: (repo: GitHubRepoOption) => void;
  title?: string;
  defaultProvider?: string;
  access?: 'PULL' | 'PUSH';
  /** Multi-select mode (bulk migration): checkboxes, selection survives paging/search. */
  multiSelect?: boolean;
  initialSelection?: GitHubRepoOption[];
  onConfirmMulti?: (repos: GitHubRepoOption[]) => void;
  /** Normalized clone URLs already part of an active pair — rows get an "already mirrored" badge. */
  conflictKeys?: Set<string>;
}

/** Canonical repo URL key — mirrors backend RepoMappingService.normalizeRepoKey. */
const repoKey = (url?: string | null): string => {
  if (!url) return '';
  let s = url.trim().toLowerCase();
  s = s.replace(/\/+$/, '');
  s = s.replace(/\.git$/, '');
  s = s.replace(/^(https?|ssh|git):\/\//, '');
  s = s.replace(/^git@([^:]+):/, '$1/');
  s = s.replace(/^[^@/]+@/, '');
  return s;
};

const ALL_PROVIDERS = ['GITHUB', 'GHES', 'GITLAB', 'BITBUCKET', 'ORIGIN'] as const;
const PAGE_SIZE_OPTIONS = [15, 30, 50] as const;

export const RepoPickerModal: React.FC<RepoPickerModalProps> = ({
  isOpen,
  onClose,
  onSelectRepo,
  title = 'Select repository',
  defaultProvider = 'GITHUB',
  access = 'PULL',
  multiSelect = false,
  initialSelection = [],
  onConfirmMulti,
  conflictKeys,
}) => {
  const { flags } = useFeatureFlags();
  const PROVIDERS = ALL_PROVIDERS.filter((p) => isProviderUiEnabled(flags, p));
  const [query, setQuery] = useState('');
  const [provider, setProvider] = useState<string>(defaultProvider === 'ALL' ? 'GITHUB' : defaultProvider);
  const [credentials, setCredentials] = useState<ScmCredential[]>([]);
  const [credentialId, setCredentialId] = useState<string | ''>('');
  /** Blank = every selected App installation. Set = that owner only. */
  const [ownerInstallationId, setOwnerInstallationId] = useState<string | ''>('');
  const [installations, setInstallations] = useState<ScmInstallationOption[]>([]);
  const [pageSize, setPageSize] = useState<number>(15);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [repos, setRepos] = useState<GitHubRepoOption[]>([]);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** False until user searches or clicks List accessible — avoids loading all repos on open. */
  const [hasLoaded, setHasLoaded] = useState(false);
  const debounceTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);
  /** Multi-select: persistent selection map keyed by normalized clone URL — survives paging/search. */
  const [selection, setSelection] = useState<Map<string, GitHubRepoOption>>(new Map());

  const selectedRepos = useMemo(() => Array.from(selection.values()), [selection]);

  useEffect(() => {
    if (!PROVIDERS.includes(provider as typeof ALL_PROVIDERS[number])) {
      setProvider(PROVIDERS[0] || 'GITHUB');
    }
  }, [flags.providerGitlabEnabled, flags.providerBitbucketEnabled, flags.providerOriginEnabled, provider]);

  const needsCredential = provider === 'GITHUB' || provider === 'GHES';

  useEffect(() => {
    if (!isOpen) return;
    setQuery('');
    setPage(1);
    setRepos([]);
    setHasMore(false);
    setError(null);
    setHasLoaded(false);
    if (multiSelect) {
      // Seed persistent selection from the parent (cross-modal continuity), don't clear it.
      const seeded = new Map<string, GitHubRepoOption>();
      for (const repo of initialSelection) {
        seeded.set(repoKey(repo.cloneUrl), repo);
      }
      setSelection(seeded);
    } else {
      setSelection(new Map());
    }
    if (debounceTimeout.current) clearTimeout(debounceTimeout.current);
    if (needsCredential) {
      const p = provider === 'GHES' ? 'GITHUB_ENTERPRISE' : 'GITHUB';
      listScmCredentials(p)
        .then((rows) => {
          // GitHub App credentials grant the richest permissions — prefer them over PATs.
          const enabled = rows
            .filter((r) => r.enabled)
            .sort((a, b) => (a.authMode === 'GITHUB_APP' ? 0 : 1) - (b.authMode === 'GITHUB_APP' ? 0 : 1) || a.id.localeCompare(b.id));
          setCredentials(enabled);
          setCredentialId(enabled[0]?.id ?? '');
          setOwnerInstallationId('');
          setInstallations([]);
        })
        .catch(() => {
          setCredentials([]);
          setCredentialId('');
        });
    } else {
      setCredentials([]);
      setCredentialId('');
      setOwnerInstallationId('');
      setInstallations([]);
    }
  }, [isOpen, provider]);

  useEffect(() => {
    return () => {
      if (debounceTimeout.current) clearTimeout(debounceTimeout.current);
    };
  }, []);

  const fetchRepos = async (
    searchQuery: string,
    activeProvider: string,
    pageNum: number,
    append = false,
    cred?: string,
    limit = pageSize,
    installId?: string
  ) => {
    if (pageNum === 1) setLoading(true);
    else setLoadingMore(true);
    setError(null);
    setHasLoaded(true);
    try {
      let res: RepoSearchResult;
      if (cred) {
        res = await searchCredentialRepositories(cred, {
          query: searchQuery,
          page: pageNum,
          limit,
          access,
          installationId: installId || undefined,
        });
      } else {
        res = await searchRemoteRepositories({
          query: searchQuery,
          provider: activeProvider,
          page: pageNum,
          limit,
        });
      }
      setRepos((prev) => (append ? [...prev, ...(res.items || [])] : res.items || []));
      setHasMore(res.hasMore);
      setPage(res.page);
    } catch (e: any) {
      console.error('Failed to search repositories:', e);
      setError(e.response?.data?.error || e.message || 'Failed to list repositories');
      if (!append) setRepos([]);
    } finally {
      setLoading(false);
      setLoadingMore(false);
    }
  };

  const selectedCredential = credentials.find((c) => c.id === credentialId);

  useEffect(() => {
    if (!isOpen || !credentialId || selectedCredential?.authMode !== 'GITHUB_APP') {
      setInstallations([]);
      setOwnerInstallationId('');
      return;
    }
    let cancelled = false;
    void listScmInstallations(credentialId)
      .then((rows) => {
        if (cancelled) return;
        const selectedIds = selectedCredential.installationIds?.length
          ? selectedCredential.installationIds
          : selectedCredential.installationId
            ? [selectedCredential.installationId]
            : [];
        const allowed = new Set(selectedIds);
        const visible = (rows || []).filter((row) =>
          allowed.size === 0 || (row.installationId != null && allowed.has(row.installationId))
        );
        setInstallations(visible);
      })
      .catch(() => {
        if (!cancelled) setInstallations([]);
      });
    return () => {
      cancelled = true;
    };
  }, [isOpen, credentialId, selectedCredential?.authMode, selectedCredential?.installationId, selectedCredential?.installationIds]);

  const canFetch = !needsCredential || credentialId !== '';

  const runSearch = (searchQuery: string, installId = ownerInstallationId) => {
    if (!canFetch) return;
    setPage(1);
    fetchRepos(
      searchQuery,
      provider,
      1,
      false,
      credentialId === '' ? undefined : credentialId,
      pageSize,
      installId
    );
  };

  const handleQueryChange = (val: string) => {
    setQuery(val);
    if (debounceTimeout.current) clearTimeout(debounceTimeout.current);
    const trimmed = val.trim();
    if (!trimmed) {
      // Clearing search returns to idle — do not auto-list everything.
      setRepos([]);
      setHasMore(false);
      setHasLoaded(false);
      setError(null);
      return;
    }
    debounceTimeout.current = setTimeout(() => {
      runSearch(trimmed);
    }, 300);
  };

  const handleListAccessible = () => {
    if (!canFetch) return;
    setQuery('');
    runSearch('');
  };

  const handleCredentialChange = (next: string | '') => {
    setCredentialId(next);
    setOwnerInstallationId('');
    setInstallations([]);
    setRepos([]);
    setHasMore(false);
    setHasLoaded(false);
    setError(null);
    setQuery('');
    if (debounceTimeout.current) clearTimeout(debounceTimeout.current);
  };

  const handleOwnerChange = (next: string) => {
    setOwnerInstallationId(next);
    setRepos([]);
    setHasMore(false);
    setPage(1);
    if (!hasLoaded && !query.trim()) {
      setError(null);
      return;
    }
    setHasLoaded(false);
    runSearch(query.trim(), next);
  };

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

  const toggleRepo = (repo: GitHubRepoOption) => {
    const key = repoKey(repo.cloneUrl);
    setSelection((prev) => {
      const next = new Map(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.set(key, { ...repo, credentialId: repo.credentialId || (credentialId === '' ? undefined : credentialId) });
      }
      return next;
    });
  };

  const selectAllOnPage = () => {
    setSelection((prev) => {
      const next = new Map(prev);
      for (const repo of repos) {
        const key = repoKey(repo.cloneUrl);
        if (!next.has(key)) {
          next.set(key, { ...repo, credentialId: repo.credentialId || (credentialId === '' ? undefined : credentialId) });
        }
      }
      return next;
    });
  };

  const confirmMulti = () => {
    onConfirmMulti?.(Array.from(selection.values()));
    onClose();
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 bg-black/50 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="bg-white border border-zinc-200 rounded-2xl w-full max-w-2xl shadow-2xl overflow-hidden flex flex-col my-auto max-h-[85vh]">
        <div className="px-6 py-4 border-b border-zinc-100 flex items-center justify-between shrink-0">
          <div>
            <h3 className="text-sm font-semibold text-zinc-900">{title}</h3>
            <p className="text-xs text-zinc-500 mt-0.5">
              Search by name, or list a page of accessible repos. {access === 'PUSH' ? 'Write access required.' : 'Read access required.'}
            </p>
          </div>
          <button onClick={onClose} className="p-1.5 text-zinc-400 hover:text-zinc-700 rounded-lg hover:bg-zinc-100">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="p-4 bg-zinc-50/70 border-b border-zinc-200/80 space-y-3 shrink-0">
          <div className="flex items-center space-x-1.5 overflow-x-auto text-[11px]">
            <span className="text-zinc-400 font-medium flex items-center mr-1">
              <Filter className="w-3 h-3 mr-1" /> Provider:
            </span>
            {PROVIDERS.map((p) => (
              <button
                key={p}
                type="button"
                onClick={() => setProvider(p)}
                className={`px-3 py-1 rounded-full font-medium ${
                  provider === p ? 'bg-zinc-900 text-white' : 'bg-white text-zinc-600 border border-zinc-200'
                }`}
              >
                {p}
              </button>
            ))}
          </div>
          {needsCredential && (
            <select
              value={credentialId}
              onChange={(e) => handleCredentialChange(e.target.value)}
              className="w-full bg-white border border-zinc-200 rounded-xl px-3 py-2 text-xs"
            >
              <option value="">{credentials.length ? 'Select a credential' : 'No GitHub/GHES credentials — add one in Settings'}</option>
              {credentials.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.label} {c.accountLogin ? `· ${c.accountLogin}` : ''} {c.authMode === 'GITHUB_APP' ? '(App)' : '(PAT)'}
                </option>
              ))}
            </select>
          )}
          {needsCredential && installations.length > 0 && (
            <select
              value={ownerInstallationId}
              onChange={(e) => handleOwnerChange(e.target.value)}
              className="w-full bg-white border border-zinc-200 rounded-xl px-3 py-2 text-xs"
            >
              <option value="">All owners</option>
              {installations.map((install) => (
                <option key={install.installationId} value={install.installationId}>
                  @{install.accountLogin || install.installationId}
                  {install.accountType ? ` · ${install.accountType}` : ''}
                </option>
              ))}
            </select>
          )}
          <div className="flex items-center gap-2">
            <div className="relative flex-1">
              <Search className="w-4 h-4 text-zinc-400 absolute left-3.5 top-1/2 -translate-y-1/2" />
              <input
                type="text"
                value={query}
                onChange={(e) => handleQueryChange(e.target.value)}
                placeholder="Search by repo name or org…"
                className="w-full bg-white border border-zinc-200 rounded-xl pl-9 pr-4 py-2 text-xs"
                autoFocus
                disabled={needsCredential && credentialId === ''}
              />
            </div>
            <select
              value={pageSize}
              onChange={(e) => setPageSize(Number(e.target.value))}
              className="bg-white border border-zinc-200 rounded-xl px-2 py-2 text-xs text-zinc-700 shrink-0"
              title="Results per page"
            >
              {PAGE_SIZE_OPTIONS.map((n) => (
                <option key={n} value={n}>
                  {n} / page
                </option>
              ))}
            </select>
            <button
              type="button"
              onClick={handleListAccessible}
              disabled={!canFetch || loading}
              className="shrink-0 px-3 py-2 rounded-xl bg-zinc-900 text-white text-xs font-medium disabled:opacity-40 hover:bg-zinc-800"
            >
              List accessible
            </button>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto p-4 space-y-2">
          {loading ? (
            <div className="py-12 flex flex-col items-center space-y-2 text-zinc-400 text-xs">
              <Loader2 className="w-6 h-6 animate-spin" />
              <span>{query.trim() ? 'Searching…' : 'Loading repositories…'}</span>
            </div>
          ) : error ? (
            <p className="py-12 text-center text-rose-600 text-xs px-6">{error}</p>
          ) : needsCredential && credentialId === '' ? (
            <p className="py-12 text-center text-zinc-500 text-xs">Select a credential, then search or list accessible repos.</p>
          ) : !hasLoaded ? (
            <div className="py-12 text-center text-zinc-500 text-xs px-6 space-y-2">
              <p>Repos are not loaded until you search or click <span className="font-medium text-zinc-700">List accessible</span>.</p>
              <p className="text-zinc-400">Use search for large installations; list loads one page ({pageSize} repos) at a time.</p>
            </div>
          ) : repos.length === 0 ? (
            <p className="py-12 text-center text-zinc-500 text-xs px-6">
              {query.trim()
                ? `No repositories matching “${query.trim()}”${ownerInstallationId ? ' for this owner' : ''}.`
                : 'No repositories for this credential. On GitHub, open the App installation and grant it the repositories you want to mirror.'}
            </p>
          ) : (
            <>
              {multiSelect && repos.length > 0 && (
                <div className="flex items-center justify-between px-1 pb-1">
                  <button
                    type="button"
                    onClick={selectAllOnPage}
                    className="text-[11px] font-medium text-zinc-600 hover:text-zinc-900"
                  >
                    Select all on this page ({repos.length})
                  </button>
                  <span className="text-[11px] text-zinc-400">Selection persists across pages & searches</span>
                </div>
              )}
              {repos.map((repo) => {
                const key = repoKey(repo.cloneUrl);
                const isSelected = multiSelect && selection.has(key);
                const isConflict = conflictKeys?.has(key);
                return (
                <div
                  key={repo.id || repo.cloneUrl}
                  onClick={() => {
                    if (multiSelect) {
                      toggleRepo(repo);
                      return;
                    }
                    onSelectRepo({ ...repo, credentialId: repo.credentialId || (credentialId === '' ? undefined : credentialId) });
                    onClose();
                  }}
                  className={`p-3 border rounded-xl cursor-pointer flex items-center justify-between ${
                    isSelected ? 'bg-zinc-900/5 border-zinc-900/30' : 'bg-white hover:bg-zinc-50 border-zinc-200'
                  }`}
                >
                  <div className="flex items-start space-x-3 min-w-0">
                    {multiSelect && (
                      <span
                        className={`mt-0.5 w-4 h-4 rounded border flex items-center justify-center shrink-0 ${
                          isSelected ? 'bg-zinc-900 border-zinc-900' : 'bg-white border-zinc-300'
                        }`}
                      >
                        {isSelected && <Check className="w-3 h-3 text-white" />}
                      </span>
                    )}
                    <div className="p-2 bg-zinc-50 rounded-lg border">{renderProviderIcon(repo.provider)}</div>
                    <div className="min-w-0">
                      <div className="font-semibold text-zinc-900 text-xs truncate flex items-center gap-1.5">
                        {repo.fullName}
                        {isConflict && (
                          <span className="px-1.5 py-0.5 rounded-full bg-amber-100 text-amber-700 text-[9px] font-semibold uppercase tracking-wide">
                            already mirrored
                          </span>
                        )}
                      </div>
                      <p className="text-[11px] font-mono text-zinc-400 truncate">{repo.cloneUrl}</p>
                    </div>
                  </div>
                  {!multiSelect && (
                    <span className="text-[11px] flex items-center space-x-1 text-zinc-600">
                      <span>Select</span>
                      <ArrowRight className="w-3 h-3" />
                    </span>
                  )}
                </div>
                );
              })}
              {hasMore && (
                <button
                  type="button"
                  onClick={() =>
                    fetchRepos(
                      query.trim(),
                      provider,
                      page + 1,
                      true,
                      credentialId === '' ? undefined : credentialId,
                      pageSize,
                      ownerInstallationId
                    )
                  }
                  disabled={loadingMore}
                  className="px-4 py-2 bg-zinc-100 rounded-xl text-xs mx-auto block"
                >
                  {loadingMore ? 'Loading…' : `Load more (${pageSize})`}
                </button>
              )}
            </>
          )}
        </div>

        {multiSelect && (
          <div className="px-4 py-3 border-t border-zinc-200 bg-white flex items-center justify-between shrink-0">
            <div className="text-xs text-zinc-600">
              <span className="font-semibold text-zinc-900">{selectedRepos.length}</span> selected
            </div>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setSelection(new Map())}
                disabled={selectedRepos.length === 0}
                className="px-3 py-1.5 rounded-lg border border-zinc-200 text-zinc-600 text-xs hover:bg-zinc-50 disabled:opacity-40"
              >
                Clear
              </button>
              <button
                type="button"
                onClick={confirmMulti}
                disabled={selectedRepos.length === 0}
                className="px-3 py-1.5 rounded-lg bg-zinc-900 text-white text-xs font-medium hover:bg-zinc-800 disabled:opacity-40 flex items-center gap-1.5"
              >
                <Check className="w-3.5 h-3.5" /> Confirm selection
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
