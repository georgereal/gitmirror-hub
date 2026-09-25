import React, { useEffect, useState } from 'react';
import { Loader2, Search, X } from 'lucide-react';
import {
  applyWriteAuthority,
  listRepositoryRulesets,
  listScmCredentials,
  pageCredentialRepositories,
  RepositoryRulesetItem,
  setRepositoryRulesetEnforcement,
} from '../../services/api';
import { GitHubRepoOption, ScmCredential } from '../../types';

const PAGE_SIZE_OPTIONS = [15, 50] as const;
const HUB_RULESET = 'gitmirror-replica-readonly';

const repoVisibility = (repo: GitHubRepoOption): string => {
  const value = (repo.visibility || (repo.isPrivate ? 'private' : 'public')).toLowerCase();
  if (value === 'internal') return 'Internal';
  if (value === 'public') return 'Public';
  return 'Private';
};

const stateLabel = (enforcement: string) => {
  if (enforcement === 'active') return 'Enforced';
  if (enforcement === 'disabled') return 'Off';
  if (enforcement === 'evaluate') return 'Evaluate';
  return enforcement || 'Unknown';
};

const stateClass = (enforcement: string) => {
  if (enforcement === 'active') return 'bg-emerald-50 text-emerald-800 border-emerald-200';
  if (enforcement === 'disabled') return 'bg-zinc-100 text-zinc-600 border-zinc-200';
  return 'bg-amber-50 text-amber-800 border-amber-200';
};

