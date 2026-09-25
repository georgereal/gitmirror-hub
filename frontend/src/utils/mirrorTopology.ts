import { RepoMapping, SyncDirection } from '../types';

/** Source is readable without a credential, so Hub cannot subscribe to its events or push to it. */
export function isAnonymousPublicSource(
  mapping: Pick<RepoMapping, 'sourceCredentialId' | 'sourcePublicRead' | 'sourceVisibility'>
): boolean {
  if (mapping.sourceCredentialId) return false;
  return mapping.sourcePublicRead === true || mapping.sourceVisibility === 'PUBLIC';
}

export function shouldWarnBidirectionalBackup(
  mapping: Pick<RepoMapping, 'sourceCredentialId' | 'sourcePublicRead' | 'sourceVisibility'>,
  syncDirection: SyncDirection | undefined
): boolean {
  return isAnonymousPublicSource(mapping)
    && (syncDirection === 'BIDIRECTIONAL' || syncDirection === 'UNIDIRECTIONAL_B_TO_A' || !syncDirection);
}
