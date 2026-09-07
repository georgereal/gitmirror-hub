import { useCallback, useEffect, useState } from 'react';
import { ClusterRuntimeMetrics } from '../types';
import { getClusterRuntimeMetrics } from '../services/api';

/** Shared poll of GET /runtime-metrics/cluster for Observability + Internals. */
export function useClusterRuntimeMetrics(intervalMs = 3000) {
  const [cluster, setCluster] = useState<ClusterRuntimeMetrics | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getClusterRuntimeMetrics();
      setCluster(data);
      setError(null);
    } catch (e) {
      console.error('Failed to load cluster runtime metrics:', e);
      setError('Could not load cluster runtime metrics');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
    const timer = setInterval(refresh, intervalMs);
    return () => clearInterval(timer);
  }, [refresh, intervalMs]);

  return { cluster, error, loading, refresh };
}
