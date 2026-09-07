import React, { useEffect, useRef, useState } from 'react';
import {
  Plus, Save, Trash2, RefreshCw, Copy, Check, Server, GitFork, AlertCircle, ShieldCheck,
} from 'lucide-react';
import { ScmCredential, ScmInstallationOption, PermissionCheckReport } from '../../types';
import {
  listScmCredentials,
  createScmCredential,
  updateScmCredential,
  deleteScmCredential,
  listScmInstallations,
  testScmCredential,
} from '../../services/api';
import { InfoTooltip } from '../../components/InfoTooltip';
import { appWebhookUrls } from '../../utils/webhookUrls';

interface Props {
  provider: 'GITHUB' | 'GITHUB_ENTERPRISE';
}

type FormState = {
  label: string;
  authMode: 'GITHUB_APP' | 'PERSONAL_ACCESS_TOKEN';
  hostUrl: string;
  appId: string;
  clientId: string;
  clientSecret: string;
  privateKeyPem: string;
  installationId: string;
  patToken: string;
  webhookSecret: string;
};

const emptyForm = (provider: Props['provider']): FormState => ({
  label: '',
  authMode: 'GITHUB_APP',
  hostUrl: provider === 'GITHUB' ? 'https://github.com' : '',
  appId: '',
  clientId: '',
  clientSecret: '',
  privateKeyPem: '',
  installationId: '',
  patToken: '',
  webhookSecret: '',
});

const inputClass =
  'w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400';

