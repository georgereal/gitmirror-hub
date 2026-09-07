import React, { useState, useEffect } from 'react';
import {
  ShieldCheck, Key, Save, CheckCircle2, AlertCircle, RefreshCw, Check,
  BookOpen, Layers, GitFork, Globe, Lock, Server, Copy, ExternalLink,
  HelpCircle, Radio, Terminal, Webhook
} from 'lucide-react';
import { InfoTooltip } from '../../components/InfoTooltip';
import { ProviderConfig, GitHubRepoOption, PermissionCheckReport } from '../../types';
import { getGitHubAppConfig, saveGitHubAppConfig, listAccessibleRepositories, testProviderConnection } from '../../services/api';
import { ScmCredentialsPanel } from './ScmCredentialsPanel';
import { appWebhookUrls } from '../../utils/webhookUrls';

export const ProvidersAuthPage: React.FC = () => {
  const [selectedProvider, setSelectedProvider] = useState<'github' | 'ghes' | 'bitbucket' | 'gitlab' | 'origin' | 'generic'>('github');

  const [loadedConfig, setLoadedConfig] = useState<ProviderConfig | null>(null);
  const [authType, setAuthType] = useState<'GITHUB_APP' | 'PERSONAL_ACCESS_TOKEN'>('PERSONAL_ACCESS_TOKEN');
  const [defaultPatToken, setDefaultPatToken] = useState('');

  // GitHub App state
  const [appId, setAppId] = useState('');
  const [clientId, setClientId] = useState('');
  const [clientSecret, setClientSecret] = useState('');
  const [privateKeyPem, setPrivateKeyPem] = useState('');
  const [installationId, setInstallationId] = useState('');
  const [webhookSecret, setWebhookSecret] = useState('');

  // GitHub Enterprise Server (GHES) state
  const [ghesHostUrl, setGhesHostUrl] = useState('');
  const [ghesAuthType, setGhesAuthType] = useState<'GITHUB_APP' | 'PERSONAL_ACCESS_TOKEN'>('PERSONAL_ACCESS_TOKEN');
  const [ghesPatToken, setGhesPatToken] = useState('');
  const [ghesAppId, setGhesAppId] = useState('');
  const [ghesClientId, setGhesClientId] = useState('');
  const [ghesClientSecret, setGhesClientSecret] = useState('');
  const [ghesPrivateKeyPem, setGhesPrivateKeyPem] = useState('');
  const [ghesInstallationId, setGhesInstallationId] = useState('');
  const [ghesWebhookSecret, setGhesWebhookSecret] = useState('');

  // GitLab state
  const [gitlabHostUrl, setGitlabHostUrl] = useState('https://gitlab.com');
  const [gitlabAccessToken, setGitlabAccessToken] = useState('');
  const [gitlabWebhookSecret, setGitlabWebhookSecret] = useState('');

  // Bitbucket state
  const [bitbucketWorkspace, setBitbucketWorkspace] = useState('');
  const [bitbucketAuthType, setBitbucketAuthType] = useState<'OAUTH2' | 'APP_PASSWORD' | 'ACCESS_TOKEN'>('OAUTH2');
  const [bitbucketUsername, setBitbucketUsername] = useState('');
  const [bitbucketAccessToken, setBitbucketAccessToken] = useState('');
  const [bitbucketWebhookSecret, setBitbucketWebhookSecret] = useState('');

  // Cursor Origin state
  const [originHostUrl, setOriginHostUrl] = useState('https://origin.cursor.com');
  const [originAccessToken, setOriginAccessToken] = useState('');
  const [originWebhookSecret, setOriginWebhookSecret] = useState('');

  // Generic state
  const [genericUsername, setGenericUsername] = useState('');
  const [genericAccessToken, setGenericAccessToken] = useState('');

  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(false);
  const [testingConnection, setTestingConnection] = useState(false);
  const [testResult, setTestResult] = useState<PermissionCheckReport | null>(null);
  const [feedback, setFeedback] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

  const [repos, setRepos] = useState<GitHubRepoOption[]>([]);
  const [fetchingRepos, setFetchingRepos] = useState(false);

  // Copy feedback state
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  const isSelectedProviderConfigured = () => {
    switch (selectedProvider) {
      case 'github':
        return !!(loadedConfig?.configured && (loadedConfig.hasDefaultPatToken || (loadedConfig.appId && loadedConfig.hasPrivateKey) || loadedConfig.authType === 'GITHUB_APP'));
      case 'ghes':
        return !!(loadedConfig?.hasGhesPatToken || (loadedConfig?.hasGhesPrivateKey && loadedConfig?.ghesAppId));
      case 'bitbucket':
        return !!loadedConfig?.hasBitbucketAccessToken;
      case 'gitlab':
        return !!loadedConfig?.hasGitlabAccessToken;
      case 'origin':
        return !!loadedConfig?.hasOriginAccessToken;
      case 'generic':
        return !!loadedConfig?.hasGenericAccessToken;
      default:
        return false;
    }
  };

  const getProviderDisplayName = (p: string) => {
    switch (p) {
      case 'github': return 'GitHub Cloud';
      case 'ghes': return 'GitHub Enterprise';
      case 'bitbucket': return 'Bitbucket Cloud';
      case 'gitlab': return 'GitLab';
      case 'origin': return 'Cursor Origin';
      case 'generic': return 'Azure DevOps / Generic';
      default: return 'Repository';
    }
  };

  useEffect(() => {
    loadConfig();
  }, []);

  useEffect(() => {
    if (loadedConfig) {
      setTestResult(null);
      setFeedback(null);
      fetchAccessibleRepos(selectedProvider);
    }
  }, [selectedProvider, loadedConfig]);

  const handleTestConnection = async () => {
    setTestingConnection(true);
    setTestResult(null);
    setFeedback(null);
    try {
      let inFlightToken: string | undefined;

      switch (selectedProvider) {
        case 'github':
          inFlightToken = authType === 'PERSONAL_ACCESS_TOKEN' && defaultPatToken ? defaultPatToken : undefined;
          break;
        case 'ghes':
          inFlightToken = ghesAuthType === 'PERSONAL_ACCESS_TOKEN' && ghesPatToken ? ghesPatToken : undefined;
          break;
        case 'bitbucket':
          inFlightToken = bitbucketAccessToken || undefined;
          break;
        case 'gitlab':
          inFlightToken = gitlabAccessToken || undefined;
          break;
        case 'origin':
          inFlightToken = originAccessToken || undefined;
          break;
        case 'generic':
          inFlightToken = genericAccessToken || undefined;
          break;
      }

      const result = await testProviderConnection(selectedProvider, {
        token: inFlightToken,
      });
      setTestResult(result);
    } catch (err: any) {
      setTestResult({
        valid: false,
        httpStatusCode: err.response?.status || 400,
        message: err.response?.data?.message || err.message || 'Connection test failed',
        errors: [err.response?.data?.message || err.message || 'Provider connection rejected.'],
        passedChecks: [],
        warnings: [],
      });
    } finally {
      setTestingConnection(false);
    }
  };

  const loadConfig = async () => {
    setLoading(true);
    try {
      const config = await getGitHubAppConfig();
      if (config) {
        setLoadedConfig(config);
        setAuthType(config.authType || 'PERSONAL_ACCESS_TOKEN');
        setAppId(config.appId || '');
        setClientId(config.clientId || '');
        setInstallationId(config.installationId || '');
        if (config.ghesHostUrl) setGhesHostUrl(config.ghesHostUrl);
        if (config.ghesAuthType) setGhesAuthType(config.ghesAuthType);
        if (config.ghesAppId) setGhesAppId(config.ghesAppId);
        if (config.ghesClientId) setGhesClientId(config.ghesClientId);
        if (config.ghesInstallationId) setGhesInstallationId(config.ghesInstallationId);
        if (config.gitlabHostUrl) setGitlabHostUrl(config.gitlabHostUrl);
        if (config.bitbucketWorkspace) setBitbucketWorkspace(config.bitbucketWorkspace);
        if (config.bitbucketAuthType) setBitbucketAuthType(config.bitbucketAuthType);
        if (config.bitbucketUsername) setBitbucketUsername(config.bitbucketUsername);
        if (config.originHostUrl) setOriginHostUrl(config.originHostUrl);
        if (config.genericUsername) setGenericUsername(config.genericUsername);
      }
    } catch (e) {
      console.error('Error loading config:', e);
    } finally {
      setLoading(false);
    }
  };

  const fetchAccessibleRepos = async (providerOverride?: string) => {
    const prov = providerOverride || selectedProvider;
    setFetchingRepos(true);
    try {
      const r = await listAccessibleRepositories(undefined, prov.toUpperCase());
      setRepos(r);
    } catch (e) {
      console.error('Failed to list repos:', e);
      setRepos([]);
    } finally {
      setFetchingRepos(false);
    }
  };

  const copyToClipboard = (text: string, key: string) => {
    navigator.clipboard.writeText(text);
    setCopiedKey(key);
    setTimeout(() => setCopiedKey(null), 2000);
  };

  const handleSaveProvider = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setFeedback(null);
    try {
      const payload: Partial<ProviderConfig> = {
        authType: selectedProvider === 'github' ? authType : (loadedConfig?.authType || undefined),
        appId: appId || undefined,
        clientId: clientId || undefined,
        clientSecret: clientSecret || undefined,
        privateKeyPem: privateKeyPem || undefined,
        installationId: installationId || undefined,
        webhookSecret: webhookSecret || undefined,
        defaultPatToken: defaultPatToken || undefined,

        ghesHostUrl: ghesHostUrl || undefined,
        ghesAuthType: selectedProvider === 'ghes' ? ghesAuthType : (loadedConfig?.ghesAuthType || undefined),
        ghesPatToken: ghesPatToken || undefined,
        ghesAppId: ghesAppId || undefined,
        ghesClientId: ghesClientId || undefined,
        ghesClientSecret: ghesClientSecret || undefined,
        ghesPrivateKeyPem: ghesPrivateKeyPem || undefined,
        ghesInstallationId: ghesInstallationId || undefined,
        ghesWebhookSecret: ghesWebhookSecret || undefined,

        gitlabHostUrl: gitlabHostUrl || undefined,
        gitlabAccessToken: gitlabAccessToken || undefined,
        gitlabWebhookSecret: gitlabWebhookSecret || undefined,

        bitbucketWorkspace: bitbucketWorkspace || undefined,
        bitbucketAuthType: selectedProvider === 'bitbucket' ? bitbucketAuthType : (loadedConfig?.bitbucketAuthType || undefined),
        bitbucketUsername: bitbucketUsername || undefined,
        bitbucketAccessToken: bitbucketAccessToken || undefined,
        bitbucketWebhookSecret: bitbucketWebhookSecret || undefined,

        originHostUrl: originHostUrl || undefined,
        originAccessToken: originAccessToken || undefined,
        originWebhookSecret: originWebhookSecret || undefined,

        genericUsername: genericUsername || undefined,
        genericAccessToken: genericAccessToken || undefined,
      };

      const saved = await saveGitHubAppConfig(payload);
      setLoadedConfig(saved);
      setClientSecret('');
      setPrivateKeyPem('');
      setWebhookSecret('');
      setDefaultPatToken('');
      setGhesPatToken('');
      setGhesClientSecret('');
      setGhesPrivateKeyPem('');
      setGhesWebhookSecret('');
      setGitlabAccessToken('');
      setGitlabWebhookSecret('');
      setBitbucketAccessToken('');
      setBitbucketWebhookSecret('');
      setOriginAccessToken('');
      setOriginWebhookSecret('');
      setGenericAccessToken('');

      setFeedback({
        type: 'success',
        message: 'Provider credentials encrypted and saved successfully!'
      });

      if (saved.configured) {
        fetchAccessibleRepos();
      }
    } catch (err: any) {
      setFeedback({
        type: 'error',
        message: err.response?.data?.message || err.message || 'Failed to save configuration'
      });
    } finally {
      setSaving(false);
    }
  };

  // Helper function to render provider setup checklist & webhook guidance
  const renderProviderSetupGuide = () => {
    switch (selectedProvider) {
      case 'github':
        return {
          title: 'GitHub Cloud Integration Guide',
          icon: <GitFork className="w-4 h-4 text-zinc-900" />,
          docUrl: 'https://docs.github.com/en/apps/creating-github-apps',
          webhookUrl: appWebhookUrls('github').edge,
          backendWebhookUrl: appWebhookUrls('github').hub,
          steps: [
            'Go to GitHub -> Settings -> Developer Settings -> GitHub Apps -> New GitHub App (or Personal Access Tokens).',
            'Paste the Edge Webhook URL (right) into the GitHub App webhook field. Use Hub direct only if you are not running the Worker.',
            'Generate a Webhook Secret there and paste the same secret onto each installation card below (it is App-level, not per org).',
            'Grant Permissions: Contents (Read & Write), Pull requests (Read & Write), Commit statuses (Read & Write), Webhooks (Read & Write).',
            'Generate a Private Key (.pem), download it, and copy the App ID, Client ID, Client Secret, and PEM into a credential card. Add another card only for another installation (org).'
          ],
          scopes: ['Contents: Read & Write', 'Pull Requests: Read & Write', 'Commit Statuses: Read & Write', 'Webhooks: Read & Write', 'repo (for PAT)'],
          events: ['Pushes (push)', 'Pull requests (pull_request)', 'Commit statuses (status)', 'Issue comments (issue_comment)']
        };
      case 'ghes':
        return {
          title: 'GitHub Enterprise Server (GHES) Guide',
          icon: <Server className="w-4 h-4 text-indigo-600" />,
          docUrl: 'https://docs.github.com/en/enterprise-server/developers/apps/building-github-apps',
          webhookUrl: appWebhookUrls('ghes').edge,
          backendWebhookUrl: appWebhookUrls('ghes').hub,
          steps: [
            'Navigate to your on-premises GHES instance: https://<ghes-host>/settings/apps/new.',
            'Paste the Edge Webhook URL (right) into the GitHub App webhook field. Use Hub direct only if you are not running the Worker.',
            'Generate a Webhook Secret there and paste the same secret onto each installation card (it is App-level, not per org).',
            'Configure repository permissions for Contents, Pull Requests, and Commit Statuses.',
            'Download the generated RSA Private Key and copy the App ID, Client ID, Client Secret, PEM, and Installation ID onto a credential card.'
          ],
          scopes: ['Contents: Read & Write', 'Pull Requests: Read & Write', 'Commit Statuses: Read & Write', 'Internal API v3 access'],
          events: ['push', 'pull_request', 'status', 'check_run']
        };
      case 'bitbucket':
        return {
          title: 'Bitbucket Cloud OAuth & Webhook Guide',
          icon: <Globe className="w-4 h-4 text-blue-600" />,
          docUrl: 'https://support.atlassian.com/bitbucket-cloud/docs/app-passwords/',
          webhookUrl: appWebhookUrls('bitbucket').edge,
          backendWebhookUrl: appWebhookUrls('bitbucket').hub,
          steps: [
            'Create Auth: Workspace Settings -> OAuth consumers -> Add consumer with Grant type: "Client credentials" (or Personal Settings -> App Passwords).',
            'Grant Scopes: Repositories (Read/Write), Pull Requests (Read/Write), Pipelines (Read/Write), Webhooks (Read/Write).',
            'Copy the Client Key & Secret (or App Password with username x-token-auth) into this form.',
            'In your Bitbucket repo (Repository Settings at bottom of sidebar -> Webhooks -> Add Webhook): paste the Webhook URL, set a secret passphrase, and check Push & PR events.',
            'Run `npx wrangler secret put BITBUCKET_WEBHOOK_SECRET` in webhook-worker if you configured a secret, then `npm run deploy`.'
          ],
          scopes: ['Repositories: Read & Write', 'Pull requests: Read & Write', 'Pipelines: Read & Write', 'Webhooks: Read & Write'],
          events: ['repo:push', 'pullrequest:created', 'pullrequest:updated', 'pullrequest:fulfilled', 'repo:commit_status_created']
        };
      case 'gitlab':
        return {
          title: 'GitLab OAuth & Webhook Guide',
          icon: <Layers className="w-4 h-4 text-orange-600" />,
          docUrl: 'https://docs.gitlab.com/ee/user/project/integrations/webhooks.html',
          webhookUrl: appWebhookUrls('gitlab').edge,
          backendWebhookUrl: appWebhookUrls('gitlab').hub,
          steps: [
            'Go to GitLab -> User Settings -> Access Tokens (or Project/Group Settings -> Access Tokens).',
            'Create a token with scopes: api, read_repository, write_repository.',
            'In your project settings: Settings -> Webhooks -> Add new webhook.',
            'Enter the Webhook URL, provide your Secret Token, and check "Push events", "Merge requests events", and "Pipeline events".'
          ],
          scopes: ['api', 'read_repository', 'write_repository', 'read_api'],
          events: ['Push events', 'Tag push events', 'Merge request events', 'Pipeline events']
        };
      case 'origin':
        return {
          title: 'Cursor Origin Git Guide',
          icon: <Lock className="w-4 h-4 text-purple-600" />,
          docUrl: 'https://origin.cursor.com',
          webhookUrl: appWebhookUrls('origin').edge,
          backendWebhookUrl: appWebhookUrls('origin').hub,
          steps: [
            'Open your terminal and authenticate using the Cursor CLI: origin signin.',
            'Alternatively, obtain your personal access token from your Cursor settings.',
            'Paste the access token into the Cursor Origin Access Token field.',
            'Mirrored repositories can push and pull directly to https://origin.cursor.com.'
          ],
          scopes: ['origin:read', 'origin:write', 'git-transport'],
          events: ['push', 'sync-trigger']
        };
      case 'generic':
        return {
          title: 'Azure DevOps & Generic SCM Guide',
          icon: <Key className="w-4 h-4 text-cyan-600" />,
          docUrl: 'https://learn.microsoft.com/en-us/azure/devops/service-hooks/services/webhooks',
          webhookUrl: appWebhookUrls('github').edge,
          backendWebhookUrl: appWebhookUrls('github').hub,
          steps: [
            'In Azure DevOps: User Settings -> Personal Access Tokens -> New Token.',
            'Grant Scopes: Code (Read & Write) and Code (Status).',
            'For Webhooks: Project Settings -> Service Hooks -> Create subscription -> Web Hooks.',
            'Trigger on "Code pushed" and "Pull request created", pointing to the Webhook URL.'
          ],
          scopes: ['Code: Read & Write', 'Code: Status', 'vso.code_full'],
          events: ['Code pushed', 'Pull request created', 'Pull request updated']
        };
    }
  };

  const currentGuide = renderProviderSetupGuide();

  return (
    <div className="space-y-6">
      {/* Provider Sub-tabs */}
      <div className="flex border-b border-zinc-200 overflow-x-auto space-x-1">
        <button
          onClick={() => setSelectedProvider('github')}
          className={`flex items-center space-x-2 px-3 py-2 text-xs font-medium border-b-2 transition-colors whitespace-nowrap ${
            selectedProvider === 'github'
              ? 'border-zinc-900 text-zinc-900 font-semibold'
              : 'border-transparent text-zinc-500 hover:text-zinc-700'
          }`}
        >
          <GitFork className="w-3.5 h-3.5" />
          <span>GitHub Cloud</span>
          {loadedConfig?.configured && (
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 ml-1" />
          )}
        </button>

        <button
          onClick={() => setSelectedProvider('ghes')}
          className={`flex items-center space-x-2 px-3 py-2 text-xs font-medium border-b-2 transition-colors whitespace-nowrap ${
            selectedProvider === 'ghes'
              ? 'border-zinc-900 text-zinc-900 font-semibold'
              : 'border-transparent text-zinc-500 hover:text-zinc-700'
          }`}
        >
          <Server className="w-3.5 h-3.5" />
          <span>GitHub Enterprise Server</span>
          {loadedConfig?.hasGhesPatToken && (
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 ml-1" />
          )}
        </button>

        <button
          onClick={() => setSelectedProvider('bitbucket')}
          className={`flex items-center space-x-2 px-3 py-2 text-xs font-medium border-b-2 transition-colors whitespace-nowrap ${
            selectedProvider === 'bitbucket'
              ? 'border-zinc-900 text-zinc-900 font-semibold'
              : 'border-transparent text-zinc-500 hover:text-zinc-700'
          }`}
        >
          <Globe className="w-3.5 h-3.5" />
          <span>Bitbucket Cloud</span>
          {loadedConfig?.hasBitbucketAccessToken && (
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 ml-1" />
          )}
        </button>

        <button
          onClick={() => setSelectedProvider('gitlab')}
          className={`flex items-center space-x-2 px-3 py-2 text-xs font-medium border-b-2 transition-colors whitespace-nowrap ${
            selectedProvider === 'gitlab'
              ? 'border-zinc-900 text-zinc-900 font-semibold'
              : 'border-transparent text-zinc-500 hover:text-zinc-700'
          }`}
        >
          <Layers className="w-3.5 h-3.5" />
          <span>GitLab</span>
        </button>

        <button
          onClick={() => setSelectedProvider('origin')}
          className={`flex items-center space-x-2 px-3 py-2 text-xs font-medium border-b-2 transition-colors whitespace-nowrap ${
            selectedProvider === 'origin'
              ? 'border-zinc-900 text-zinc-900 font-semibold'
              : 'border-transparent text-zinc-500 hover:text-zinc-700'
          }`}
        >
          <Lock className="w-3.5 h-3.5" />
          <span>Cursor Origin</span>
        </button>

        <button
          onClick={() => setSelectedProvider('generic')}
          className={`flex items-center space-x-2 px-3 py-2 text-xs font-medium border-b-2 transition-colors whitespace-nowrap ${
            selectedProvider === 'generic'
              ? 'border-zinc-900 text-zinc-900 font-semibold'
              : 'border-transparent text-zinc-500 hover:text-zinc-700'
          }`}
        >
          <Key className="w-3.5 h-3.5" />
          <span>Azure DevOps / Generic</span>
        </button>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* Left Form View */}
        <div className="lg:col-span-7 space-y-4">
          <div className="rounded-2xl border border-zinc-200/90 bg-white p-6 shadow-sm">
            <form onSubmit={handleSaveProvider} className="space-y-4 text-xs">
              {(selectedProvider === 'github' || selectedProvider === 'ghes') && (
                <ScmCredentialsPanel provider={selectedProvider === 'ghes' ? 'GITHUB_ENTERPRISE' : 'GITHUB'} />
              )}

              {selectedProvider === 'gitlab' && (
                <div className="space-y-4">
                  <div className="flex items-center justify-between border-b border-zinc-100 pb-3">
                    <div className="flex items-center space-x-2">
                      <h3 className="font-semibold text-zinc-900 text-sm">GitLab Integration</h3>
                      <InfoTooltip
                        title="GitLab API & Auth"
                        badge="REST v4"
                        whatIsIt="Integrates with GitLab.com SaaS and Self-Hosted GitLab instances via Personal or Group Access Tokens."
                        howItWorks="Authenticates Git transport via oauth2:<token> and REST v4 endpoints via PRIVATE-TOKEN headers."
                      />
                    </div>
                  </div>

                  <div>
                    <div className="flex items-center space-x-1.5 mb-1">
                      <label className="block text-zinc-700 font-medium">GitLab Host URL</label>
                      <InfoTooltip
                        title="GitLab Host URL"
                        whatIsIt="Base URL for your GitLab instance."
                        howItWorks="Default is https://gitlab.com. For self-hosted instances, specify https://gitlab.mycompany.com."
                      />
                    </div>
                    <input
                      type="text"
                      value={gitlabHostUrl}
                      onChange={(e) => setGitlabHostUrl(e.target.value)}
                      placeholder="https://gitlab.com or https://gitlab.mycorp.internal"
                      className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                    />
                  </div>

                  <div>
                    <div className="flex items-center space-x-1.5 mb-1">
                      <label className="block text-zinc-700 font-medium">
                        Personal Access Token (PAT)
                        {loadedConfig?.hasGitlabAccessToken && (
                          <span className="text-emerald-600 ml-2 font-normal">(Configured: {loadedConfig.gitlabAccessTokenMasked})</span>
                        )}
                      </label>
                      <InfoTooltip
                        title="GitLab Access Token Scopes"
                        whatIsIt="Token generated under User Settings -> Access Tokens or Project/Group Access Tokens."
                        recommended="Requires 'api', 'read_repository', and 'write_repository' scopes."
                      />
                    </div>
                    <input
                      type="password"
                      value={gitlabAccessToken}
                      onChange={(e) => setGitlabAccessToken(e.target.value)}
                      placeholder={loadedConfig?.hasGitlabAccessToken ? '•••••••• (Leave blank to keep current)' : 'glpat-xxxxxxxxxxxxxxxxxxxx'}
                      className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                    />
                  </div>
                </div>
              )}

              {/* BITBUCKET CONFIGURATION */}
              {selectedProvider === 'bitbucket' && (
                <div className="space-y-4">
                  <div className="flex items-center justify-between border-b border-zinc-100 pb-3">
                    <div className="flex items-center space-x-2">
                      <h3 className="font-semibold text-zinc-900 text-sm">Bitbucket Cloud Integration</h3>
                      <InfoTooltip
                        title="Bitbucket Cloud API 2.0"
                        badge="Atlassian"
                        whatIsIt="Connects to Bitbucket Cloud for bidirectional code replication, PR mirroring, and CI pipeline status propagation."
                        howItWorks="Supports automated OAuth 2.0 Client Credentials token exchange, direct Bearer access tokens, or Personal App Passwords."
                      />
                    </div>
                  </div>

                  {/* Bitbucket Auth Type Selector */}
                  <div>
                    <div className="flex items-center space-x-1.5 mb-2">
                      <label className="block text-zinc-700 font-medium">Authentication Method</label>
                      <InfoTooltip
                        title="Bitbucket Auth Method"
                        whatIsIt="Choose how GitMirror Hub authenticates against Bitbucket Cloud."
                        recommended="OAuth 2.0 Consumer is recommended for team workspaces. App Password is ideal for personal accounts."
                      />
                    </div>
                    <div className="grid grid-cols-3 gap-2">
                      <button
                        type="button"
                        onClick={() => setBitbucketAuthType('OAUTH2')}
                        className={`px-3 py-2 rounded-lg text-xs font-medium border text-left transition-all ${
                          bitbucketAuthType === 'OAUTH2'
                            ? 'bg-zinc-900 text-white border-zinc-900 shadow-sm'
                            : 'bg-zinc-50 hover:bg-zinc-100 text-zinc-700 border-zinc-200'
                        }`}
                      >
                        <div className="font-semibold">OAuth 2.0 Consumer</div>
                        <div className={`text-[10px] mt-0.5 ${bitbucketAuthType === 'OAUTH2' ? 'text-zinc-300' : 'text-zinc-500'}`}>
                          Client Key & Secret
                        </div>
                      </button>

                      <button
                        type="button"
                        onClick={() => setBitbucketAuthType('APP_PASSWORD')}
                        className={`px-3 py-2 rounded-lg text-xs font-medium border text-left transition-all ${
                          bitbucketAuthType === 'APP_PASSWORD'
                            ? 'bg-zinc-900 text-white border-zinc-900 shadow-sm'
                            : 'bg-zinc-50 hover:bg-zinc-100 text-zinc-700 border-zinc-200'
                        }`}
                      >
                        <div className="font-semibold">App Password</div>
                        <div className={`text-[10px] mt-0.5 ${bitbucketAuthType === 'APP_PASSWORD' ? 'text-zinc-300' : 'text-zinc-500'}`}>
                          Username + Password
                        </div>
                      </button>

                      <button
                        type="button"
                        onClick={() => setBitbucketAuthType('ACCESS_TOKEN')}
                        className={`px-3 py-2 rounded-lg text-xs font-medium border text-left transition-all ${
                          bitbucketAuthType === 'ACCESS_TOKEN'
                            ? 'bg-zinc-900 text-white border-zinc-900 shadow-sm'
                            : 'bg-zinc-50 hover:bg-zinc-100 text-zinc-700 border-zinc-200'
                        }`}
                      >
                        <div className="font-semibold">Access Token</div>
                        <div className={`text-[10px] mt-0.5 ${bitbucketAuthType === 'ACCESS_TOKEN' ? 'text-zinc-300' : 'text-zinc-500'}`}>
                          Repository / Bearer Token
                        </div>
                      </button>
                    </div>
                  </div>

                  {/* Workspace ID */}
                  <div>
                    <div className="flex items-center space-x-1.5 mb-1">
                      <label className="block text-zinc-700 font-medium">Workspace ID / Slug</label>
                      <InfoTooltip
                        title="Bitbucket Workspace"
                        whatIsIt="The unique slug of your Bitbucket workspace (e.g. 'my-company')."
                        howItWorks="Used to search and enumerate repositories under https://api.bitbucket.org/2.0/repositories/{workspace}."
                      />
                    </div>
                    <input
                      type="text"
                      value={bitbucketWorkspace}
                      onChange={(e) => setBitbucketWorkspace(e.target.value)}
                      placeholder="e.g. your-workspace-slug"
                      className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                    />
                  </div>

                  {/* Dynamic Fields for OAuth 2.0 Consumer */}
                  {bitbucketAuthType === 'OAUTH2' && (
                    <div className="space-y-3">
                      <div>
                        <div className="flex items-center space-x-1.5 mb-1">
                          <label className="block text-zinc-700 font-medium">OAuth Consumer Key (Client Key)</label>
                          <InfoTooltip
                            title="OAuth Consumer Key"
                            whatIsIt="The Key generated in Bitbucket Workspace Settings -> OAuth consumers."
                            howItWorks="Used as the client_id for exchanging an automated 2-hour OAuth access token."
                          />
                        </div>
                        <input
                          type="text"
                          value={bitbucketUsername}
                          onChange={(e) => setBitbucketUsername(e.target.value)}
                          placeholder="e.g. u5n8Wk9xYZabc123"
                          className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                        />
                      </div>

                      <div>
                        <div className="flex items-center space-x-1.5 mb-1">
                          <label className="block text-zinc-700 font-medium">
                            OAuth Consumer Secret (Client Secret)
                            {loadedConfig?.hasBitbucketAccessToken && (
                              <span className="text-emerald-600 ml-2 font-normal">(Configured: {loadedConfig.bitbucketAccessTokenMasked})</span>
                            )}
                          </label>
                          <InfoTooltip
                            title="OAuth Consumer Secret"
                            whatIsIt="The Secret generated in Bitbucket Workspace Settings -> OAuth consumers."
                            recommended="Requires scopes: Repositories (Read/Write), Pull Requests (Read/Write), Pipelines (Read/Write), Webhooks (Read/Write)."
                          />
                        </div>
                        <input
                          type="password"
                          value={bitbucketAccessToken}
                          onChange={(e) => setBitbucketAccessToken(e.target.value)}
                          placeholder={loadedConfig?.hasBitbucketAccessToken ? '•••••••• (Leave blank to keep current)' : 'OAuth Consumer Secret'}
                          className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                        />
                      </div>
                    </div>
                  )}

                  {/* Dynamic Fields for App Password */}
                  {bitbucketAuthType === 'APP_PASSWORD' && (
                    <div className="space-y-3">
                      <div>
                        <div className="flex items-center space-x-1.5 mb-1">
                          <label className="block text-zinc-700 font-medium">Atlassian Account Username</label>
                          <InfoTooltip
                            title="Atlassian Account Username"
                            whatIsIt="Your actual Bitbucket account username (e.g. 'john_doe'). Do not use 'x-token-auth'."
                            howItWorks="Used for HTTP Basic authentication with your App Password."
                          />
                        </div>
                        <input
                          type="text"
                          value={bitbucketUsername}
                          onChange={(e) => setBitbucketUsername(e.target.value)}
                          placeholder="e.g. your-atlassian-username"
                          className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                        />
                      </div>

                      <div>
                        <div className="flex items-center space-x-1.5 mb-1">
                          <label className="block text-zinc-700 font-medium">
                            App Password
                            {loadedConfig?.hasBitbucketAccessToken && (
                              <span className="text-emerald-600 ml-2 font-normal">(Configured: {loadedConfig.bitbucketAccessTokenMasked})</span>
                            )}
                          </label>
                          <InfoTooltip
                            title="Bitbucket App Password"
                            whatIsIt="App Password created under Personal Settings -> App Passwords."
                            recommended="Requires scopes: Repositories (Read/Write), Pull Requests (Read/Write), Pipelines (Read/Write), Webhooks (Read/Write)."
                          />
                        </div>
                        <input
                          type="password"
                          value={bitbucketAccessToken}
                          onChange={(e) => setBitbucketAccessToken(e.target.value)}
                          placeholder={loadedConfig?.hasBitbucketAccessToken ? '•••••••• (Leave blank to keep current)' : 'ATBB... App Password'}
                          className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                        />
                      </div>
                    </div>
                  )}

                  {/* Dynamic Fields for Access Token */}
                  {bitbucketAuthType === 'ACCESS_TOKEN' && (
                    <div>
                      <div className="flex items-center space-x-1.5 mb-1">
                        <label className="block text-zinc-700 font-medium">
                          Repository / Workspace Access Token (Bearer)
                          {loadedConfig?.hasBitbucketAccessToken && (
                            <span className="text-emerald-600 ml-2 font-normal">(Configured: {loadedConfig.bitbucketAccessTokenMasked})</span>
                          )}
                        </label>
                        <InfoTooltip
                          title="Bitbucket Access Token"
                          whatIsIt="Bearer token created under Repository Settings -> Access Tokens or Workspace Settings -> Access Tokens."
                        />
                      </div>
                      <input
                        type="password"
                        value={bitbucketAccessToken}
                        onChange={(e) => setBitbucketAccessToken(e.target.value)}
                        placeholder={loadedConfig?.hasBitbucketAccessToken ? '•••••••• (Leave blank to keep current)' : 'Bearer Access Token'}
                        className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                      />
                    </div>
                  )}
                </div>
              )}

              {/* CURSOR ORIGIN CONFIGURATION */}
              {selectedProvider === 'origin' && (
                <div className="space-y-4">
                  <div className="flex items-center justify-between border-b border-zinc-100 pb-3">
                    <div className="flex items-center space-x-2">
                      <h3 className="font-semibold text-zinc-900 text-sm">Cursor Origin Repositories</h3>
                      <InfoTooltip
                        title="Cursor Origin"
                        badge="Origin SCM"
                        whatIsIt="Direct synchronization with Cursor-hosted repositories on origin.cursor.com."
                        howItWorks="Authenticates Git transport via personal access tokens generated with 'origin signin' CLI."
                      />
                    </div>
                  </div>

                  <div>
                    <div className="flex items-center space-x-1.5 mb-1">
                      <label className="block text-zinc-700 font-medium">Origin Endpoint</label>
                      <InfoTooltip
                        title="Origin Endpoint"
                        whatIsIt="Base URL for Cursor Origin Git service."
                        howItWorks="Default: https://origin.cursor.com"
                      />
                    </div>
                    <input
                      type="text"
                      value={originHostUrl}
                      onChange={(e) => setOriginHostUrl(e.target.value)}
                      placeholder="https://origin.cursor.com"
                      className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                    />
                  </div>

                  <div>
                    <div className="flex items-center space-x-1.5 mb-1">
                      <label className="block text-zinc-700 font-medium">
                        Cursor Origin Access Token
                        {loadedConfig?.hasOriginAccessToken && (
                          <span className="text-emerald-600 ml-2 font-normal">(Configured: {loadedConfig.originAccessTokenMasked})</span>
                        )}
                      </label>
                      <InfoTooltip
                        title="Cursor Origin Token"
                        whatIsIt="Authentication token obtained via 'origin signin' CLI or Cursor settings."
                      />
                    </div>
                    <input
                      type="password"
                      value={originAccessToken}
                      onChange={(e) => setOriginAccessToken(e.target.value)}
                      placeholder={loadedConfig?.hasOriginAccessToken ? '•••••••• (Leave blank to keep current)' : 'origin_pat_xxxxxxxxxxxx'}
                      className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                    />
                  </div>
                </div>
              )}

              {/* GENERIC / AZURE DEVOPS CONFIGURATION */}
              {selectedProvider === 'generic' && (
                <div className="space-y-4">
                  <div className="flex items-center justify-between border-b border-zinc-100 pb-3">
                    <div className="flex items-center space-x-2">
                      <h3 className="font-semibold text-zinc-900 text-sm">Azure DevOps / Generic Git SCM</h3>
                      <InfoTooltip
                        title="Generic Git Provider"
                        badge="HTTPS Git"
                        whatIsIt="Connects standard Git remotes such as Azure DevOps (dev.azure.com), AWS CodeCommit, Gitea, or custom servers."
                        howItWorks="Uses standard HTTPS Basic or Bearer authentication for fetch/push replication."
                      />
                    </div>
                  </div>

                  <div>
                    <div className="flex items-center space-x-1.5 mb-1">
                      <label className="block text-zinc-700 font-medium">HTTP Basic Username (Optional)</label>
                      <InfoTooltip
                        title="Basic Auth Username"
                        whatIsIt="Optional username for Git HTTP Basic credentials."
                        howItWorks="For Azure DevOps, this can be your email or 'oauth2'. Leave blank for token-only auth."
                      />
                    </div>
                    <input
                      type="text"
                      value={genericUsername}
                      onChange={(e) => setGenericUsername(e.target.value)}
                      placeholder="username or blank for token-only"
                      className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                    />
                  </div>

                  <div>
                    <div className="flex items-center space-x-1.5 mb-1">
                      <label className="block text-zinc-700 font-medium">
                        Generic Personal Access Token
                        {loadedConfig?.hasGenericAccessToken && (
                          <span className="text-emerald-600 ml-2 font-normal">(Configured: {loadedConfig.genericAccessTokenMasked})</span>
                        )}
                      </label>
                      <InfoTooltip
                        title="Generic Token / PAT"
                        whatIsIt="Access token with read/write repository permissions on your Git host."
                      />
                    </div>
                    <input
                      type="password"
                      value={genericAccessToken}
                      onChange={(e) => setGenericAccessToken(e.target.value)}
                      placeholder={loadedConfig?.hasGenericAccessToken ? '•••••••• (Leave blank to keep current)' : 'Generic PAT / Bearer Token'}
                      className="w-full bg-white border border-zinc-200 rounded-lg px-3 py-2 text-zinc-900 font-mono text-xs focus:outline-none focus:border-zinc-400"
                    />
                  </div>
                </div>
              )}

              {feedback && (
                <div className={`p-3 rounded-lg border text-xs flex items-center space-x-2 ${
                  feedback.type === 'success' ? 'bg-emerald-50 border-emerald-200 text-emerald-800' : 'bg-rose-50 border-rose-200 text-rose-800'
                }`}>
                  {feedback.type === 'success' ? <CheckCircle2 className="w-4 h-4 shrink-0 text-emerald-600" /> : <AlertCircle className="w-4 h-4 shrink-0 text-rose-600" />}
                  <span>{feedback.message}</span>
                </div>
              )}

              {/* Dynamic Diagnostic Test Connection Feedback Card */}
              {testResult && (
                <div className={`p-4 rounded-xl border text-xs space-y-2.5 transition-all animate-in fade-in duration-200 ${
                  testResult.valid
                    ? 'bg-emerald-50/70 border-emerald-200 text-emerald-950'
                    : 'bg-rose-50/70 border-rose-200 text-rose-950'
                }`}>
                  <div className="flex items-center justify-between">
                    <div className="flex items-center space-x-2">
                      {testResult.valid ? (
                        <CheckCircle2 className="w-4 h-4 text-emerald-600 shrink-0" />
                      ) : (
                        <AlertCircle className="w-4 h-4 text-rose-600 shrink-0" />
                      )}
                      <span className="font-semibold text-xs">
                        {testResult.valid ? 'Connection & Credentials Verified' : 'Connection Test Failed'}
                      </span>
                    </div>
                    <span className={`px-2 py-0.5 rounded text-[10px] font-mono font-medium ${
                      testResult.valid ? 'bg-emerald-100 text-emerald-800 border border-emerald-200' : 'bg-rose-100 text-rose-800 border border-rose-200'
                    }`}>
                      HTTP {testResult.httpStatusCode || (testResult.valid ? 200 : 400)}
                    </span>
                  </div>

                  <p className="text-xs text-zinc-700 leading-relaxed font-sans">{testResult.message}</p>

                  {testResult.passedChecks && testResult.passedChecks.length > 0 && (
                    <div className="space-y-1 pt-1.5 border-t border-emerald-200/60">
                      <span className="text-[11px] font-semibold text-emerald-900 block">Verified Capabilities:</span>
                      {testResult.passedChecks.map((check, idx) => (
                        <div key={idx} className="flex items-center space-x-1.5 text-[11px] text-emerald-800">
                          <Check className="w-3 h-3 text-emerald-600 shrink-0" />
                          <span>{check}</span>
                        </div>
                      ))}
                    </div>
                  )}

                  {testResult.errors && testResult.errors.length > 0 && (
                    <div className="space-y-1 pt-1.5 border-t border-rose-200/60">
                      <span className="text-[11px] font-semibold text-rose-900 block">Identified Issues:</span>
                      {testResult.errors.map((err, idx) => (
                        <div key={idx} className="flex items-center space-x-1.5 text-[11px] text-rose-800">
                          <AlertCircle className="w-3 h-3 text-rose-600 shrink-0" />
                          <span>{err}</span>
                        </div>
                      ))}
                    </div>
                  )}

                  {testResult.warnings && testResult.warnings.length > 0 && (
                    <div className="space-y-1 pt-1.5 border-t border-amber-200/60">
                      <span className="text-[11px] font-semibold text-amber-900 block">Warnings:</span>
                      {testResult.warnings.map((warn, idx) => (
                        <div key={idx} className="flex items-center space-x-1.5 text-[11px] text-amber-800">
                          <AlertCircle className="w-3 h-3 text-amber-600 shrink-0" />
                          <span>{warn}</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}

              {selectedProvider !== 'github' && selectedProvider !== 'ghes' && (
              <div className="flex items-center justify-end space-x-2.5 pt-2">
                <button
                  type="button"
                  onClick={handleTestConnection}
                  disabled={testingConnection || saving}
                  className="inline-flex items-center space-x-1.5 bg-zinc-100 hover:bg-zinc-200 text-zinc-800 text-xs px-3.5 py-2 rounded-lg font-medium border border-zinc-200 shadow-sm transition-colors disabled:opacity-50"
                >
                  <RefreshCw className={`w-3.5 h-3.5 ${testingConnection ? 'animate-spin' : ''}`} />
                  <span>{testingConnection ? 'Testing Connection...' : 'Test Connection'}</span>
                </button>
                <button
                  type="submit"
                  disabled={saving || testingConnection}
                  className="inline-flex items-center space-x-1.5 bg-zinc-900 hover:bg-zinc-800 text-white text-xs px-4 py-2 rounded-lg font-medium shadow-sm transition-colors disabled:opacity-50"
                >
                  <Save className="w-3.5 h-3.5" />
                  <span>{saving ? 'Saving...' : 'Save Provider Credentials'}</span>
                </button>
              </div>
              )}
            </form>
          </div>
        </div>

        {/* Right Info View */}
        <div className="lg:col-span-5 space-y-4">
          {/* Dynamic Provider Setup Guide & Webhook Reference Card */}
          <div className="rounded-2xl border border-zinc-200/90 bg-white p-5 shadow-sm space-y-4">
            <div className="flex items-center justify-between border-b border-zinc-100 pb-3">
              <div className="flex items-center space-x-2">
                <div className="p-1.5 rounded-lg bg-zinc-100">
                  {currentGuide.icon}
                </div>
                <div>
                  <h3 className="text-xs font-semibold text-zinc-900">{currentGuide.title}</h3>
                  <p className="text-[10px] text-zinc-400">OAuth & Webhook Configuration Blueprint</p>
                </div>
              </div>
              <a
                href={currentGuide.docUrl}
                target="_blank"
                rel="noreferrer"
                className="text-[11px] text-zinc-500 hover:text-zinc-900 flex items-center space-x-1 font-medium transition-colors"
              >
                <span>Docs</span>
                <ExternalLink className="w-3 h-3" />
              </a>
            </div>

            {/* Step-by-Step Procedure */}
            <div className="space-y-2">
              <h4 className="text-[11px] font-semibold uppercase tracking-wider text-zinc-500 flex items-center space-x-1.5">
                <Terminal className="w-3 h-3" />
                <span>Setup Procedure</span>
              </h4>
              <ol className="space-y-1.5 text-[11px] text-zinc-600 pl-4 list-decimal">
                {currentGuide.steps.map((step, idx) => (
                  <li key={idx} className="pl-1 leading-relaxed">
                    {step}
                  </li>
                ))}
              </ol>
            </div>

            {/* Webhook Payload URLs — paste into GitHub App / repo webhook settings */}
            <div className="space-y-3 pt-1">
              <div className="space-y-1.5">
                <div className="flex items-center justify-between">
                  <span className="text-[10px] font-semibold uppercase tracking-wider text-zinc-500 flex items-center space-x-1">
                    <Webhook className="w-3 h-3 text-emerald-600" />
                    <span>Edge webhook URL (recommended)</span>
                  </span>
                  <button
                    type="button"
                    onClick={() => copyToClipboard(currentGuide.webhookUrl, 'edge-url')}
                    className="text-[10px] text-zinc-500 hover:text-zinc-900 flex items-center space-x-1 font-medium"
                  >
                    {copiedKey === 'edge-url' ? (
                      <>
                        <Check className="w-2.5 h-2.5 text-emerald-600" />
                        <span className="text-emerald-600">Copied!</span>
                      </>
                    ) : (
                      <>
                        <Copy className="w-2.5 h-2.5" />
                        <span>Copy URL</span>
                      </>
                    )}
                  </button>
                </div>
                <p className="text-[10px] text-zinc-500 leading-relaxed">
                  Paste this into the GitHub App (or repo) webhook configuration. One URL for the App — not per installation card.
                </p>
                <div className="p-2 bg-zinc-900 text-zinc-200 rounded-lg font-mono text-[10px] break-all select-all">
                  {currentGuide.webhookUrl}
                </div>
              </div>
              {currentGuide.backendWebhookUrl && (
                <div className="space-y-1.5">
                  <div className="flex items-center justify-between">
                    <span className="text-[10px] font-semibold uppercase tracking-wider text-zinc-500">
                      Hub direct (no Worker)
                    </span>
                    <button
                      type="button"
                      onClick={() => copyToClipboard(currentGuide.backendWebhookUrl!, 'hub-url')}
                      className="text-[10px] text-zinc-500 hover:text-zinc-900 flex items-center space-x-1 font-medium"
                    >
                      {copiedKey === 'hub-url' ? (
                        <>
                          <Check className="w-2.5 h-2.5 text-emerald-600" />
                          <span className="text-emerald-600">Copied!</span>
                        </>
                      ) : (
                        <>
                          <Copy className="w-2.5 h-2.5" />
                          <span>Copy URL</span>
                        </>
                      )}
                    </button>
                  </div>
                  <p className="text-[10px] text-zinc-500 leading-relaxed">
                    Use only if the Worker is not deployed. GitHub.com cannot call localhost; GHES on the same network can.
                  </p>
                  <div className="p-2 bg-zinc-100 text-zinc-700 rounded-lg font-mono text-[10px] break-all select-all">
                    {currentGuide.backendWebhookUrl}
                  </div>
                </div>
              )}
            </div>

            {/* Required Permissions & Scopes */}
            <div className="space-y-1.5 pt-1">
              <h4 className="text-[10px] font-semibold uppercase tracking-wider text-zinc-500 flex items-center space-x-1">
                <ShieldCheck className="w-3 h-3 text-blue-600" />
                <span>Required Scopes & Permissions</span>
              </h4>
              <div className="flex flex-wrap gap-1.5">
                {currentGuide.scopes.map((scope, idx) => (
                  <span
                    key={idx}
                    className="px-2 py-0.5 rounded-md text-[10px] font-medium bg-zinc-100 text-zinc-700 border border-zinc-200/80 font-mono"
                  >
                    {scope}
                  </span>
                ))}
              </div>
            </div>

            {/* Subscribed Events */}
            <div className="space-y-1.5 pt-1">
              <h4 className="text-[10px] font-semibold uppercase tracking-wider text-zinc-500 flex items-center space-x-1">
                <Radio className="w-3 h-3 text-amber-600" />
                <span>Subscribed Webhook Events</span>
              </h4>
              <div className="flex flex-wrap gap-1.5">
                {currentGuide.events.map((evt, idx) => (
                  <span
                    key={idx}
                    className="px-2 py-0.5 rounded-md text-[10px] font-medium bg-emerald-50 text-emerald-700 border border-emerald-200/60 font-mono"
                  >
                    {evt}
                  </span>
                ))}
              </div>
            </div>
          </div>

          {/* Accessible Repositories Card */}
          <div className="rounded-2xl border border-zinc-200/90 bg-white p-5 shadow-sm space-y-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center space-x-2">
                <BookOpen className="w-4 h-4 text-zinc-600" />
                <h3 className="text-xs font-semibold text-zinc-900">
                  {getProviderDisplayName(selectedProvider)} Repositories
                </h3>
              </div>
              <button
                type="button"
                onClick={() => fetchAccessibleRepos(selectedProvider)}
                disabled={fetchingRepos}
                className="text-xs text-zinc-500 hover:text-zinc-900 flex items-center space-x-1"
              >
                <RefreshCw className={`w-3 h-3 ${fetchingRepos ? 'animate-spin' : ''}`} />
                <span>Refresh</span>
              </button>
            </div>

            {fetchingRepos ? (
              <div className="p-6 text-center text-xs text-zinc-400 flex items-center justify-center space-x-2">
                <RefreshCw className="w-3.5 h-3.5 animate-spin text-zinc-500" />
                <span>Querying {getProviderDisplayName(selectedProvider)} API...</span>
              </div>
            ) : repos.length === 0 ? (
              <div className="p-4 bg-zinc-50 rounded-xl border border-zinc-200/70 text-center text-xs text-zinc-500">
                {isSelectedProviderConfigured()
                  ? `No repositories found for ${getProviderDisplayName(selectedProvider)}. Ensure your token or OAuth app has repository read permissions.`
                  : `Configure ${getProviderDisplayName(selectedProvider)} credentials on the left to discover repositories.`}
              </div>
            ) : (
              <div className="divide-y divide-zinc-100 max-h-72 overflow-y-auto">
                {repos.map((repo) => (
                  <div key={repo.id} className="py-2 flex items-center justify-between text-xs">
                    <div>
                      <span className="font-semibold text-zinc-900">{repo.fullName}</span>
                      <span className="text-[10px] text-zinc-400 block font-mono">{repo.cloneUrl}</span>
                    </div>
                    <span className="text-[10px] bg-zinc-100 px-2 py-0.5 rounded text-zinc-600 font-mono">
                      {repo.defaultBranch}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
