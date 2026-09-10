import React, { useEffect, useState } from 'react';
import { X, ShieldCheck, Loader2 } from 'lucide-react';
import { ScmCredential } from '../types';
import { listScmCredentials } from '../services/api';

const formatCredentialOption = (c: ScmCredential) =>
  `${c.label}${c.accountLogin ? ` · ${c.accountLogin}` : ''} (${c.authMode === 'GITHUB_APP' ? 'App' : 'PAT'})`;

interface CredentialPickModalProps {
  isOpen: boolean;
  title?: string;
  reason?: string;
  provider?: 'GITHUB' | 'GITHUB_ENTERPRISE';
  initialCredentialId?: number;
  confirmLabel?: string;
  onCancel: () => void;
  onConfirm: (credentialId: number) => void;
}

export const CredentialPickModal: React.FC<CredentialPickModalProps> = ({
  isOpen,
  title = 'Select GitHub credential',
  reason = 'Authenticated access is required. Choose a GitHub App or Personal Access Token.',
  provider = 'GITHUB',
  initialCredentialId,
  confirmLabel = 'Check Access',
  onCancel,
  onConfirm,
}) => {
  const [credentials, setCredentials] = useState<ScmCredential[]>([]);
  const [selectedId, setSelectedId] = useState<number | ''>('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isOpen) return;
    setLoading(true);
    setError(null);
    listScmCredentials(provider)
      .then((rows) => {
        const enabled = rows.filter((r) => r.enabled);
        setCredentials(enabled);
        const preferred =
          initialCredentialId != null && enabled.some((c) => c.id === initialCredentialId)
            ? initialCredentialId
            : enabled[0]?.id;
        setSelectedId(preferred ?? '');
      })
      .catch((e: any) => {
        setCredentials([]);
        setSelectedId('');
        setError(e.message || 'Failed to load credentials');
      })
      .finally(() => setLoading(false));
  }, [isOpen, provider, initialCredentialId]);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-[60] bg-black/50 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="bg-white border border-zinc-200 rounded-2xl w-full max-w-md shadow-2xl overflow-hidden">
        <div className="px-5 py-4 border-b border-zinc-100 flex items-center justify-between">
          <div>
            <h3 className="text-sm font-semibold text-zinc-900">{title}</h3>
            <p className="text-xs text-zinc-500 mt-0.5">{reason}</p>
          </div>
          <button
            type="button"
            onClick={onCancel}
            className="p-1.5 text-zinc-400 hover:text-zinc-700 rounded-lg hover:bg-zinc-100"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="p-5 space-y-3">
          {loading ? (
            <div className="py-8 flex flex-col items-center text-zinc-400 text-xs space-y-2">
              <Loader2 className="w-5 h-5 animate-spin" />
              <span>Loading credentials…</span>
            </div>
          ) : error ? (
            <p className="text-xs text-rose-600">{error}</p>
          ) : credentials.length === 0 ? (
            <p className="text-xs text-zinc-600">
              No enabled GitHub credentials found. Add a GitHub App or PAT in Settings → Providers.
            </p>
          ) : (
            <select
              value={selectedId}
              onChange={(e) => setSelectedId(e.target.value ? Number(e.target.value) : '')}
              className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2.5 text-xs text-zinc-800 focus:outline-none focus:border-zinc-400"
              autoFocus
            >
              {credentials.map((c) => (
                <option key={c.id} value={c.id}>
                  {formatCredentialOption(c)}
                </option>
              ))}
            </select>
          )}
        </div>

        <div className="px-5 py-4 border-t border-zinc-100 flex justify-end space-x-2">
          <button
            type="button"
            onClick={onCancel}
            className="px-3 py-2 text-xs font-medium rounded-lg border border-zinc-200 text-zinc-700 hover:bg-zinc-50"
          >
            Cancel
          </button>
          <button
            type="button"
            disabled={selectedId === '' || loading}
            onClick={() => {
              if (selectedId === '') return;
              onConfirm(Number(selectedId));
            }}
            className="inline-flex items-center space-x-1.5 px-3 py-2 text-xs font-medium rounded-lg bg-zinc-900 text-white hover:bg-zinc-800 disabled:opacity-40"
          >
            <ShieldCheck className="w-3.5 h-3.5" />
            <span>{confirmLabel}</span>
          </button>
        </div>
      </div>
    </div>
  );
};