export const ScmCredentialsPanel: React.FC<Props> = ({ provider }) => {
  const [rows, setRows] = useState<ScmCredential[]>([]);
  const [loading, setLoading] = useState(true);
  const [editingId, setEditingId] = useState<number | 'new' | null>(null);
  const [form, setForm] = useState<FormState>(emptyForm(provider));
  const [installs, setInstalls] = useState<ScmInstallationOption[]>([]);
  const [feedback, setFeedback] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);
  const [testById, setTestById] = useState<Record<string, PermissionCheckReport>>({});
  const [copied, setCopied] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [testingId, setTestingId] = useState<number | null>(null);
  const [appClientSecret, setAppClientSecret] = useState('');
  const [savingAppSecret, setSavingAppSecret] = useState(false);
  const labelInputRef = useRef<HTMLInputElement>(null);
  const newCardRef = useRef<HTMLDivElement>(null);

  const isGhes = provider === 'GITHUB_ENTERPRISE';
  const appUrls = appWebhookUrls(isGhes ? 'ghes' : 'github');
  const appCardsWithSecret = rows.filter((r) => r.authMode === 'GITHUB_APP' && r.hasClientSecret);
  const clientSecretStatus =
    appCardsWithSecret.length === 0
      ? 'Not stored yet'
      : `Stored on ${appCardsWithSecret.length} card${appCardsWithSecret.length === 1 ? '' : 's'}${
          appCardsWithSecret[0].clientSecretMasked ? ` · ${appCardsWithSecret[0].clientSecretMasked}` : ''
        }`;

  const reload = async () => {
    setLoading(true);
    try {
      setRows(await listScmCredentials(provider));
    } catch (e: any) {
      setFeedback({ type: 'err', text: e.response?.data?.error || e.message });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setEditingId(null);
    setForm(emptyForm(provider));
    setInstalls([]);
    setTestById({});
    void reload();
  }, [provider]);

  useEffect(() => {
    if (editingId !== 'new') return;
    newCardRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    const id = window.setTimeout(() => labelInputRef.current?.focus(), 50);
    return () => window.clearTimeout(id);
  }, [editingId]);

  const startNew = () => {
    setEditingId('new');
    setForm(emptyForm(provider));
    setInstalls([]);
  };

  const startEdit = (row: ScmCredential) => {
    setEditingId(row.id);
    setForm({
      label: row.label,
      authMode: row.authMode,
      hostUrl: row.hostUrl || '',
      appId: row.appId || '',
      clientId: row.clientId || '',
      clientSecret: '',
      privateKeyPem: '',
      installationId: row.installationId || '',
      patToken: '',
      webhookSecret: '',
    });
    setInstalls([]);
  };

  const loadInstalls = async () => {
    if (editingId === 'new' || editingId == null) {
      setFeedback({ type: 'err', text: 'Save App ID and private key first, then list installations for this card.' });
      return;
    }
    try {
      setInstalls(await listScmInstallations(editingId));
    } catch (e: any) {
      setFeedback({ type: 'err', text: e.response?.data?.error || e.message });
    }
  };

  const saveSharedClientSecret = async () => {
    const secret = appClientSecret.trim();
    if (!secret) {
      setFeedback({ type: 'err', text: 'Paste the GitHub App client secret before saving.' });
      return;
    }
    const appCards = rows.filter((r) => r.authMode === 'GITHUB_APP');
    if (appCards.length === 0) {
      setFeedback({ type: 'err', text: 'Add an installation card first, then save the client secret here.' });
      return;
    }
    setSavingAppSecret(true);
    setFeedback(null);
    try {
      for (const card of appCards) {
        await updateScmCredential(card.id, { clientSecret: secret });
      }
      setAppClientSecret('');
      setFeedback({
        type: 'ok',
        text: `Client secret saved on ${appCards.length} GitHub App card${appCards.length === 1 ? '' : 's'}.`,
      });
      await reload();
    } catch (e: any) {
      setFeedback({ type: 'err', text: e.response?.data?.error || e.message });
    } finally {
      setSavingAppSecret(false);
    }
  };

  const save = async () => {
    setSaving(true);
    setFeedback(null);
    try {
      const body: Record<string, unknown> = {
        label: form.label,
        provider,
        hostUrl: isGhes ? form.hostUrl : 'https://github.com',
        authMode: form.authMode,
        appId: form.authMode === 'GITHUB_APP' ? form.appId : undefined,
        clientId: form.authMode === 'GITHUB_APP' ? form.clientId || undefined : undefined,
        clientSecret: form.clientSecret || undefined,
        privateKeyPem: form.privateKeyPem || undefined,
        installationId: form.authMode === 'GITHUB_APP' ? form.installationId : undefined,
        patToken: form.authMode === 'PERSONAL_ACCESS_TOKEN' ? form.patToken || undefined : undefined,
        webhookSecret: form.webhookSecret || undefined,
      };
      if (editingId === 'new') {
        await createScmCredential(body);
      } else if (editingId != null) {
        await updateScmCredential(editingId, body);
      }
      setEditingId(null);
      setInstalls([]);
      setFeedback({ type: 'ok', text: 'Credential saved.' });
      await reload();
    } catch (e: any) {
      setFeedback({ type: 'err', text: e.response?.data?.error || e.message });
    } finally {
      setSaving(false);
    }
  };

  const remove = async (id: number) => {
    if (!confirm('Delete this credential? Pairs that still reference it will block delete.')) return;
    try {
      await deleteScmCredential(id);
      if (editingId === id) setEditingId(null);
      await reload();
    } catch (e: any) {
      setFeedback({ type: 'err', text: e.response?.data?.error || e.message });
    }
  };

  const test = async (id: number) => {
    setTestingId(id);
    try {
      const report = await testScmCredential(id);
      setTestById((prev) => ({ ...prev, [String(id)]: report }));
    } catch (e: any) {
      setFeedback({ type: 'err', text: e.response?.data?.error || e.message });
    } finally {
      setTestingId(null);
    }
  };

  const copy = async (text: string, key: string) => {
    await navigator.clipboard.writeText(text);
    setCopied(key);
    setTimeout(() => setCopied(null), 1500);
  };

  const fieldLabel = (text: string, tooltip: React.ReactNode, stored?: string) => (
    <div className="flex items-center space-x-1.5 mb-1">
      <label className="block text-zinc-700 font-medium">
        {text}
        {stored && <span className="text-emerald-600 ml-2 font-normal">(Stored: {stored})</span>}
      </label>
      {tooltip}
    </div>
  );

  const renderForm = (row?: ScmCredential) => {
    const isApp = form.authMode === 'GITHUB_APP';
    return (
      <div className="space-y-4 pt-1">
        <div>
          {fieldLabel(
            'Label',
            <InfoTooltip
              title="Credential label"
              whatIsIt="A name you pick so this App install or PAT is easy to choose in the repo picker."
              howItWorks="Shown on this card and when binding a pair side. Use the org or appliance name."
            />
          )}
          <input
            ref={labelInputRef}
            className={inputClass.replace('font-mono ', '')}
            value={form.label}
            onChange={(e) => setForm({ ...form, label: e.target.value })}
            placeholder={isGhes ? 'e.g. GHES — engineering org' : 'e.g. gitmirror-sync-utility'}
          />
        </div>

        {isGhes && (
          <div>
            {fieldLabel(
              'GHES Host URL',
              <InfoTooltip
                title="GHES Host Endpoint"
                whatIsIt="Root web URL of this GitHub Enterprise Server appliance."
                howItWorks="Lives on this card. Another appliance is another credential. API calls go to {host}/api/v3."
                recommended="e.g. https://github.corp.internal"
              />
            )}
            <input
              className={inputClass}
              value={form.hostUrl}
              onChange={(e) => setForm({ ...form, hostUrl: e.target.value })}
              placeholder="https://github.corp.mycompany.com"
            />
            <p className="text-[11px] text-zinc-400 mt-1">
              API requests will be routed to <code>{form.hostUrl || 'https://github.corp.internal'}/api/v3</code>
            </p>
          </div>
        )}

        <div>
          <div className="flex items-center space-x-1.5 mb-2">
            <label className="block text-zinc-700 font-medium">Authentication Method</label>
            <InfoTooltip
              title="GitHub Authentication Modes"
              badge="OAuth & Apps"
              whatIsIt="Choose a GitHub App installation (ephemeral RS256 JWT tokens) or a Personal Access Token. Auth mode is exclusive — App rows never fall back to a leftover PAT."
              howItWorks="GitHub Apps authenticate per-installation. PATs act on behalf of a single user. Pick this credential in the repo picker so pairs never guess."
              recommended="Enterprise production: GitHub App per org install."
            />
          </div>
          <div className="flex flex-wrap gap-4">
            <label className="flex items-center space-x-2 cursor-pointer">
              <input
                type="radio"
                checked={isApp}
                onChange={() => setForm({ ...form, authMode: 'GITHUB_APP' })}
                className="text-zinc-900 focus:ring-zinc-900"
              />
              <span className="font-medium text-zinc-800">GitHub App (Enterprise Recommended)</span>
            </label>
            <label className="flex items-center space-x-2 cursor-pointer">
              <input
                type="radio"
                checked={!isApp}
                onChange={() => setForm({ ...form, authMode: 'PERSONAL_ACCESS_TOKEN' })}
                className="text-zinc-900 focus:ring-zinc-900"
              />
              <span className="font-medium text-zinc-800">Personal Access Token (PAT)</span>
            </label>
          </div>
        </div>

        {isApp ? (
          <div className="space-y-3 pt-1">
            <div className="grid grid-cols-2 gap-3">
              <div>
                {fieldLabel(
                  'App ID',
                  <InfoTooltip
                    title="GitHub App ID"
                    whatIsIt="Numerical identifier assigned by GitHub when the App is registered."
                    howItWorks="Found under Developer Settings → GitHub Apps → [Your App] → About → App ID."
                  />
                )}
                <input
                  className={inputClass}
                  value={form.appId}
                  onChange={(e) => setForm({ ...form, appId: e.target.value })}
                  placeholder="e.g. 123456"
                />
              </div>
              <div>
                {fieldLabel(
                  'Client ID',
                  <InfoTooltip
                    title="GitHub Client ID"
                    whatIsIt="Public OAuth identifier for your GitHub App."
                    howItWorks="Optional. Used to identify the app during OAuth handshakes and permission introspection."
                  />
                )}
                <input
                  className={inputClass}
                  value={form.clientId}
                  onChange={(e) => setForm({ ...form, clientId: e.target.value })}
                  placeholder="Iv1.xxxxxxxxxxxx"
                />
              </div>
            </div>

            <div>
              {fieldLabel(
                'Client Secret',
                <InfoTooltip
                  title="GitHub Client Secret"
                  whatIsIt="OAuth client secret from the GitHub App — the same value for every installation of that App."
                  howItWorks="Paste it once per card so Hub can store it with this install. Leave blank on edit to keep the stored secret. Optional for installation tokens (App ID + PEM are enough)."
                />,
                row?.hasClientSecret ? row.clientSecretMasked || '••••••••' : undefined
              )}
              <input
                type="password"
                className={inputClass}
                value={form.clientSecret}
                onChange={(e) => setForm({ ...form, clientSecret: e.target.value })}
                placeholder={row?.hasClientSecret ? '•••••••• (Leave blank to keep current)' : 'Enter Client Secret'}
              />
            </div>

            <div>
              {fieldLabel(
                'Private Key (.pem)',
                <InfoTooltip
                  title="RSA Private Key (.pem)"
                  whatIsIt="Private cryptographic key used to sign RS256 JWT tokens."
                  howItWorks="The backend generates short-lived JWTs to request 1-hour Installation Access Tokens. Leave blank on edit to keep the stored key."
                  recommended="Generate via GitHub App → Private keys → Generate a private key."
                />,
                row?.hasPrivateKey ? 'RSA Private Key' : undefined
              )}
              <textarea
                rows={4}
                className="w-full bg-white border border-zinc-200 rounded-lg p-2.5 text-zinc-900 font-mono text-[11px] focus:outline-none focus:border-zinc-400"
                value={form.privateKeyPem}
                onChange={(e) => setForm({ ...form, privateKeyPem: e.target.value })}
                placeholder={
                  row?.hasPrivateKey
                    ? '-----BEGIN RSA PRIVATE KEY-----\n••••••••\n-----END RSA PRIVATE KEY-----'
                    : '-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA...'
                }
              />
            </div>

            <div>
              {fieldLabel(
                'Installation ID',
                <InfoTooltip
                  title="Installation ID"
                  whatIsIt="ID representing where this App is installed on an organization or user account."
                  howItWorks="Required. Save App ID + PEM first, then List installs and pick the org. Hub will not auto-use installations[0]."
                />
              )}
              <div className="flex items-center space-x-2">
                <input
                  className={inputClass}
                  value={form.installationId}
                  onChange={(e) => setForm({ ...form, installationId: e.target.value })}
                  placeholder="Pick from List installs"
                />
                <button
                  type="button"
                  onClick={loadInstalls}
                  className="shrink-0 inline-flex items-center space-x-1 px-3 py-2 rounded-lg bg-zinc-100 hover:bg-zinc-200 text-zinc-800 border border-zinc-200 font-medium"
                >
                  <RefreshCw className="w-3.5 h-3.5" />
                  <span>List installs</span>
                </button>
              </div>
              {installs.length > 0 && (
                <select
                  className={`${inputClass} mt-2`}
                  value={form.installationId}
                  onChange={(e) => setForm({ ...form, installationId: e.target.value })}
                >
                  <option value="">Pick an installation — never auto-first</option>
                  {installs.map((i) => (
                    <option key={i.installationId} value={i.installationId}>
                      {i.accountLogin} ({i.accountType}) · {i.installationId}
                    </option>
                  ))}
                </select>
              )}
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                {fieldLabel(
                  'Webhook Secret',
                  <InfoTooltip
                    title="HMAC Webhook Secret"
                    whatIsIt="The webhook secret you set on the GitHub App — one secret for the App, not per installation."
                    howItWorks="Paste the same value you entered in GitHub App → Webhook secret. Hub uses it to validate X-Hub-Signature-256."
                  />,
                  row?.hasWebhookSecret ? row.webhookSecretMasked : undefined
                )}
                <input
                  type="password"
                  className={inputClass}
                  value={form.webhookSecret}
                  onChange={(e) => setForm({ ...form, webhookSecret: e.target.value })}
                  placeholder={row?.hasWebhookSecret ? '•••••••• (Leave blank to keep current)' : 'Enter Webhook Secret'}
                />
              </div>
            </div>
          </div>
        ) : (
          <div className="space-y-3 pt-1">
            {fieldLabel(
              'Personal Access Token',
              <InfoTooltip
                title="GitHub Personal Access Token"
                whatIsIt="A classic or fine-grained GitHub token used for JGit operations and REST API calls."
                howItWorks="Used as HTTP Basic credentials (x-access-token:<token>) for Git fetch/push and Bearer auth for metadata APIs."
                recommended="Requires repo, workflow, and admin:repo_hook scopes."
              />,
              row?.hasPatToken ? '••••••••' : undefined
            )}
            <input
              type="password"
              className={inputClass}
              value={form.patToken}
              onChange={(e) => setForm({ ...form, patToken: e.target.value })}
              placeholder={row?.hasPatToken ? '•••••••• (Leave blank to keep existing)' : 'ghp_xxxxxxxxxxxxxxxxxxxx'}
            />
            <p className="text-[11px] text-zinc-400">
              Requires <code>repo</code>, <code>workflow</code>, and <code>admin:repo_hook</code> scopes.
            </p>
            {fieldLabel(
              'Webhook Secret',
              <InfoTooltip
                title="HMAC Webhook Secret"
                whatIsIt="Secret you set on the repo webhook (or App webhook if you still use an App URL)."
                howItWorks="Paste the same secret you configured in GitHub. Leave blank on edit to keep the stored secret."
              />,
              row?.hasWebhookSecret ? row.webhookSecretMasked : undefined
            )}
            <input
              type="password"
              className={inputClass}
              value={form.webhookSecret}
              onChange={(e) => setForm({ ...form, webhookSecret: e.target.value })}
              placeholder={row?.hasWebhookSecret ? '•••••••• (Leave blank to keep current)' : 'Enter Webhook Secret'}
            />
          </div>
        )}

        <div className="flex items-center justify-end space-x-2.5 pt-1">
          <button
            type="button"
            onClick={() => {
              setEditingId(null);
              setInstalls([]);
            }}
            className="inline-flex items-center px-3.5 py-2 rounded-lg text-xs font-medium border border-zinc-200 bg-white hover:bg-zinc-50 text-zinc-800"
          >
            Cancel
          </button>
          <button
            type="button"
            disabled={saving}
            onClick={save}
            className="inline-flex items-center space-x-1.5 bg-zinc-900 hover:bg-zinc-800 text-white text-xs px-4 py-2 rounded-lg font-medium shadow-sm disabled:opacity-50"
          >
            <Save className="w-3.5 h-3.5" />
            <span>{saving ? 'Saving...' : 'Save Credential'}</span>
          </button>
        </div>
      </div>
    );
  };

  const renderTestReport = (report: PermissionCheckReport) => (
    <div
      className={`mt-3 p-3 rounded-xl border text-xs space-y-1.5 ${
        report.valid ? 'bg-emerald-50/80 border-emerald-200' : 'bg-rose-50/80 border-rose-200'
      }`}
    >
      <div className="flex items-center justify-between">
        <span className="font-semibold text-zinc-900">{report.valid ? 'Connection verified' : 'Connection failed'}</span>
        <span className="text-[10px] font-mono text-zinc-500">HTTP {report.httpStatusCode || (report.valid ? 200 : 400)}</span>
      </div>
      <p className="text-zinc-700 leading-relaxed font-sans">{report.message}</p>
      {report.passedChecks && report.passedChecks.length > 0 && (
        <div className="space-y-1 pt-1.5 border-t border-emerald-200/60">
          {report.passedChecks.map((check, idx) => (
            <div key={idx} className="flex items-center space-x-1.5 text-[11px] text-emerald-800">
              <Check className="w-3 h-3 text-emerald-600 shrink-0" />
              <span>{check}</span>
            </div>
          ))}
        </div>
      )}
      {report.errors && report.errors.length > 0 && (
        <div className="space-y-1 pt-1.5 border-t border-rose-200/60">
          {report.errors.map((err, idx) => (
            <div key={idx} className="flex items-center space-x-1.5 text-[11px] text-rose-800">
              <AlertCircle className="w-3 h-3 text-rose-600 shrink-0" />
              <span>{err}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );

  const secretChip = (ok: boolean, label: string) => (
    <span
      className={`inline-flex items-center px-2 py-0.5 rounded-md border text-[10px] font-medium ${
        ok
          ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
          : 'bg-amber-50 text-amber-800 border-amber-200'
      }`}
    >
      {label}: {ok ? 'Stored' : 'Missing'}
    </span>
  );

  const renderSummary = (row: ScmCredential) => {
    const configured = row.enabled && (row.hasPrivateKey || row.hasPatToken);
    const isApp = row.authMode === 'GITHUB_APP';
    return (
      <div className="space-y-2">
        <div className="flex flex-wrap gap-x-4 gap-y-1 text-[11px] text-zinc-600">
          {isApp && row.installationId && (
            <span>
              <span className="text-zinc-400 font-semibold uppercase tracking-wider text-[10px] mr-1">Install</span>
              <span className="font-mono text-zinc-800">{row.installationId}</span>
            </span>
          )}
          {isApp && row.appId && (
            <span>
              <span className="text-zinc-400 font-semibold uppercase tracking-wider text-[10px] mr-1">App</span>
              <span className="font-mono text-zinc-800">{row.appId}</span>
            </span>
          )}
          {isGhes && row.hostUrl && (
            <span className="font-mono text-zinc-700 truncate max-w-full">{row.hostUrl}</span>
          )}
        </div>
        <div className="flex flex-wrap gap-1.5">
          {isApp ? secretChip(!!row.hasPrivateKey, 'Private key') : secretChip(!!row.hasPatToken, 'PAT')}
          {secretChip(!!row.hasWebhookSecret, 'Webhook secret')}
          {isApp && secretChip(!!row.hasClientSecret, 'Client secret')}
        </div>
        {!configured && (
          <p className="text-[11px] text-amber-700">Incomplete — open Edit and save App ID + PEM (or a PAT) and an installation.</p>
        )}
      </div>
    );
  };

  return (
    <div className="space-y-4 text-xs">
      <div className="flex items-center justify-between border-b border-zinc-100 pb-3">
        <div className="flex items-center space-x-2">
          {isGhes ? <Server className="w-4 h-4 text-indigo-600" /> : <GitFork className="w-4 h-4" />}
          <h3 className="font-semibold text-zinc-900 text-sm">
            {isGhes ? 'GitHub Enterprise Server credentials' : 'GitHub Cloud Authentication'}
          </h3>
          <InfoTooltip
            title={isGhes ? 'GitHub Enterprise Server' : 'GitHub Authentication Modes'}
            badge={isGhes ? 'On-Premises' : 'OAuth & Apps'}
            whatIsIt={
              isGhes
                ? 'Each card is one GHES App install or PAT. Host URL lives on the card — two appliances are two cards.'
                : 'Each card is one GitHub App installation or one PAT. Bind a card in the repo picker so pairs never guess PAT vs App.'
            }
            recommended="Add one card per org install. Do not reuse a single god-row across orgs."
          />
        </div>
        <button
          type="button"
          onClick={startNew}
          className="inline-flex items-center space-x-1 px-3 py-1.5 rounded-lg bg-zinc-900 hover:bg-zinc-800 text-white font-medium"
        >
          <Plus className="w-3.5 h-3.5" />
          <span>Add credential</span>
        </button>
      </div>

      <p className="text-zinc-500 leading-relaxed">
        Each card is one installation (org) or PAT. App ID, client secret, PEM, and webhook URL belong to the GitHub App —
        paste the URL into GitHub, not as Hub-saved config.
      </p>

      <div className="rounded-xl border border-zinc-200 bg-zinc-50 p-3 space-y-2.5">
        <p className="text-[11px] text-zinc-600 leading-relaxed">
          Copy these into the GitHub App webhook settings. Same URLs are in the setup guide on the right.
        </p>
        <div>
          <div className="flex items-center justify-between mb-1">
            <span className="text-[10px] font-semibold uppercase tracking-wider text-zinc-500">Edge webhook URL</span>
            <button
              type="button"
              onClick={() => copy(appUrls.edge, 'edge')}
              className="text-[10px] text-zinc-500 hover:text-zinc-900 flex items-center space-x-1 font-medium"
            >
              {copied === 'edge' ? (
                <>
                  <Check className="w-2.5 h-2.5 text-emerald-600" />
                  <span className="text-emerald-600">Copied!</span>
                </>
              ) : (
                <>
                  <Copy className="w-2.5 h-2.5" />
                  <span>Copy</span>
                </>
              )}
            </button>
          </div>
          <div className="p-2 bg-zinc-900 text-zinc-200 rounded-lg font-mono text-[10px] break-all select-all">
            {appUrls.edge}
          </div>
        </div>
        <div>
          <div className="flex items-center justify-between mb-1">
            <span className="text-[10px] font-semibold uppercase tracking-wider text-zinc-500">Hub direct (no Worker)</span>
            <button
              type="button"
              onClick={() => copy(appUrls.hub, 'hub')}
              className="text-[10px] text-zinc-500 hover:text-zinc-900 flex items-center space-x-1 font-medium"
            >
              {copied === 'hub' ? (
                <>
                  <Check className="w-2.5 h-2.5 text-emerald-600" />
                  <span className="text-emerald-600">Copied!</span>
                </>
              ) : (
                <>
                  <Copy className="w-2.5 h-2.5" />
                  <span>Copy</span>
                </>
              )}
            </button>
          </div>
          <p className="text-[10px] text-zinc-500 mb-1">
            Use only without the Worker. GitHub.com cannot call localhost; GHES on the same network can.
          </p>
          <div className="p-2 bg-white border border-zinc-200 text-zinc-700 rounded-lg font-mono text-[10px] break-all select-all">
            {appUrls.hub}
          </div>
        </div>
        <div>
          <div className="flex items-center justify-between mb-1">
            <span className="text-[10px] font-semibold uppercase tracking-wider text-zinc-500">Client secret (GitHub App)</span>
            <span className={`text-[10px] font-medium ${appCardsWithSecret.length > 0 ? 'text-emerald-700' : 'text-amber-700'}`}>
              {clientSecretStatus}
            </span>
          </div>
          <p className="text-[10px] text-zinc-500 mb-1.5">
            Same OAuth secret for every installation of this App. Paste here to store or rotate it on all GitHub App cards.
          </p>
          <div className="flex items-center gap-2">
            <input
              type="password"
              className={`${inputClass} bg-white`}
              value={appClientSecret}
              onChange={(e) => setAppClientSecret(e.target.value)}
              placeholder="Paste GitHub App client secret"
              autoComplete="new-password"
            />
            <button
              type="button"
              disabled={savingAppSecret}
              onClick={() => void saveSharedClientSecret()}
              className="shrink-0 inline-flex items-center space-x-1 px-3 py-2 rounded-lg bg-zinc-900 hover:bg-zinc-800 text-white font-medium disabled:opacity-50"
            >
              <Save className="w-3.5 h-3.5" />
              <span>{savingAppSecret ? 'Saving…' : 'Save'}</span>
            </button>
          </div>
        </div>
      </div>

      {feedback && (
        <div
          className={`flex items-start space-x-2 rounded-lg border px-3 py-2 ${
            feedback.type === 'ok' ? 'bg-emerald-50 border-emerald-200 text-emerald-800' : 'bg-rose-50 border-rose-200 text-rose-800'
          }`}
        >
          <AlertCircle className="w-3.5 h-3.5 mt-0.5 shrink-0" />
          <span>{feedback.text}</span>
        </div>
      )}

      {loading ? (
        <p className="text-zinc-400">Loading credentials…</p>
      ) : rows.length === 0 && editingId !== 'new' ? (
        <p className="text-zinc-500">No credentials yet. Add a GitHub App install or a PAT.</p>
      ) : (
        <div className="space-y-4">
          {rows.map((row) => {
            const configured = row.enabled && (row.hasPrivateKey || row.hasPatToken);
            const editingThis = editingId === row.id;
            return (
              <div key={row.id} className="rounded-xl border border-zinc-200 bg-white p-4 space-y-3 shadow-sm">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex items-center flex-wrap gap-2">
                      <h4 className="font-semibold text-zinc-900 text-sm truncate">{row.label}</h4>
                      <span
                        className={`px-2 py-0.5 rounded-full text-[10px] font-medium ${
                          configured
                            ? 'bg-emerald-50 text-emerald-700 border border-emerald-200'
                            : 'bg-zinc-100 text-zinc-600'
                        }`}
                      >
                        {configured ? 'Configured & Active' : 'Incomplete'}
                      </span>
                    </div>
                    <p className="text-[11px] text-zinc-500 mt-0.5">
                      {row.authMode === 'GITHUB_APP' ? 'GitHub App' : 'PAT'}
                      {row.accountLogin ? ` · ${row.accountLogin}` : ''}
                      {isGhes && row.hostUrl ? ` · ${row.hostUrl}` : ''}
                    </p>
                  </div>
                  <div className="flex items-center space-x-1.5 shrink-0">
                    <button
                      type="button"
                      onClick={() => (editingThis ? setEditingId(null) : startEdit(row))}
                      className="px-2.5 py-1.5 rounded-lg bg-zinc-100 hover:bg-zinc-200 text-zinc-800 border border-zinc-200 font-medium"
                    >
                      {editingThis ? 'Close' : 'Edit'}
                    </button>
                    <button
                      type="button"
                      onClick={() => test(row.id)}
                      disabled={testingId === row.id}
                      className="inline-flex items-center space-x-1 px-2.5 py-1.5 rounded-lg bg-zinc-100 hover:bg-zinc-200 text-zinc-800 border border-zinc-200 font-medium disabled:opacity-50"
                    >
                      <ShieldCheck className={`w-3.5 h-3.5 ${testingId === row.id ? 'animate-spin' : ''}`} />
                      <span>{testingId === row.id ? 'Testing…' : 'Test'}</span>
                    </button>
                    <button
                      type="button"
                      className="p-1.5 rounded-lg text-rose-700 hover:bg-rose-50"
                      onClick={() => remove(row.id)}
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                </div>
                {editingThis ? renderForm(row) : renderSummary(row)}
                {testById[String(row.id)] && renderTestReport(testById[String(row.id)])}
              </div>
            );
          })}
        </div>
      )}

      {editingId === 'new' && (
        <div ref={newCardRef} className="rounded-xl border border-zinc-200 bg-white p-4 space-y-3 shadow-sm">
          <h4 className="font-semibold text-zinc-900 text-sm">New credential</h4>
          {renderForm()}
        </div>
      )}
    </div>
  );
};
