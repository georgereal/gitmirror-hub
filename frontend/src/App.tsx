import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router';
import { Header } from './components/Header';
import { RepositoriesPage } from './pages/RepositoriesPage';
import { RepoDetailPage } from './pages/RepoDetailPage';
import { ObservabilityPage } from './pages/ObservabilityPage';
import { InternalsPage } from './pages/InternalsPage';
import { QueueManagerPage } from './pages/QueueManagerPage';
import { SimulationPage } from './pages/SimulationPage';
import { SettingsLayout } from './pages/settings/SettingsLayout';
import { ProvidersAuthPage } from './pages/settings/ProvidersAuthPage';
import { SystemEnginePage } from './pages/settings/SystemEnginePage';
import { StorageSettingsPage } from './pages/settings/StorageSettingsPage';
import { EnterpriseLoggingPage } from './pages/settings/EnterpriseLoggingPage';

export const App: React.FC = () => {
  return (
    <BrowserRouter>
      <div className="min-h-screen bg-[#fafafa] flex flex-col selection:bg-zinc-900 selection:text-white">
        <Header />

        <main className="flex-1 max-w-6xl w-full mx-auto px-6 sm:px-10 py-8 space-y-8">
          <Routes>
            {/* Root redirect to Repositories */}
            <Route path="/" element={<Navigate to="/repos" replace />} />

            {/* Repositories domain */}
            <Route path="/repos" element={<RepositoriesPage />} />
            <Route path="/repos/:id" element={<RepoDetailPage />} />

            {/* Observability & Queue Control */}
            <Route path="/observability" element={<ObservabilityPage />} />
            <Route path="/observability/internals" element={<InternalsPage />} />
            <Route path="/queues" element={<QueueManagerPage />} />

            {/* Simulation Lab */}
            <Route path="/simulation" element={<SimulationPage />} />

            {/* Dedicated Settings Layout & Pages */}
            <Route path="/settings" element={<SettingsLayout />}>
              <Route index element={<Navigate to="/settings/providers" replace />} />
              <Route path="providers" element={<ProvidersAuthPage />} />
              <Route path="system-engine" element={<SystemEnginePage />} />
              <Route path="storage" element={<StorageSettingsPage />} />
              <Route path="logging" element={<EnterpriseLoggingPage />} />
            </Route>

            {/* Catch-all fallback */}
            <Route path="*" element={<Navigate to="/repos" replace />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  );
};

export default App;
