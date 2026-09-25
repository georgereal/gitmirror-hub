import React, { useEffect, useMemo, useState } from 'react';
import { AlertCircle, Check, Link2, RefreshCw, Unlink } from 'lucide-react';
import {
  applyWriteAuthority,
  getWriteAuthority,
  WriteAuthoritySide,
  WriteAuthorityView,
} from '../../services/api';
import { InfoTooltip } from '../../components/InfoTooltip';
import { RepositoryRulesetModal } from './RepositoryRulesetModal';

type Access = 'write' | 'readonly';
type Scope = 'repo' | 'org' | 'enterprise';
type Target = 'this_repo' | 'all_repos';
type OrgRow = WriteAuthorityView['orgs'][number];
type EnterpriseRow = WriteAuthorityView['enterprises'][number];
type Pair = WriteAuthorityView['pairs'][number];

interface DraftSide {
  access: Access;
  scope: Scope;
  target: Target;
}

interface PairDraft {
  mode: 'linked' | 'independent';
  A: DraftSide;
  B: DraftSide;
  confirm: string;
}

const chipClass = (on: boolean, disabled: boolean) =>
  `px-2 py-0.5 rounded-full text-[11px] font-medium border ${
    disabled
      ? 'border-zinc-200 text-zinc-300 bg-zinc-50 cursor-not-allowed'
      : on
        ? 'border-zinc-900 bg-zinc-900 text-white'
        : 'border-zinc-200 bg-white text-zinc-700 hover:border-zinc-400'
  }`;

function ownerOf(fullName?: string | null) {
  if (!fullName) return '';
  const slash = fullName.indexOf('/');
  return slash < 0 ? fullName : fullName.slice(0, slash);
}

function liveAccess(state?: string | null): Access {
  return state === 'active' ? 'readonly' : 'write';
}

function draftSide(side?: WriteAuthoritySide): DraftSide {
  const scope: Scope = side?.scope === 'org' || side?.scope === 'enterprise' ? side.scope : 'repo';
  const accessState = scope === 'repo' ? side?.repoRulesetState ?? side?.rulesetState : side?.rulesetState;
  return {
    access: liveAccess(accessState),
    scope,
    target: side?.target === 'all_repos' ? 'all_repos' : 'this_repo',
  };
}

function accessForScope(side: WriteAuthoritySide | undefined, scope: Scope, current: Access): Access {
  if (scope === 'repo') return liveAccess(side?.repoRulesetState);
  if (side?.scope === scope) return liveAccess(side.rulesetState);
  return current;
}

function pairDraft(pair: Pair): PairDraft {
  return {
    mode: pair.linkMode === 'independent' ? 'independent' : 'linked',
    A: draftSide(pair.sides.find((side) => side.side === 'A')),
    B: draftSide(pair.sides.find((side) => side.side === 'B')),
    confirm: '',
  };
}

