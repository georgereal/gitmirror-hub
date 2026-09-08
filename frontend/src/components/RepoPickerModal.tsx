import React, { useState, useEffect, useRef } from 'react';
import {
  Search, X, Globe, GitFork, Layers, Lock, Check,
  Loader2, ArrowRight, RefreshCw, Filter, Server
} from 'lucide-react';
import { GitHubRepoOption, RepoSearchResult, ScmCredential } from '../types';
import { searchRemoteRepositories, listScmCredentials, searchCredentialRepositories } from '../services/api';

interface RepoPickerModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSelectRepo: (repo: GitHubRepoOption) => void;
  title?: string;
  defaultProvider?: string;
  access?: 'PULL' | 'PUSH';
}

const PROVIDERS = ['GITHUB', 'GHES', 'GITLAB', 'BITBUCKET', 'ORIGIN'] as const;

export const RepoPickerModal: React.FC<RepoPickerModalProps> = ({
  isOpen,
  onClose,
  onSelectRepo,
  title = 'Select repository',
  defaultProvider = 'GITHUB',
  access = 'PULL',
}) => {
  const [query, setQuery] = useState('');
  const [provider, setProvider] = useState<string>(defaultProvider === 'ALL' ? 'GITHUB' : defaultProvider);
  const [credentials, setCredentials] = useState<ScmCredential[]>([]);
  const [credentialId, setCredentialId] = useState<number | ''>('');
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [repos, setRepos] = useState<GitHubRepoOption[]>([]);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const debounceTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);

  const needsCredential = provider === 'GITHUB' || provider === 'GHES';

  useEffect(() => {
    if (!isOpen) return;
    setQuery('');
    setPage(1);
    setRepos([]);
    if (needsCredential) {
      const p = provider === 'GHES' ? 'GITHUB_ENTERPRISE' : 'GITHUB';
      listScmCredentials(p).then((rows) => {
        setCredentials(rows.filter((r) => r.enabled));
        setCredentialId(rows.find((r) => r.enabled)?.id ?? '');
      }).catch(() => setCredentials([]));
    } else {
      setCredentials([]);
      setCredentialId('');
      fetchRepos('', provider, 1, false, undefined);
    }
  }, [isOpen, provider]);

  useEffect(() => {
    if (!isOpen || !needsCredential || credentialId === '') return;
    fetchRepos(query, provider, 1, false, Number(credentialId));
  }, [credentialId, isOpen]);

  const fetchRepos = async (
    searchQuery: string,
    activeProvider: string,
    pageNum: number,
    append = false,
    cred?: number
  ) => {
    if (pageNum === 1) setLoading(true);
    else setLoadingMore(true);
    setError(null);
    try {
      let res: RepoSearchResult;
      if (cred) {
        res = await searchCredentialRepositories(cred, {
          query: searchQuery,
          page: pageNum,
          limit: 15,
          access,
        });
      } else {
        res = await searchRemoteRepositories({
          query: searchQuery,
          provider: activeProvider,
          page: pageNum,
          limit: 15,
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

  const handleQueryChange = (val: string) => {
    setQuery(val);
    if (debounceTimeout.current) clearTimeout(debounceTimeout.current);
    debounceTimeout.current = setTimeout(() => {
      setPage(1);
      fetchRepos(val, provider, 1, false, credentialId === '' ? undefined : Number(credentialId));
    }, 300);
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

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 bg-black/50 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="bg-white border border-zinc-200 rounded-2xl w-full max-w-2xl shadow-2xl overflow-hidden flex flex-col my-auto max-h-[85vh]">
        <div className="px-6 py-4 border-b border-zinc-100 flex items-center justify-between shrink-0">
          <div>
            <h3 className="text-sm font-semibold text-zinc-900">{title}</h3>
            <p className="text-xs text-zinc-500 mt-0.5">
              Scoped by provider{needsCredential ? ' and credential' : ''}. {access === 'PUSH' ? 'Write access required.' : 'Read access required.'}
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
              onChange={(e) => setCredentialId(e.target.value ? Number(e.target.value) : '')}
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
          <div className="relative">
            <Search className="w-4 h-4 text-zinc-400 absolute left-3.5 top-1/2 -translate-y-1/2" />
            <input
              type="text"
              value={query}
              onChange={(e) => handleQueryChange(e.target.value)}
              placeholder="Search by repo name or org..."
              className="w-full bg-white border border-zinc-200 rounded-xl pl-9 pr-4 py-2 text-xs"
              autoFocus
            />
          </div>
        </div>

        <div className="flex-1 overflow-y-auto p-4 space-y-2">
          {loading ? (
            <div className="py-12 flex flex-col items-center space-y-2 text-zinc-400 text-xs">
              <Loader2 className="w-6 h-6 animate-spin" />
              <span>Loading repositories…</span>
            </div>
          ) : error ? (
            <p className="py-12 text-center text-rose-600 text-xs px-6">{error}</p>
          ) : needsCredential && credentialId === '' ? (
            <p className="py-12 text-center text-zinc-500 text-xs">Select a credential to list repositories it can access.</p>
          ) : repos.length === 0 ? (
            <p className="py-12 text-center text-zinc-500 text-xs px-6">
              {query.trim()
                ? `No repositories matching “${query.trim()}” for this credential. Clear the search to list all repos the App can access.`
                : 'No repositories for this credential. On GitHub, open the App installation and grant it the repositories you want to mirror.'}
            </p>
          ) : (
            <>
              {repos.map((repo) => (
                <div
                  key={repo.id || repo.cloneUrl}
                  onClick={() => {
                    onSelectRepo({ ...repo, credentialId: repo.credentialId || (credentialId === '' ? undefined : Number(credentialId)) });
                    onClose();
                  }}
                  className="p-3 bg-white hover:bg-zinc-50 border border-zinc-200 rounded-xl cursor-pointer flex items-center justify-between"
                >
                  <div className="flex items-start space-x-3 min-w-0">
                    <div className="p-2 bg-zinc-50 rounded-lg border">{renderProviderIcon(repo.provider)}</div>
                    <div className="min-w-0">
                      <div className="font-semibold text-zinc-900 text-xs truncate">{repo.fullName}</div>
                      <p className="text-[11px] font-mono text-zinc-400 truncate">{repo.cloneUrl}</p>
                    </div>
                  </div>
                  <span className="text-[11px] flex items-center space-x-1 text-zinc-600">
                    <span>Select</span>
                    <ArrowRight className="w-3 h-3" />
                  </span>
                </div>
              ))}
              {hasMore && (
                <button
                  type="button"
                  onClick={() => fetchRepos(query, provider, page + 1, true, credentialId === '' ? undefined : Number(credentialId))}
                  disabled={loadingMore}
                  className="px-4 py-2 bg-zinc-100 rounded-xl text-xs mx-auto block"
                >
                  {loadingMore ? 'Loading…' : 'Load more'}
                </button>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
};