export const RepositoryRulesetModal: React.FC<{ open: boolean; onClose: () => void }> = ({ open, onClose }) => {
  const [apps, setApps] = useState<ScmCredential[]>([]);
  const [credentialId, setCredentialId] = useState('');
  const [query, setQuery] = useState('');
  const [pageSize, setPageSize] = useState<(typeof PAGE_SIZE_OPTIONS)[number]>(15);
  const [submitted, setSubmitted] = useState<{ query: string; limit: number } | null>(null);
  const [repos, setRepos] = useState<GitHubRepoOption[]>([]);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const [loadingApps, setLoadingApps] = useState(false);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [listError, setListError] = useState<string | null>(null);
  const [selected, setSelected] = useState<GitHubRepoOption | null>(null);
  const [rulesets, setRulesets] = useState<RepositoryRulesetItem[]>([]);
  const [rulesetLoading, setRulesetLoading] = useState(false);
  const [rulesetError, setRulesetError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | 'create' | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setLoadingApps(true);
    setListError(null);
    void Promise.all([listScmCredentials('GITHUB'), listScmCredentials('GITHUB_ENTERPRISE')])
      .then(([github, enterprise]) => {
        const enabled = [...github, ...enterprise]
          .filter((row) => row.enabled && row.authMode === 'GITHUB_APP')
          .sort((a, b) => a.label.localeCompare(b.label));
        setApps(enabled);
        setCredentialId((current) => current || enabled[0]?.id || '');
      })
      .catch((e: any) => {
        setApps([]);
        setListError(e.response?.data?.message || e.message || 'Could not list GitHub Apps.');
      })
      .finally(() => setLoadingApps(false));
  }, [open]);

  useEffect(() => {
    if (!open || !credentialId || !submitted) {
      if (!submitted) {
        setRepos([]);
        setHasMore(false);
      }
      return;
    }
    let cancelled = false;
    setLoading(true);
    setListError(null);
    void pageCredentialRepositories(credentialId, {
      query: submitted.query,
      page: 1,
      limit: submitted.limit,
    })
      .then((result) => {
        if (cancelled) return;
        setRepos(result.items || []);
        setPage(result.page || 1);
        setHasMore(result.hasMore);
      })
      .catch((e: any) => {
        if (cancelled) return;
        setRepos([]);
        setHasMore(false);
        setListError(e.response?.data?.message || e.message || 'Could not search repositories.');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, credentialId, submitted]);

  const search = () => {
    setListError(null);
    setSubmitted({ query: query.trim(), limit: pageSize });
  };

  const loadRulesets = (repo: GitHubRepoOption) => {
    setRulesets([]);
    setRulesetLoading(true);
    setRulesetError(null);
    void listRepositoryRulesets(credentialId, repo.fullName, repo.installationId)
      .then((rows) => setRulesets(rows || []))
      .catch((e: any) => {
        setRulesets([]);
        setRulesetError(e.response?.data?.message || e.message || 'Could not list rulesets.');
      })
      .finally(() => setRulesetLoading(false));
  };

  const choose = (repo: GitHubRepoOption) => {
    setSelected(repo);
    setNotice(null);
    loadRulesets(repo);
  };

  const loadMore = () => {
    if (!credentialId || !submitted || loadingMore || !hasMore) return;
    const next = page + 1;
    setLoadingMore(true);
    setListError(null);
    void pageCredentialRepositories(credentialId, {
      query: submitted.query,
      page: next,
      limit: submitted.limit,
    })
      .then((result) => {
        setRepos((current) => {
          const seen = new Set(current.map((row) => row.fullName));
          const extra = (result.items || []).filter((row) => !seen.has(row.fullName));
          return [...current, ...extra];
        });
        setPage(result.page || next);
        setHasMore(result.hasMore);
      })
      .catch((e: any) => setListError(e.response?.data?.message || e.message || 'Could not load the next page.'))
      .finally(() => setLoadingMore(false));
  };

  const setEnforcement = (row: RepositoryRulesetItem, enforcement: 'active' | 'disabled') => {
    if (!selected) return;
    setBusyId(row.id);
    setNotice(null);
    setRulesetError(null);
    void setRepositoryRulesetEnforcement({
      credentialId,
      repoFullName: selected.fullName,
      installationId: selected.installationId,
      rulesetId: row.id,
      enforcement,
    })
      .then(() => {
        setNotice(`${row.name} is ${enforcement === 'active' ? 'enforced' : 'off'}.`);
        loadRulesets(selected);
      })
      .catch((e: any) => setRulesetError(e.response?.data?.message || e.message || 'Could not update the ruleset.'))
      .finally(() => setBusyId(null));
  };

  const createReplica = () => {
    if (!selected) return;
    setBusyId('create');
    setNotice(null);
    setRulesetError(null);
    void applyWriteAuthority({
      repository: {
        credentialId,
        repoFullName: selected.fullName,
        installationId: selected.installationId,
        access: 'readonly',
      },
    })
      .then(() => {
        setNotice(`${HUB_RULESET} is enforced.`);
        loadRulesets(selected);
      })
      .catch((e: any) => setRulesetError(e.response?.data?.message || e.message || 'Could not create the replica ruleset.'))
      .finally(() => setBusyId(null));
  };

  if (!open) return null;

  const hubPresent = rulesets.some((row) => row.hubReplica || row.name === HUB_RULESET);
  const selectedApp = apps.find((row) => row.id === credentialId);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-zinc-900/40 p-4">
      <div className="flex h-[680px] w-[920px] max-h-[calc(100vh-2rem)] max-w-[calc(100vw-2rem)] flex-col overflow-hidden rounded-xl border border-zinc-200 bg-white shadow-xl">
        <div className="flex h-14 shrink-0 items-center justify-between border-b border-zinc-200 px-4">
          <div>
            <h2 className="text-sm font-semibold text-zinc-900">Repository rulesets</h2>
            <p className="text-xs text-zinc-500">One repository at a time. Each ruleset can be turned on or off.</p>
          </div>
          <button type="button" onClick={onClose} className="rounded-md p-1 text-zinc-500 hover:bg-zinc-100 hover:text-zinc-900" aria-label="Close">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="flex h-14 shrink-0 items-center gap-2 border-b border-zinc-200 px-4">
          <select
            value={credentialId}
            disabled={loadingApps || apps.length === 0}
            onChange={(event) => {
              setCredentialId(event.target.value);
              setSubmitted(null);
              setRepos([]);
              setSelected(null);
              setRulesets([]);
              setRulesetError(null);
              setNotice(null);
            }}
            className="h-8 w-52 shrink-0 rounded-md border border-zinc-200 bg-white px-2 text-sm text-zinc-900"
          >
            {apps.length === 0 ? <option value="">No GitHub App</option> : null}
            {apps.map((app) => (
              <option key={app.id} value={app.id}>
                {app.label} · App {app.appId || '—'}
              </option>
            ))}
          </select>
          <form
            className="flex min-w-0 flex-1 gap-1"
            onSubmit={(event) => {
              event.preventDefault();
              search();
            }}
          >
            <label className="relative min-w-0 flex-1">
              <Search className="pointer-events-none absolute left-2 top-2 h-3.5 w-3.5 text-zinc-400" />
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Installed repos, or is:public name"
                className="h-8 w-full rounded-md border border-zinc-200 pl-7 pr-2 text-sm text-zinc-900 placeholder:text-zinc-400"
              />
            </label>
            <select
              value={pageSize}
              onChange={(event) => {
                const next = Number(event.target.value) === 50 ? 50 : 15;
                setPageSize(next);
                setSubmitted((current) => (current ? { ...current, limit: next } : current));
              }}
              title="Repositories per page"
              className="h-8 shrink-0 rounded-md border border-zinc-200 bg-white px-1.5 text-xs text-zinc-700"
            >
              {PAGE_SIZE_OPTIONS.map((size) => (
                <option key={size} value={size}>
                  {size} / page
                </option>
              ))}
            </select>
            <button type="submit" className="h-8 shrink-0 rounded-md border border-zinc-300 px-2 text-xs font-medium text-zinc-800 hover:border-zinc-500">
              Search
            </button>
          </form>
        </div>

        <div className="grid min-h-0 flex-1 grid-cols-[320px_minmax(0,1fr)]">
          <div className="flex min-h-0 flex-col border-r border-zinc-200">
            <div className="min-h-0 flex-1 overflow-y-auto">
              {loading && repos.length === 0 ? (
                <div className="flex h-full items-center justify-center text-xs text-zinc-500">
                  <Loader2 className="mr-2 h-3.5 w-3.5 animate-spin" />
                  Loading repositories
                </div>
              ) : null}
              {!loading && repos.length === 0 ? (
                <div className="px-4 py-6 text-xs leading-5 text-zinc-500">
                  {listError
                    ? listError
                    : submitted?.query
                      ? 'No repositories match this search.'
                      : submitted
                        ? 'No installed repositories on this page.'
                        : 'Search loads repositories this App is installed on. Add is:public, is:private, or is:internal to search that visibility. * matches the rest of a name.'}
                </div>
              ) : null}
              {repos.map((repo) => {
                const on = selected?.fullName === repo.fullName && selected?.installationId === repo.installationId;
                return (
                  <button
                    key={`${repo.installationId || ''}:${repo.fullName}`}
                    type="button"
                    onClick={() => choose(repo)}
                    className={`flex h-11 w-full items-center justify-between gap-3 border-b border-zinc-100 px-4 text-left ${
                      on ? 'bg-zinc-100' : 'hover:bg-zinc-50'
                    }`}
                  >
                    <span className="truncate text-sm text-zinc-900">{repo.fullName}</span>
                    <span className="flex shrink-0 items-center gap-1">
                      <span className="text-[11px] text-zinc-500">{repoVisibility(repo)}</span>
                      {repo.appInstalled ? (
                        <span className="rounded border border-zinc-200 bg-zinc-50 px-1.5 py-0.5 text-[11px] text-zinc-700">Installed</span>
                      ) : null}
                    </span>
                  </button>
                );
              })}
            </div>
            <div className="flex h-10 shrink-0 items-center justify-between border-t border-zinc-200 px-4 text-xs text-zinc-500">
              <span className="truncate" title={listError || undefined}>
                {listError && repos.length > 0 ? listError : loading ? 'Refreshing…' : `${repos.length} shown`}
              </span>
              <button
                type="button"
                onClick={loadMore}
                disabled={!hasMore || loadingMore}
                className="font-medium text-zinc-800 disabled:text-zinc-300"
              >
                {loadingMore ? 'Loading…' : hasMore ? 'Load more' : 'End of list'}
              </button>
            </div>
          </div>

          <div className="flex min-h-0 flex-col">
            <div className="flex h-12 shrink-0 items-center justify-between border-b border-zinc-200 px-4">
              <div className="min-w-0">
                <div className="truncate text-sm font-medium text-zinc-900">
                  {selected ? selected.fullName : 'Select a repository'}
                </div>
                <div className="truncate text-[11px] text-zinc-500">
                  {selected
                    ? repoVisibility(selected) + (selected.appInstalled ? ' · Installed' : '') + ' · ' + (selectedApp?.label || 'GitHub App')
                    : 'Rulesets for the selected repository appear here.'}
                </div>
              </div>
              {rulesetLoading ? <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-zinc-400" /> : null}
            </div>

            <div className="grid h-8 shrink-0 grid-cols-[minmax(0,1fr)_88px_88px_88px] items-center border-b border-zinc-100 px-4 text-[11px] font-medium uppercase tracking-wide text-zinc-400">
              <span>Ruleset</span>
              <span>Target</span>
              <span>State</span>
              <span className="text-right">Action</span>
            </div>

            <div className="min-h-0 flex-1 overflow-y-auto">
              {!selected ? (
                <div className="px-4 py-6 text-xs text-zinc-500">Choose a repository on the left.</div>
              ) : null}
              {selected && rulesetError ? (
                <div className="px-4 py-3 text-xs leading-5 text-red-700">{rulesetError}</div>
              ) : null}
              {selected && !rulesetLoading && !rulesetError && rulesets.length === 0 ? (
                <div className="px-4 py-6 text-xs leading-5 text-zinc-500">
                  This repository has no rulesets. Add the replica ruleset to block pushes while the mirror app can still write.
                </div>
              ) : null}
              {rulesets.map((row) => (
                <div
                  key={row.id}
                  className="grid h-12 grid-cols-[minmax(0,1fr)_88px_88px_88px] items-center border-b border-zinc-100 px-4"
                >
                  <div className="min-w-0">
                    <div className="truncate text-sm text-zinc-900">{row.name}</div>
                    {row.hubReplica ? <div className="text-[11px] text-zinc-500">Replica ruleset</div> : null}
                  </div>
                  <span className="truncate text-xs text-zinc-600">{row.target || '—'}</span>
                  <span className={`w-fit rounded border px-1.5 py-0.5 text-[11px] ${stateClass(row.enforcement)}`}>
                    {stateLabel(row.enforcement)}
                  </span>
                  <div className="text-right">
                    <button
                      type="button"
                      disabled={busyId !== null}
                      onClick={() => setEnforcement(row, row.enforcement === 'active' ? 'disabled' : 'active')}
                      className="rounded-md border border-zinc-300 px-2 py-1 text-xs font-medium text-zinc-800 hover:border-zinc-500 disabled:opacity-40"
                    >
                      {busyId === row.id ? 'Saving…' : row.enforcement === 'active' ? 'Turn off' : 'Turn on'}
                    </button>
                  </div>
                </div>
              ))}
            </div>

            <div className="flex h-14 shrink-0 items-center justify-between gap-3 border-t border-zinc-200 px-4">
              <p className="min-w-0 truncate text-xs text-zinc-500" title={notice || undefined}>
                {notice || (selected && hubPresent ? 'The replica ruleset is already on this repository.' : 'Turn on and turn off update GitHub immediately. Create on GitHub adds gitmirror-replica-readonly and enforces it.')}
              </p>
              <button
                type="button"
                disabled={!selected || hubPresent || busyId !== null}
                onClick={createReplica}
                className="shrink-0 rounded-md bg-zinc-900 px-3 py-1.5 text-xs font-medium text-white disabled:bg-zinc-200 disabled:text-zinc-400"
              >
                {busyId === 'create' ? 'Creating…' : 'Create on GitHub'}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