export const WriteAuthorityPage: React.FC = () => {
  const [view, setView] = useState<WriteAuthorityView | null>(null);
  const [loading, setLoading] = useState(true);
  const [drafts, setDrafts] = useState<Record<string, PairDraft>>({});
  const [confirmText, setConfirmText] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [message, setMessage] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);
  const [repoModal, setRepoModal] = useState(false);

  const load = async (refresh = false) => {
    if (refresh) setRefreshing(true);
    else setLoading(true);
    setMessage(null);
    try {
      const next = await getWriteAuthority(refresh);
      setView(next);
      const seeded: Record<string, PairDraft> = {};
      for (const pair of next.pairs) seeded[pair.id] = pairDraft(pair);
      setDrafts(seeded);
      if (refresh) setMessage({ type: 'ok', text: 'Rulesets re-read from GitHub.' });
      return next;
    } catch (e: any) {
      setMessage({ type: 'err', text: e.response?.data?.message || e.message || 'Could not load replica rulesets.' });
      return null;
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    void load(false);
  }, []);

  const placedPairIds = useMemo(() => {
    const ids = new Set<string>();
    if (!view) return ids;
    for (const org of view.orgs) {
      for (const pair of view.pairs) {
        if (pairTouchesOrg(pair, org)) ids.add(pair.id);
      }
    }
    return ids;
  }, [view]);

  const loosePairs = view?.pairs.filter((pair) => !placedPairIds.has(pair.id)) ?? [];

  const patchDraft = (pairId: string, recipe: (draft: PairDraft) => PairDraft) => {
    setDrafts((prev) => {
      const current = prev[pairId];
      if (!current) return prev;
      return { ...prev, [pairId]: recipe(current) };
    });
  };

  const setAccess = (pairId: string, letter: 'A' | 'B', access: Access) => {
    patchDraft(pairId, (draft) => {
      const next = { ...draft, [letter]: { ...draft[letter], access } };
      if (draft.mode === 'linked' && access === 'readonly') {
        const other = letter === 'A' ? 'B' : 'A';
        next[other] = { ...next[other], access: 'write' };
      }
      return next;
    });
  };

  const savePair = async (pair: Pair) => {
    const draft = drafts[pair.id];
    if (!draft) return;
    const needsConfirm = (['A', 'B'] as const).some(
      (letter) => draft[letter].access === 'readonly' && draft[letter].target === 'all_repos' && draft[letter].scope !== 'repo'
    );
    setBusy(true);
    setMessage(null);
    try {
      const updated = await applyWriteAuthority({
        link: draft.mode,
        pairId: pair.id,
        confirmAllRepos: needsConfirm ? draft.confirm : undefined,
        sides: (['A', 'B'] as const).map((letter) => ({
          side: letter,
          access: draft[letter].access,
          scope: draft[letter].scope,
          target: draft[letter].scope === 'repo' ? 'this_repo' : draft[letter].target,
        })),
      });
      setView(updated);
      const seeded: Record<string, PairDraft> = {};
      for (const item of updated.pairs) seeded[item.id] = pairDraft(item);
      setDrafts(seeded);
      setMessage({ type: 'ok', text: 'Ruleset enforced.' });
    } catch (e: any) {
      setMessage({ type: 'err', text: e.response?.data?.message || e.message || 'Update failed.' });
    } finally {
      setBusy(false);
    }
  };

  const saveOrg = async (org: OrgRow, access: Access) => {
    const key = `org:${org.credentialId}:${org.orgLogin}`;
    setBusy(true);
    setMessage(null);
    try {
      const updated = await applyWriteAuthority({
        org: { credentialId: org.credentialId, orgLogin: org.orgLogin, access },
        confirmAllRepos: access === 'readonly' ? 'ALL REPOS' : undefined,
      });
      setView(updated);
      setMessage({ type: 'ok', text: `${org.orgLogin} ruleset ${access === 'readonly' ? 'enforced' : 'disabled'}.` });
    } catch (e: any) {
      setMessage({ type: 'err', text: e.response?.data?.message || e.message || 'Update failed.' });
    } finally {
      setBusy(false);
    }
  };

  const saveEnterprise = async (row: EnterpriseRow, access: Access) => {
    setBusy(true);
    setMessage(null);
    try {
      const updated = await applyWriteAuthority({
        enterprise: { credentialId: row.credentialId, access },
        confirmAllRepos: access === 'readonly' ? 'ALL REPOS' : undefined,
      });
      setView(updated);
      setMessage({ type: 'ok', text: `${row.slug} ruleset ${access === 'readonly' ? 'enforced' : 'disabled'}.` });
    } catch (e: any) {
      setMessage({ type: 'err', text: e.response?.data?.message || e.message || 'Update failed.' });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-1">
          <h3 className="text-sm font-semibold text-zinc-900">Replica rulesets</h3>
          <InfoTooltip
            title="Replica rulesets"
            position="bottom"
            whatIsIt="GitHub rulesets that decide whether a mirror side can accept pushes."
            howItWorks="Read-only installs the selected ruleset when it is missing and sets enforcement to active immediately. Write sets that ruleset to disabled immediately. The mirror App is the only bypass actor."
            recommended="Manage a repository opens one GitHub App and loads repositories a page at a time. An organization-wide ruleset needs GitHub Team."
          />
        </div>
        <div className="flex items-center gap-1">
          <button
            type="button"
            disabled={loading || busy || refreshing}
            onClick={() => void load(true)}
            aria-label="Re-read rulesets from GitHub"
            className="inline-flex items-center gap-1 px-2 py-1 rounded-md border border-zinc-200 bg-white text-[11px] font-medium text-zinc-700 hover:bg-zinc-50 disabled:opacity-50"
          >
            <RefreshCw className={`w-3 h-3 ${refreshing ? 'animate-spin' : ''}`} />
            Refresh
          </button>
          <button
            type="button"
            onClick={() => setRepoModal(true)}
            className="inline-flex items-center gap-1 rounded-md bg-zinc-900 px-2 py-1 text-[11px] font-medium text-white"
          >
            Manage repository
          </button>
        </div>
      </div>

      {message && (
        <div
          className={`flex items-center gap-1.5 text-[11px] rounded-md border px-2 py-1 ${
            message.type === 'ok'
              ? 'border-emerald-200 bg-emerald-50 text-emerald-800'
              : 'border-red-200 bg-red-50 text-red-800'
          }`}
        >
          {message.type === 'ok' ? <Check className="w-3 h-3" /> : <AlertCircle className="w-3 h-3" />}
          <span>{message.text}</span>
        </div>
      )}

      {loading && <p className="text-[11px] text-zinc-500">Loading organizations…</p>}

      <RepositoryRulesetModal open={repoModal} onClose={() => setRepoModal(false)} />

      {!loading && view && view.orgs.map((org) => {
        const pairs = view.pairs.filter((pair) => pairTouchesOrg(pair, org));
        return (
          <OrgCard
            key={`${org.credentialId}:${org.orgLogin}`}
            org={org}
            pairs={pairs}
            drafts={drafts}
            confirm={confirmText[`org:${org.credentialId}:${org.orgLogin}`] || ''}
            busy={busy}
            onConfirm={(value) =>
              setConfirmText((prev) => ({ ...prev, [`org:${org.credentialId}:${org.orgLogin}`]: value }))
            }
            onApplyOrg={(access) => saveOrg(org, access)}
            onAccess={(pairId, letter, access) => setAccess(pairId, letter, access)}
            onPatch={patchDraft}
            onApplyPair={savePair}
          />
        );
      })}

      {!loading && loosePairs.length > 0 && (
        <section className="border border-zinc-200 rounded-lg bg-white">
          <div className="flex items-center gap-1 px-2.5 py-1.5 border-b border-zinc-100">
            <h4 className="text-xs font-semibold text-zinc-900">Other mirrors</h4>
            <InfoTooltip
              title="Other mirrors"
              position="bottom"
              whatIsIt="Mirror pairs whose repositories are not under an organization installation."
            />
          </div>
          {loosePairs.map((pair) => (
            <PairBlock
              key={pair.id}
              pair={pair}
              draft={drafts[pair.id]}
              busy={busy}
              onAccess={(letter, access) => setAccess(pair.id, letter, access)}
              onPatch={(recipe) => patchDraft(pair.id, recipe)}
              onApply={() => savePair(pair)}
            />
          ))}
        </section>
      )}

      {!loading && view && view.enterprises.length > 0 && (
        <section className="border border-zinc-200 rounded-lg bg-white divide-y divide-zinc-100">
          <div className="flex items-center gap-1 px-2.5 py-1.5">
            <h4 className="text-xs font-semibold text-zinc-900">Enterprises</h4>
            <InfoTooltip
              title="Enterprise ruleset"
              position="bottom"
              whatIsIt="gitmirror-enterprise-readonly on the enterprise slug saved with the GitHub App."
              howItWorks="Read-only enforces it on every repository immediately. GitHub Enterprise Server has no enterprise ruleset API."
            />
          </div>
          {view.enterprises.map((row) => (
            <FleetLine
              key={`${row.credentialId}:${row.slug}`}
              title={row.slug}
              subtitle={row.credentialLabel}
              state={row.rulesetState}
              name={row.rulesetName}
              detail={row.rulesetDetail || row.disabledReason}
              disabled={!row.probeOk || busy}
              confirm={confirmText[`enterprise:${row.credentialId}:${row.slug}`] || ''}
              onConfirm={(value) =>
                setConfirmText((prev) => ({ ...prev, [`enterprise:${row.credentialId}:${row.slug}`]: value }))
              }
              onApply={(access) => saveEnterprise(row, access)}
            />
          ))}
        </section>
      )}
    </div>
  );
};

