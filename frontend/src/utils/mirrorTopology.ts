import { RepoMapping, SyncDirection } from '../types';

export function isPublicToPrivateBackup(
  mapping: Pick<RepoMapping, 'sourceVisibility' | 'targetVisibility'>
): boolean {
  return mapping.sourceVisibility === 'PUBLIC' && mapping.targetVisibility === 'PRIVATE';
}

export function shouldWarnBidirectionalBackup(
  sourceVisibility: RepoMapping['sourceVisibility'] | undefined,
  targetVisibility: RepoMapping['targetVisibility'] | undefined,
  syncDirection: SyncDirection | undefined
): boolean {
  return sourceVisibility === 'PUBLIC'
    && targetVisibility === 'PRIVATE'
    && (syncDirection === 'BIDIRECTIONAL' || !syncDirection);
}
