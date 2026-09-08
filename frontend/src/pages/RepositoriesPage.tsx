import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router';
import { BannerHero } from '../components/BannerHero';
import { RepoListView } from '../components/RepoListView';
import { PairConfigModal } from '../components/PairConfigModal';
import { RepoMapping } from '../types';
import { getMappings, createMapping, updateMapping, deleteMapping, triggerManualSync } from '../services/api';

export const RepositoriesPage: React.FC = () => {
  const [mappings, setMappings] = useState<RepoMapping[]>([]);
  const [loading, setLoading] = useState(false);
  const [isPairModalOpen, setIsPairModalOpen] = useState(false);
  const [editingMapping, setEditingMapping] = useState<RepoMapping | null>(null);

  const navigate = useNavigate();

  const loadData = async () => {
    setLoading(true);
    try {
      const m = await getMappings();
      setMappings(m);
    } catch (e) {
      console.error('Failed to load repositories:', e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  const handleNewPair = () => {
    setEditingMapping(null);
    setIsPairModalOpen(true);
  };

  const handleDeletePair = async (id: number) => {
    const mapping = mappings.find((m) => m.id === id);
    const label = mapping?.name || `pair #${id}`;
    if (!confirm(`Remove "${label}" from mirror?\n\nThis stops sync for this pair. Remote Git repositories are not deleted.`)) {
      return;
    }
    try {
      await deleteMapping(id);
      await loadData();
    } catch (e) {
      console.error('Failed to delete pair:', e);
    }
  };

  const handleTriggerSync = async (id: number, branch?: string) => {
    try {
      await triggerManualSync(id, branch || '*');
    } catch (e) {
      console.error('Failed to trigger sync:', e);
    }
  };

  const handleSavePair = async (payload: Partial<RepoMapping>) => {
    if (editingMapping && editingMapping.id) {
      await updateMapping(editingMapping.id, payload);
    } else {
      await createMapping(payload);
    }
    await loadData();
  };

  return (
    <div className="space-y-8">
      <BannerHero onSyncFromGitHub={handleNewPair} />
      <RepoListView
        mappings={mappings}
        onSelectRepo={(repo) => navigate(`/repos/${repo.id}`)}
        onNewPair={handleNewPair}
        onSyncFromGitHub={handleNewPair}
        onDeletePair={handleDeletePair}
        onTriggerSync={handleTriggerSync}
      />

      <PairConfigModal
        mapping={editingMapping}
        isOpen={isPairModalOpen}
        onClose={() => {
          setIsPairModalOpen(false);
          setEditingMapping(null);
        }}
        onSave={handleSavePair}
        existingMappings={mappings}
      />
    </div>
  );
};