function pairTouchesOrg(pair: Pair, org: OrgRow) {
  return pair.sides.some(
    (side) => side.credentialId === org.credentialId && ownerOf(side.repoFullName).toLowerCase() === org.orgLogin.toLowerCase()
  );
}

const OrgCard: React.FC<{
  org: OrgRow;
  pairs: Pair[];
  drafts: Record<string, PairDraft>;
  confirm: string;
  busy: boolean;
  onConfirm: (value: string) => void;
  onApplyOrg: (access: Access) => void;
  onAccess: (pairId: string, letter: 'A' | 'B', access: Access) => void;
  onPatch: (pairId: string, recipe: (draft: PairDraft) => PairDraft) => void;
  onApplyPair: (pair: Pair) => void;
}> = ({ org, pairs, drafts, confirm, busy, onConfirm, onApplyOrg, onAccess, onPatch, onApplyPair }) => (
  <section className="border border-zinc-200 rounded-lg bg-white">
    <FleetLine
      title={org.orgLogin}
      subtitle={org.credentialLabel}
      state={org.rulesetState}
      name={org.rulesetName || 'gitmirror-org-readonly'}
      detail={org.rulesetDetail || org.disabledReason}
      hint="This switch writes gitmirror-org-readonly across the organization. A ruleset with any other name stays missing here. GitHub does not enforce an organization ruleset until the organization is on GitHub Team. A single repository, including one owned by a personal account, is chosen above."
      disabled={!org.canManage || busy}
      confirm={confirm}
      onConfirm={onConfirm}
      onApply={onApplyOrg}
    />
    {pairs.length === 0 && (
      <p className="px-2.5 py-1 text-[11px] text-zinc-400 border-t border-zinc-100">No mirror pairs in this organization.</p>
    )}
    {pairs.map((pair) => (
      <PairBlock
        key={pair.id}
        pair={pair}
        draft={drafts[pair.id]}
        busy={busy}
        onAccess={(letter, access) => onAccess(pair.id, letter, access)}
        onPatch={(recipe) => onPatch(pair.id, recipe)}
        onApply={() => onApplyPair(pair)}
      />
    ))}
  </section>
);

