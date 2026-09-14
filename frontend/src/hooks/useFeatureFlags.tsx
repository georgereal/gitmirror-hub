import React, { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { FeatureFlags } from '../types';
import { getFeatureFlags } from '../services/api';

const defaults: FeatureFlags = {
  publicReposEnabled: true,
  providerGitlabEnabled: false,
  providerBitbucketEnabled: false,
  providerOriginEnabled: false,
  providerGenericEnabled: false,
};

type Ctx = {
  flags: FeatureFlags;
  loading: boolean;
  refresh: () => Promise<void>;
  setFlagsLocal: (flags: FeatureFlags) => void;
};

const FeatureFlagsContext = createContext<Ctx>({
  flags: defaults,
  loading: true,
  refresh: async () => {},
  setFlagsLocal: () => {},
});

export const FeatureFlagsProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [flags, setFlags] = useState<FeatureFlags>(defaults);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    try {
      const next = await getFeatureFlags();
      setFlags(next);
    } catch {
      // Keep last-known / defaults if API unavailable
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return (
    <FeatureFlagsContext.Provider value={{ flags, loading, refresh, setFlagsLocal: setFlags }}>
      {children}
    </FeatureFlagsContext.Provider>
  );
};

export const useFeatureFlags = () => useContext(FeatureFlagsContext);

export const isProviderUiEnabled = (flags: FeatureFlags, provider: string): boolean => {
  const key = (provider || '').toUpperCase();
  switch (key) {
    case 'GITHUB':
    case 'GHES':
    case 'GITHUB_ENTERPRISE':
      return true;
    case 'GITLAB':
      return !!flags.providerGitlabEnabled;
    case 'BITBUCKET':
      return !!flags.providerBitbucketEnabled;
    case 'ORIGIN':
      return !!flags.providerOriginEnabled;
    case 'GENERIC':
      return !!flags.providerGenericEnabled;
    default:
      return true;
  }
};
