import React, { useEffect, useState } from 'react';
import { Save, ToggleLeft, AlertCircle, Check } from 'lucide-react';
import { FeatureFlags } from '../../types';
import { getFeatureFlags, saveFeatureFlags } from '../../services/api';
import { useFeatureFlags } from '../../hooks/useFeatureFlags';
import { InfoTooltip } from '../../components/InfoTooltip';

type FlagKey = keyof Omit<FeatureFlags, 'updatedAt'>;

const FLAG_META: { key: FlagKey; label: string; description: string; prodHint: string }[] = [
  {
    key: 'publicReposEnabled',
    label: 'Public repositories',
    description: 'Allow pairing public GitHub sources via anonymous HTTPS (Public visibility / Add repo without a credential).',
    prodHint: 'Turn off in production. Test/demo only.',
  },
  {
    key: 'providerGitlabEnabled',
    label: 'GitLab provider',
    description: 'Show GitLab in Settings, repo picker, and smart mirror suggestions.',
    prodHint: 'Off by default until GitLab is ready for prod.',
  },
  {
    key: 'providerBitbucketEnabled',
    label: 'Bitbucket provider',
    description: 'Show Bitbucket in Settings, repo picker, and smart mirror suggestions.',
    prodHint: 'Off by default until Bitbucket is ready for prod.',
  },
  {
    key: 'providerOriginEnabled',
    label: 'Cursor Origin provider',
    description: 'Show Origin in Settings, repo picker, and smart mirror suggestions.',
    prodHint: 'Off by default until Origin is ready for prod.',
  },
  {
    key: 'providerGenericEnabled',
    label: 'Azure DevOps / Generic Git',
    description: 'Show Generic / Azure DevOps in Settings provider tabs.',
    prodHint: 'Off by default until generic Git is ready for prod.',
  },
];

export const FeatureTogglesPage: React.FC = () => {
  const { refresh, setFlagsLocal } = useFeatureFlags();
  const [form, setForm] = useState<FeatureFlags | null>(null);
  const [saving, setSaving] = useState(false);
  const [feedback, setFeedback] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        setForm(await getFeatureFlags());
      } catch (e: any) {
        setFeedback({ type: 'err', text: e.response?.data?.error || e.message });
      }
    })();
  }, []);

  const toggle = (key: FlagKey) => {
    setForm((prev) => (prev ? { ...prev, [key]: !prev[key] } : prev));
  };

  const save = async () => {
    if (!form) return;
    setSaving(true);
    setFeedback(null);
    try {
      const saved = await saveFeatureFlags({
        publicReposEnabled: form.publicReposEnabled,
        providerGitlabEnabled: form.providerGitlabEnabled,
        providerBitbucketEnabled: form.providerBitbucketEnabled,
        providerOriginEnabled: form.providerOriginEnabled,
        providerGenericEnabled: form.providerGenericEnabled,
      });
      setForm(saved);
      setFlagsLocal(saved);
      await refresh();
      setFeedback({ type: 'ok', text: 'Feature toggles saved. Disabled paths are hidden in the UI and blocked by the API.' });
    } catch (e: any) {
      setFeedback({ type: 'err', text: e.response?.data?.error || e.message });
    } finally {
      setSaving(false);
    }
  };

  if (!form) {
    return <p className="text-xs text-zinc-500">Loading feature toggles…</p>;
  }

  return (
    <div className="space-y-5 text-xs max-w-2xl">
      <div className="flex items-start justify-between gap-4">
        <div>
          <div className="flex items-center gap-2">
            <ToggleLeft className="w-4 h-4 text-zinc-700" />
            <h3 className="font-semibold text-zinc-900 text-sm">Feature toggles</h3>
            <InfoTooltip
              title="Feature toggles"
              whatIsIt="Product capability switches for this Hub deployment."
              howItWorks="Saved in the Hub database (shared across pods). Env vars FEATURE_PUBLIC_REPOS / FEATURE_PROVIDER_* only seed the first row. Disabled features are removed from the UI and rejected by the backend."
            />
          </div>
          <p className="text-zinc-500 mt-1 leading-relaxed">
            GitHub and GHES stay available. Use these switches for test-only capabilities (public repos) and providers
            that are not ready for production. This list will grow.
          </p>
        </div>
        <button
          type="button"
          disabled={saving}
          onClick={save}
          className="shrink-0 inline-flex items-center space-x-1.5 bg-zinc-900 hover:bg-zinc-800 text-white text-xs px-4 py-2 rounded-lg font-medium disabled:opacity-50"
        >
          <Save className="w-3.5 h-3.5" />
          <span>{saving ? 'Saving…' : 'Save toggles'}</span>
        </button>
      </div>

      {feedback && (
        <div
          className={`flex items-start gap-2 rounded-xl border px-3 py-2.5 ${
            feedback.type === 'ok'
              ? 'bg-emerald-50 border-emerald-200 text-emerald-800'
              : 'bg-rose-50 border-rose-200 text-rose-800'
          }`}
        >
          {feedback.type === 'ok' ? <Check className="w-3.5 h-3.5 mt-0.5 shrink-0" /> : <AlertCircle className="w-3.5 h-3.5 mt-0.5 shrink-0" />}
          <p className="leading-relaxed">{feedback.text}</p>
        </div>
      )}

      <div className="rounded-xl border border-zinc-200 bg-white divide-y divide-zinc-100">
        {FLAG_META.map((meta) => {
          const on = !!form[meta.key];
          return (
            <label
              key={meta.key}
              className="flex items-start gap-3 px-4 py-3.5 cursor-pointer hover:bg-zinc-50/80"
            >
              <input
                type="checkbox"
                className="mt-1 rounded border-zinc-300"
                checked={on}
                onChange={() => toggle(meta.key)}
              />
              <div className="min-w-0 flex-1 space-y-1">
                <div className="flex items-center gap-2">
                  <span className="font-semibold text-zinc-900">{meta.label}</span>
                  <span
                    className={`text-[10px] font-medium px-1.5 py-0.5 rounded-md border ${
                      on
                        ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                        : 'bg-zinc-100 text-zinc-500 border-zinc-200'
                    }`}
                  >
                    {on ? 'On' : 'Off'}
                  </span>
                </div>
                <p className="text-zinc-600 leading-relaxed">{meta.description}</p>
                <p className="text-[10px] text-amber-800/90">{meta.prodHint}</p>
              </div>
            </label>
          );
        })}
      </div>
    </div>
  );
};