const FleetLine: React.FC<{
  title: string;
  subtitle?: string | null;
  state?: string | null;
  name?: string | null;
  detail?: string | null;
  hint?: string;
  confirmRequired?: boolean;
  disabled: boolean;
  confirm: string;
  onConfirm: (value: string) => void;
  onApply: (access: Access) => void;
}> = ({ title, subtitle, state, name, detail, hint, confirmRequired = true, disabled, confirm, onConfirm, onApply }) => {
  const [access, setAccess] = useState<Access>(liveAccess(state));
  useEffect(() => setAccess(liveAccess(state)), [state]);
  const needsConfirm = confirmRequired && access === 'readonly';
  return (
    <div className="flex flex-wrap items-center gap-x-2 gap-y-1 px-2.5 py-1.5">
      <div className="min-w-0 mr-auto">
        <p className="text-xs font-medium text-zinc-900 truncate">{title}</p>
        {subtitle && <p className="text-[10px] text-zinc-500 truncate">{subtitle}</p>}
      </div>
      {hint && <InfoTooltip title={name || 'Organization ruleset'} position="bottom" whatIsIt={hint} />}
      <RulesetMark state={state} name={name} detail={detail} />
      <AccessToggle access={access} disabled={disabled} onChange={setAccess} />
      {needsConfirm && (
        <input
          value={confirm}
          onChange={(e) => onConfirm(e.target.value)}
          placeholder="ALL REPOS"
          aria-label="Confirm every repository"
          className="w-24 bg-white border border-zinc-200 rounded px-1.5 py-0.5 font-mono text-[10px]"
        />
      )}
      {needsConfirm && (
        <InfoTooltip
          title="Every repository"
          position="bottom"
          whatIsIt="Read-only on an organization or enterprise ruleset covers every repository. Type ALL REPOS to enforce it."
        />
      )}
      <button
        type="button"
        disabled={disabled || (needsConfirm && confirm !== 'ALL REPOS')}
        onClick={() => onApply(access)}
        className="px-2 py-0.5 rounded bg-zinc-900 text-white text-[11px] font-medium disabled:opacity-40"
      >
        Apply
      </button>
    </div>
  );
};

