import React, { useEffect, useState } from 'react';
import { Layers, Save, AlertCircle, Check } from 'lucide-react';
import { MetadataSyncSettings } from '../../types';
import { getMetadataSyncSettings, saveMetadataSyncSettings } from '../../services/api';
import { InfoTooltip } from '../../components/InfoTooltip';

type SettingKey = keyof Omit<MetadataSyncSettings, 'updatedAt'>;

const SETTING_META: { key: SettingKey; label: string; description: string }[] = [
  {
    key: 'pullRequestsEnabled',
    label: 'Pull requests',
    description: 'Full sync, the pair-page action, and pull_request webhooks.',
  },
  {
    key: 'releasesEnabled',
    label: 'Releases and assets',
    description: 'Full sync, the pair-page action, and release webhooks. Deleting or unpublishing a release is recorded as skipped.',
  },
  {
    key: 'ciChecksEnabled',
    label: 'CI checks',
    description: 'Commit statuses and completed check runs, on full sync and on status / check_run webhooks.',
  },
  {
    key: 'lfsEnabled',
    label: 'Git LFS',
    description: 'Full-mirror blob transfer, and a pointer scan of the commits in each push.',
  },
];

export const MetadataSyncPage: React.FC = () => {
  const [form, setForm] = useState<MetadataSyncSettings | null>(null);
  const [saving, setSaving] = useState(false);
  const [feedback, setFeedback] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        setForm(await getMetadataSyncSettings());
      } catch (e: any) {
        setFeedback({ type: 'err', text: e.response?.data?.error || e.message });
      }
    })();
  }, []);

  const toggle = (key: SettingKey) => {
    setForm((prev) => (prev ? { ...prev, [key]: !prev[key] } : prev));
  };

  const save = async () => {
    if (!form) return;
    setSaving(true);
    setFeedback(null);
    try {
      const saved = await saveMetadataSyncSettings({
        pullRequestsEnabled: form.pullRequestsEnabled,
        releasesEnabled: form.releasesEnabled,
        ciChecksEnabled: form.ciChecksEnabled,
        lfsEnabled: form.lfsEnabled,
      });
      setForm(saved);
      setFeedback({ type: 'ok', text: 'Metadata sync settings saved. Git ref push stays on.' });
    } catch (e: any) {
      setFeedback({ type: 'err', text: e.response?.data?.error || e.message });
    } finally {
      setSaving(false);
    }
  };

  if (!form) {
    return <p className="text-xs text-zinc-500">Loading metadata sync settings…</p>;
  }

  return (
    <div className="space-y-5 text-xs max-w-2xl">
      <div className="flex items-start justify-between gap-4">
        <div>
          <div className="flex items-center gap-2">
            <Layers className="w-4 h-4 text-zinc-700" />
            <h3 className="font-semibold text-zinc-900 text-sm">Metadata sync</h3>
            <InfoTooltip
              title="Metadata sync"
              whatIsIt="Global switches for pull requests, releases, CI checks, and Git LFS."
              howItWorks="Saved in the Hub database and shared across pods. Turning a switch off skips that stage on full sync, ignores the matching webhook, and disables the pair-page button. Git branch and tag push stays on."
            />
          </div>
          <p className="text-zinc-500 mt-1 leading-relaxed">
            These switches default to on. Git ref push is not controlled here.
          </p>
        </div>
        <button
          type="button"
          disabled={saving}
          onClick={save}
          className="shrink-0 inline-flex items-center space-x-1.5 bg-zinc-900 hover:bg-zinc-800 text-white text-xs px-4 py-2 rounded-lg font-medium disabled:opacity-50"
        >
          <Save className="w-3.5 h-3.5" />
          <span>{saving ? 'Saving…' : 'Save settings'}</span>
        </button>
      </div>

      {feedback && (
        <div
          className={`flex items-start gap-2 rounded-lg border px-3 py-2 ${
            feedback.type === 'ok'
              ? 'border-emerald-200 bg-emerald-50 text-emerald-800'
              : 'border-red-200 bg-red-50 text-red-800'
          }`}
        >
          {feedback.type === 'ok' ? <Check className="w-3.5 h-3.5 mt-0.5" /> : <AlertCircle className="w-3.5 h-3.5 mt-0.5" />}
          <span>{feedback.text}</span>
        </div>
      )}

      <div className="rounded-2xl border border-zinc-200 bg-white divide-y divide-zinc-100">
        {SETTING_META.map((item) => {
          const on = form[item.key];
          return (
            <div key={item.key} className="flex items-start justify-between gap-4 px-4 py-3">
              <div>
                <div className="font-medium text-zinc-900">{item.label}</div>
                <p className="text-zinc-500 mt-0.5 leading-relaxed">{item.description}</p>
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={on}
                onClick={() => toggle(item.key)}
                className={`shrink-0 mt-0.5 relative h-5 w-9 rounded-full transition-colors ${on ? 'bg-zinc-900' : 'bg-zinc-300'}`}
              >
                <span
                  className={`absolute top-0.5 h-4 w-4 rounded-full bg-white transition-transform ${on ? 'left-4' : 'left-0.5'}`}
                />
              </button>
            </div>
          );
        })}
      </div>
    </div>
  );
};
