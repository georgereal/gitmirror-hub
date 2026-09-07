/**
 * Canonical repository URL normalizer.
 * Normalizes HTTPS, SSH, .git suffixes, and credentials for collision detection.
 */
export function normalizeRepoKey(url?: string | null): string {
  if (!url) return '';
  let s = url.trim().toLowerCase();
  s = s.replace(/\/+$/, '');
  s = s.replace(/\.git$/, '');
  s = s.replace(/^(https?|ssh|git):\/\//, '');
  s = s.replace(/^git@([^:]+):/, '$1/');
  s = s.replace(/^[^@/]+@/, '');
  return s;
}

export function findRepoCollision(
  candUrl: string,
  existingMappings: Array<{ id: number; name: string; repoAUrl: string; repoBUrl: string; active?: boolean }>,
  currentMappingId?: number
): { pairName: string; matchingField: 'Source' | 'Destination' } | null {
  const norm = normalizeRepoKey(candUrl);
  if (!norm) return null;

  for (const m of existingMappings) {
    if (currentMappingId != null && m.id === currentMappingId) continue;
    if (m.active === false) continue;

    if (normalizeRepoKey(m.repoAUrl) === norm) {
      return { pairName: m.name, matchingField: 'Source' };
    }
    if (normalizeRepoKey(m.repoBUrl) === norm) {
      return { pairName: m.name, matchingField: 'Destination' };
    }
  }
  return null;
}