const PairBlock: React.FC<{
  pair: Pair;
  draft?: PairDraft;
  busy: boolean;
  onAccess: (letter: 'A' | 'B', access: Access) => void;
  onPatch: (recipe: (draft: PairDraft) => PairDraft) => void;
  onApply: () => void;
}> = ({ pair, draft, busy, onAccess, onPatch, onApply }) => {
  if (!draft) return null;
  const needsConfirm = (['A', 'B'] as const).some(
    (letter) => draft[letter].access === 'readonly' && draft[letter].target === 'all_repos' && draft[letter].scope !== 'repo'
  );
  return (
    <div className="border-t border-zinc-100 px-2.5 py-1.5 space-y-1">
      <div className="flex items-center gap-1.5">
        <p className="text-[11px] font-medium text-zinc-800 truncate">{pair.name}</p>
        <button
          type="button"
          onClick={() =>
            onPatch((current) => {
              const mode = current.mode === 'linked' ? 'independent' : 'linked';
              const next = { ...current, mode };
              if (mode === 'linked' && next.A.access === 'readonly' && next.B.access === 'readonly') {
                next.B = { ...next.B, access: 'write' };
              }
              return next;
            })
          }
          className="inline-flex items-center gap-1 text-[10px] text-zinc-500 hover:text-zinc-800"
        >
          {draft.mode === 'linked' ? <Link2 className="w-3 h-3" /> : <Unlink className="w-3 h-3" />}
          {draft.mode === 'linked' ? 'Linked' : 'Separate'}
        </button>
        <InfoTooltip
          title="Pair link"
          position="bottom"
          whatIsIt="Linked keeps one side writable while the other is read-only. Separate lets each side be enforced on its own."
        />
      </div>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-1">
        {(['A', 'B'] as const).map((letter) => {
          const server = pair.sides.find((side) => side.side === letter);
          return (
            <SideLine
              key={letter}
              letter={letter}
              server={server}
              draft={draft[letter]}
              disabled={busy}
              onAccess={(access) => onAccess(letter, access)}
              onScope={(scope) =>
                onPatch((current) => ({
                  ...current,
                  [letter]: {
                    ...current[letter],
                    scope,
                    target: scope === 'repo' ? 'this_repo' : current[letter].target,
                    access: accessForScope(server, scope, current[letter].access),
                  },
                }))
              }
              onTarget={(target) =>
                onPatch((current) => ({ ...current, [letter]: { ...current[letter], target } }))
              }
            />
          );
        })}
      </div>
      {needsConfirm && (
        <input
          value={draft.confirm}
          onChange={(e) => onPatch((current) => ({ ...current, confirm: e.target.value }))}
          placeholder="ALL REPOS"
          aria-label="Confirm every repository"
          className="w-24 bg-white border border-zinc-200 rounded px-1.5 py-0.5 font-mono text-[10px]"
        />
      )}
      <button
        type="button"
        disabled={busy || (needsConfirm && draft.confirm !== 'ALL REPOS')}
        onClick={onApply}
        className="px-2 py-0.5 rounded bg-zinc-900 text-white text-[11px] font-medium disabled:opacity-40"
      >
        Apply
      </button>
    </div>
  );
};

