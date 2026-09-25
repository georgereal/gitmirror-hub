import { GitHubRepoOption, PermissionCheckReport } from '../types';

export type RepoVisibilityState = 'UNKNOWN' | 'PUBLIC' | 'PRIVATE' | 'INTERNAL';

export function visibilityFromReport(res: PermissionCheckReport | null | undefined): RepoVisibilityState {
  if (!res) return 'UNKNOWN';
  const raw = (res.visibility || '').toUpperCase();
  if (raw === 'PUBLIC' || raw === 'PRIVATE' || raw === 'INTERNAL') return raw;
  if (res.isPrivate === true || (res as { private?: boolean }).private === true) return 'PRIVATE';
  if (res.accessMode === 'PUBLIC') return 'PUBLIC';
  return 'UNKNOWN';
}

export function visibilityFromRepo(repo: Pick<GitHubRepoOption, 'visibility' | 'isPrivate'> | null | undefined): RepoVisibilityState {
  if (!repo) return 'UNKNOWN';
  const raw = (repo.visibility || '').toLowerCase();
  if (raw === 'public') return 'PUBLIC';
  if (raw === 'private') return 'PRIVATE';
  if (raw === 'internal') return 'INTERNAL';
  if (repo.isPrivate) return 'PRIVATE';
  return 'UNKNOWN';
}

export function visibilityPresentation(v: RepoVisibilityState | undefined, checked: boolean): { text: string; className: string } {
  if (!checked || !v || v === 'UNKNOWN') {
    return { text: 'Not checked', className: 'bg-zinc-100 text-zinc-500 border-zinc-200' };
  }
  if (v === 'PUBLIC') return { text: 'Public', className: 'bg-sky-50 text-sky-800 border-sky-200' };
  if (v === 'INTERNAL') return { text: 'Internal', className: 'bg-violet-50 text-violet-800 border-violet-200' };
  return { text: 'Private', className: 'bg-zinc-100 text-zinc-800 border-zinc-200' };
}

/** Public repository the chosen credential could not access — read-only, so sync cannot write back. */
export function isPublicOnlyAccess(
  visibility: RepoVisibilityState | undefined,
  credentialId?: string | null,
  accessMode?: string | null
): boolean {
  if (accessMode === 'AUTHENTICATED') return false;
  if (accessMode === 'PUBLIC') return true;
  return visibility === 'PUBLIC' && (credentialId == null || credentialId === '');
}
