import { useEffect, useState } from 'react';
import { getPersistenceModule } from '../services/api';
import { PersistenceModuleInfo } from '../types';

const DEFAULT_H2: PersistenceModuleInfo = {
  provider: 'h2',
  displayName: 'H2 (embedded file)',
  description: 'File-based H2 with Spring Data JPA. Local dev and multi-pod smoke; data lives in ./data/gitutility.',
  fileBacked: true,
  externalStore: false,
  requiresConnection: false,
  supportsConsole: true,
};

/**
 * Loads the active persistence store so Settings can show H2 vs MongoDB.
 */
export function usePersistenceModule() {
  const [persistence, setPersistence] = useState<PersistenceModuleInfo | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    getPersistenceModule()
      .then((p) => {
        if (!cancelled) setPersistence(p);
      })
      .catch(() => {
        if (!cancelled) setPersistence(DEFAULT_H2);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return { persistence: persistence ?? DEFAULT_H2, loading, known: persistence != null };
}