const SideLine: React.FC<{
  letter: 'A' | 'B';
  server?: WriteAuthoritySide;
  draft: DraftSide;
  disabled: boolean;
  onAccess: (access: Access) => void;
  onScope: (scope: Scope) => void;
  onTarget: (target: Target) => void;
}> = ({ letter, server, draft, disabled, onAccess, onScope, onTarget }) => {
  const scopes: Array<{ id: Scope; label: string; chip?: WriteAuthoritySide['repo']; title: string }> = [
    {
      id: 'repo',
      label: 'Repo',
      chip: server?.repo,
      title: 'Repository ruleset gitmirror-replica-readonly on this repository. GitHub enforces it on a public repository.',
    },
    { id: 'org', label: 'Org', chip: server?.org, title: 'Organization ruleset. Not enforced until this organization is on GitHub Team.' },
    { id: 'enterprise', label: 'Enterprise', chip: server?.enterprise, title: 'Enterprise ruleset for the slug saved on this GitHub App.' },
  ];
  const selected = scopes.find((chip) => chip.id === draft.scope);
  const savedMatches = server?.scope === draft.scope && (draft.scope === 'repo' || server?.target === draft.target);
  const mark = draft.scope === 'repo'
    ? {
        state: server?.repoRulesetState,
        name: server?.repoRulesetName || 'gitmirror-replica-readonly',
        detail: server?.repoRulesetDetail,
      }
    : savedMatches
      ? { state: server?.rulesetState, name: server?.rulesetName, detail: server?.rulesetDetail || server?.coverageNote }
      : {
          state: 'unloaded',
          name: draft.scope === 'enterprise' ? 'Enterprise ruleset' : 'Organization ruleset',
          detail: 'Apply writes this scope. Refresh then shows whether GitHub has that ruleset.',
        };
  const controlsDisabled = disabled || selected?.chip?.enabled === false;
  return (
    <div className="flex flex-wrap items-center gap-1 rounded border border-zinc-100 px-1.5 py-1">
      <span className="text-[10px] font-semibold text-zinc-400 w-3">{letter}</span>
      <span className="text-[11px] text-zinc-800 truncate max-w-[140px]">{server?.repoFullName || '—'}</span>
      <RulesetMark state={mark.state} name={mark.name} detail={mark.detail} />
      <AccessToggle access={draft.access} disabled={controlsDisabled} onChange={onAccess} />
      {scopes.map((chip) => (
        <button
          key={chip.id}
          type="button"
          disabled={disabled || !chip.chip?.enabled}
          title={chip.chip?.enabled ? chip.title : chip.chip?.reason || 'Unavailable'}
          onClick={() => onScope(chip.id)}
          className={chipClass(draft.scope === chip.id, disabled || !chip.chip?.enabled)}
        >
          {chip.label}
        </button>
      ))}
      {draft.scope !== 'repo' && (
        <select
          value={draft.target}
          onChange={(e) => onTarget(e.target.value as Target)}
          className="bg-white border border-zinc-200 rounded px-1 py-0.5 text-[10px]"
        >
          <option value="this_repo">This repo</option>
          <option value="all_repos">All repos</option>
        </select>
      )}
    </div>
  );
};

const AccessToggle: React.FC<{
  access: Access;
  disabled: boolean;
  onChange: (access: Access) => void;
}> = ({ access, disabled, onChange }) => (
  <span className="inline-flex items-center gap-1">
    <button type="button" disabled={disabled} onClick={() => onChange('write')} className={chipClass(access === 'write', disabled)}>
      Write
    </button>
    <button type="button" disabled={disabled} onClick={() => onChange('readonly')} className={chipClass(access === 'readonly', disabled)}>
      Read-only
    </button>
  </span>
);

const RulesetMark: React.FC<{ state?: string | null; name?: string | null; detail?: string | null }> = ({
  state,
  name,
  detail,
}) => {
  const label = state === 'active'
    ? 'Enforced'
    : state === 'disabled'
      ? 'Off'
      : state === 'unloaded'
        ? 'Not read yet'
        : state === 'unknown'
          ? 'Could not read'
          : 'Missing ruleset';
  const tone =
    state === 'active'
      ? 'bg-amber-50 text-amber-800 border-amber-200'
      : state === 'disabled' || state === 'unloaded'
        ? 'bg-zinc-50 text-zinc-500 border-zinc-200'
        : state === 'unknown'
          ? 'bg-amber-50 text-amber-800 border-amber-200'
          : 'bg-rose-50 text-rose-700 border-rose-200';
  const ruleset = name || 'Ruleset';
  const whatIsIt =
    state === 'active'
      ? `${ruleset} is enforced. Pushes are blocked except the mirror App.`
      : state === 'disabled'
        ? `${ruleset} exists and enforcement is off.`
        : state === 'unloaded'
          ? detail || `${ruleset} has not been read yet.`
          : state === 'unknown'
            ? detail || `${ruleset} could not be read from GitHub.`
            : detail
              ? `${ruleset} was not found. ${detail}`
              : `${ruleset} has not been created. Read-only creates it and enforces it immediately.`;
  return (
    <span className="inline-flex items-center gap-0.5">
      <span className={`px-1.5 py-0.5 rounded border text-[10px] font-medium ${tone}`}>{label}</span>
      <InfoTooltip title={ruleset} position="bottom" whatIsIt={whatIsIt} />
    </span>
  );
};
