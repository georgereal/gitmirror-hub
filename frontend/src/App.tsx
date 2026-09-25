import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router';
import { Header } from './components/Header';
import { RepositoriesPage } from './pages/RepositoriesPage';
import { RepoDetailPage } from './pages/RepoDetailPage';
import { ObservabilityPage } from './pages/ObservabilityPage';
import { InternalsPage } from './pages/InternalsPage';
import { QueueManagerPage } from './pages/QueueManagerPage';
import { KafkaWebhookPage } from './pages/KafkaWebhookPage';
import { SimulationPage } from './pages/SimulationPage';
import { SettingsLayout } from './pages/settings/SettingsLayout';
import { ProvidersAuthPage } from './pages/settings/ProvidersAuthPage';
import { FeatureTogglesPage } from './pages/settings/FeatureTogglesPage';
import { MetadataSyncPage } from './pages/settings/MetadataSyncPage';
import { SystemEnginePage } from './pages/settings/SystemEnginePage';
import { StorageSettingsPage } from './pages/settings/StorageSettingsPage';
import { EnterpriseLoggingPage } from './pages/settings/EnterpriseLoggingPage';
import { WriteAuthorityPage } from './pages/settings/WriteAuthorityPage';
import { DisasterRecoveryPage } from './pages/settings/DisasterRecoveryPage';
import { FeatureFlagsProvider } from './hooks/useFeatureFlags';

export const App: React.FC = () => {
  return (
    <BrowserRouter>
      <FeatureFlagsProvider>
        <div className="min-h-screen bg-[#fafafa] flex flex-col selection:bg-zinc-900 selection:text-white">
          <Header />

          <main className="flex-1 max-w-6xl w-full mx-auto px-6 sm:px-10 py-8 space-y-8">
            <Routes>
              <Route path="/" element={<Navigate to="/repos" replace />} />

              <Route path="/repos" element={<RepositoriesPage />} />
              <Route path="/repos/:id" element={<RepoDetailPage />} />

              <Route path="/observability" element={<ObservabilityPage />} />
              <Route path="/observability/internals" element={<InternalsPage />} />
              <Route path="/queues" element={<QueueManagerPage />} />
              <Route path="/kafka" element={<KafkaWebhookPage />} />

              <Route path="/simulation" element={<SimulationPage />} />

              <Route path="/settings" element={<SettingsLayout />}>
                <Route index element={<Navigate to="/settings/providers" replace />} />
                <Route path="providers" element={<ProvidersAuthPage />} />
                <Route path="dr" element={<DisasterRecoveryPage />} />
                <Route path="write-authority" element={<WriteAuthorityPage />} />
                <Route path="feature-toggles" element={<FeatureTogglesPage />} />
                <Route path="metadata" element={<MetadataSyncPage />} />
                <Route path="system-engine" element={<SystemEnginePage />} />
                <Route path="storage" element={<StorageSettingsPage />} />
                <Route path="logging" element={<EnterpriseLoggingPage />} />
              </Route>

              <Route path="*" element={<Navigate to="/repos" replace />} />
            </Routes>
          </main>
        </div>
      </FeatureFlagsProvider>
    </BrowserRouter>
  );
};

export default App;
