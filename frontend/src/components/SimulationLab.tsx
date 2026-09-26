import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Send, CheckCircle2, HelpCircle } from 'lucide-react';
import { RepoMapping, QueueStatus } from '../types';
import { fetchSimulationRefTip, SimulationScenarioRequest, SimulationScenarioResult } from '../services/api';
import { InfoTooltip } from './InfoTooltip';
import { ProbeGuideFocus, SimulationProbeGuide } from './SimulationProbeGuide';

interface SimulationLabProps {
  mappings: RepoMapping[];
  queueStatus: QueueStatus | null;
  onUpdateSimulationConfig: (config: any) => Promise<void>;
  onEmitScenario: (data: SimulationScenarioRequest) => Promise<SimulationScenarioResult>;
}

const ZERO_SHA = '0000000000000000000000000000000000000000';

const randomSha = () => Array.from({ length: 40 }, () => Math.floor(Math.random() * 16).toString(16)).join('');

const SCENARIOS: { id: string; label: string; operations: { id: string; label: string }[] }[] = [
  { id: 'branch', label: 'Branch', operations: [
    { id: 'push', label: 'Push commit' },
    { id: 'delete', label: 'Delete branch' },
  ]},
  { id: 'tag', label: 'Tag', operations: [
    { id: 'push', label: 'Push tag' },
    { id: 'delete', label: 'Delete tag' },
  ]},
  { id: 'note', label: 'Note', operations: [
    { id: 'push', label: 'Push note' },
    { id: 'delete', label: 'Delete note' },
  ]},
  { id: 'pull_request', label: 'Pull request', operations: [
    { id: 'open', label: 'Open' },
    { id: 'edit', label: 'Edit' },
    { id: 'close', label: 'Close' },
    { id: 'merge', label: 'Merge' },
  ]},
  { id: 'release', label: 'Release', operations: [
    { id: 'publish', label: 'Publish' },
    { id: 'unpublish', label: 'Unpublish' },
    { id: 'delete', label: 'Delete' },
  ]},
  { id: 'status', label: 'Commit status', operations: [
    { id: 'success', label: 'Success' },
    { id: 'failure', label: 'Failure' },
    { id: 'pending', label: 'Pending' },
  ]},
  { id: 'check_run', label: 'Check run', operations: [
    { id: 'success', label: 'Success' },
    { id: 'failure', label: 'Failure' },
  ]},
];

const shortRepo = (url?: string) => (url || '').replace(/^https?:\/\/github.com\//, '').replace(/\.git$/, '');

const FieldHint: React.FC<{
  label: string;
  whatIsIt: string;
  howItWorks?: string;
  recommended?: string;
  badge?: string;
}> = ({ label, whatIsIt, howItWorks, recommended, badge }) => (
  <div className="flex items-center gap-1 mb-1">
    <label className="text-zinc-700 font-medium">{label}</label>
    <InfoTooltip
      title={label}
      badge={badge}
      whatIsIt={whatIsIt}
      howItWorks={howItWorks}
      recommended={recommended}
      position="bottom"
      iconSize={12}
      pinned
    />
  </div>
);

const GuideButton: React.FC<{ onClick: () => void }> = ({ onClick }) => (
  <button
    type="button"
    onClick={onClick}
    className="shrink-0 text-zinc-400 hover:text-zinc-700 p-0.5 rounded-full hover:bg-zinc-100 mb-1"
    aria-label="Open the field guide"
  >
    <HelpCircle className="w-3.5 h-3.5" />
  </button>
);

const TipFill: React.FC<{ note: string | null; onUse: () => void }> = ({ note, onUse }) => (
  <div className="flex items-start justify-between gap-3 mt-1">
    <p className="text-[10px] text-zinc-500 leading-snug">{note}</p>
    <button
      type="button"
      onClick={onUse}
      className="shrink-0 text-[10px] font-medium text-zinc-700 underline underline-offset-2 hover:text-zinc-900"
    >
      Use current tip
    </button>
  </div>
);

export const SimulationLab: React.FC<SimulationLabProps> = ({
  mappings,
  queueStatus,
  onUpdateSimulationConfig,
  onEmitScenario,
}) => {
  const [selectedMappingId, setSelectedMappingId] = useState<string>(mappings[0]?.id || '');
  const [side, setSide] = useState<'SOURCE' | 'DESTINATION'>('SOURCE');
  const [kind, setKind] = useState('branch');
  const [operation, setOperation] = useState('push');
  const [refName, setRefName] = useState('test/sim-probe');
  const [beforeSha, setBeforeSha] = useState(ZERO_SHA);
  const [commitSha, setCommitSha] = useState(randomSha);
  const [commitMessage, setCommitMessage] = useState('simulation probe');
  const [authorName, setAuthorName] = useState('simulation-lab');
  const [prNumber, setPrNumber] = useState('9001');
  const [title, setTitle] = useState('Simulation probe');
  const [body, setBody] = useState('Opened from the simulation lab');
  const [baseBranch, setBaseBranch] = useState('main');
  const [releaseTag, setReleaseTag] = useState('sim-release-v1');
  const [context, setContext] = useState('simulation/status');
  const [emitting, setEmitting] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tipNote, setTipNote] = useState<string | null>(null);
  const [tipLoading, setTipLoading] = useState(false);
  const [guideFocus, setGuideFocus] = useState<ProbeGuideFocus | null>(null);
  const tipGen = useRef(0);

  const sim = queueStatus?.simulationStatus || {
    consumerPaused: false,
    simulateTargetDown: false,
    simulateSourceDown: false,
    simulateRateLimit: false,
    artificialDelayMs: 0,
  };

  const mapping = mappings.find((item) => item.id === selectedMappingId) || mappings[0];
  const scenario = SCENARIOS.find((item) => item.id === kind) || SCENARIOS[0];
  const sideUrl = side === 'DESTINATION' ? mapping?.repoBUrl : mapping?.repoAUrl;
  const isRef = kind === 'branch' || kind === 'tag' || kind === 'note';
  const isPush = operation === 'push';
  const refKindLabel = kind === 'tag' ? 'Tag' : kind === 'note' ? 'Note' : 'Branch';
  const refPrefix = kind === 'tag' ? 'refs/tags/' : kind === 'note' ? 'refs/notes/' : 'refs/heads/';
  const tipLookup = `git ls-remote ${sideUrl || '<repo-url>'} ${refPrefix}${refName || '<name>'}`;
  const mappingId = mapping?.id;

  const loadCurrentTip = useCallback(async (gen: number) => {
    if (!isRef || !mappingId || !refName.trim()) {
      if (gen === tipGen.current) {
        setTipLoading(false);
        setTipNote(null);
      }
      return;
    }
    setTipLoading(true);
    try {
      const tip = await fetchSimulationRefTip({
        mappingId,
        side,
        refName: refName.trim(),
        kind,
      });
      if (gen !== tipGen.current) return;
      if (!tip.known) {
        setTipNote('Could not read the current tip. You can type it.');
        return;
      }
      if (tip.present && tip.sha) {
        setBeforeSha(tip.sha);
        if (!isPush) setCommitSha(tip.sha);
        setTipNote('Filled from the current tip. You can replace it.');
      } else {
        setBeforeSha(ZERO_SHA);
        setTipNote('This ref is not on that side yet, so the previous tip stays zeros.');
      }
    } catch {
      if (gen === tipGen.current) {
        setTipNote('Could not read the current tip. You can type it.');
      }
    } finally {
      if (gen === tipGen.current) setTipLoading(false);
    }
  }, [isRef, mappingId, refName, side, kind, isPush]);

  useEffect(() => {
    if (!isRef) return;
    const gen = ++tipGen.current;
    const handle = window.setTimeout(() => { void loadCurrentTip(gen); }, 400);
    return () => window.clearTimeout(handle);
  }, [isRef, loadCurrentTip]);

  const useCurrentTip = () => {
    const gen = ++tipGen.current;
    void loadCurrentTip(gen);
  };

  const keepTypedTip = (value: string, alsoNewTip: boolean) => {
    tipGen.current += 1;
    setBeforeSha(value);
    if (alsoNewTip) setCommitSha(value);
    setTipLoading(false);
    setTipNote('Using the SHA you entered.');
  };

  const handleToggle = async (key: string, currentValue: boolean) => {
    await onUpdateSimulationConfig({ [key]: !currentValue });
  };

  const handleDelayChange = async (delayMs: number) => {
    await onUpdateSimulationConfig({ artificialDelayMs: delayMs });
  };

  const selectKind = (next: string) => {
    const found = SCENARIOS.find((item) => item.id === next) || SCENARIOS[0];
    setKind(found.id);
    setOperation(found.operations[0].id);
    if (next === 'check_run') setContext('simulation/ping');
    else if (next === 'status') setContext('simulation/status');
    else if (next === 'tag') setRefName('sim-probe');
    else if (next === 'note') setRefName('sim-note');
    else if (next === 'branch') setRefName('test/sim-probe');
  };

  const handleEmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const mappingId = selectedMappingId || mappings[0]?.id;
    if (!mappingId) return;
    setEmitting(true);
    setError(null);
    try {
      const result = await onEmitScenario({
        mappingId,
        side,
        kind,
        operation,
        refName,
        beforeSha,
        commitSha,
        commitMessage,
        authorName,
        pullRequestNumber: Number(prNumber) || undefined,
        title,
        body,
        baseBranch,
        releaseTag,
        releaseName: title,
        context,
      });
      setNotice(result.summary || `${result.event} accepted on the ${result.side?.toLowerCase()}`);
      if (isPush) setCommitSha(randomSha());
    } catch (err: any) {
      setNotice(null);
      setError(err?.response?.data?.message || err?.response?.data?.error || err?.message || 'Scenario was not accepted');
    } finally {
      setEmitting(false);
    }
  };

  const fieldClass = 'w-full bg-white border border-zinc-200 rounded-lg px-3 py-1.5 text-zinc-900 text-xs focus:outline-none focus:border-zinc-400';

  return (
    <div className="space-y-6">
      {guideFocus && (
        <SimulationProbeGuide focus={guideFocus} onClose={() => setGuideFocus(null)} />
      )}
      <div>
        <h2 className="text-base font-semibold text-zinc-900">Resilience & Simulation Lab</h2>
        <p className="text-xs text-zinc-500 mt-0.5">
          Pause ingestion, inject faults, or deliver a commit and metadata probe as if it arrived on the source or the destination.
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        <div className="lg:col-span-5 rounded-2xl border border-zinc-200/90 bg-white p-6 shadow-sm space-y-4">
          <div className="border-b border-zinc-100 pb-3 flex items-center justify-between gap-3">
            <h3 className="text-sm font-semibold text-zinc-900">Chaos Fault Injection Controls</h3>
            <GuideButton onClick={() => setGuideFocus('chaos')} />
          </div>

          <div className="space-y-3 text-xs">
            <div className="p-3.5 rounded-xl bg-zinc-50/70 border border-zinc-200/80 flex items-center justify-between">
              <div>
                <span className="font-semibold text-zinc-800">Pause Queue Consumer</span>
                <p className="text-[11px] text-zinc-500 mt-0.5">
                  Webhooks will accumulate until the consumer resumes.
                </p>
              </div>
              <button
                onClick={() => handleToggle('consumerPaused', sim.consumerPaused)}
                className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${
                  sim.consumerPaused
                    ? 'bg-amber-500 text-white'
                    : 'bg-zinc-200 text-zinc-700 hover:bg-zinc-300'
                }`}
              >
                {sim.consumerPaused ? 'Paused' : 'Active'}
              </button>
            </div>

            <div className="p-3.5 rounded-xl bg-zinc-50/70 border border-zinc-200/80 flex items-center justify-between">
              <div>
                <span className="font-semibold text-zinc-800">Simulate Target Repo Outage</span>
                <p className="text-[11px] text-zinc-500 mt-0.5">
                  Causes Git pushes to fail, testing automatic retry and DLQ redirection.
                </p>
              </div>
              <button
                onClick={() => handleToggle('simulateTargetDown', sim.simulateTargetDown)}
                className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${
                  sim.simulateTargetDown
                    ? 'bg-rose-600 text-white'
                    : 'bg-zinc-200 text-zinc-700 hover:bg-zinc-300'
                }`}
              >
                {sim.simulateTargetDown ? 'Injected' : 'Off'}
              </button>
            </div>

            <div className="p-3.5 rounded-xl bg-zinc-50/70 border border-zinc-200/80 flex items-center justify-between">
              <div>
                <span className="font-semibold text-zinc-800">Simulate GitHub 429 Rate Limit</span>
                <p className="text-[11px] text-zinc-500 mt-0.5">
                  Simulates API throttling and backoff policies.
                </p>
              </div>
              <button
                onClick={() => handleToggle('simulateRateLimit', sim.simulateRateLimit)}
                className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${
                  sim.simulateRateLimit
                    ? 'bg-rose-600 text-white'
                    : 'bg-zinc-200 text-zinc-700 hover:bg-zinc-300'
                }`}
              >
                {sim.simulateRateLimit ? 'Injected' : 'Off'}
              </button>
            </div>

            <div className="p-3.5 rounded-xl bg-zinc-50/70 border border-zinc-200/80 space-y-2">
              <div className="flex items-center justify-between">
                <span className="font-semibold text-zinc-800">Artificial Network Latency</span>
                <span className="font-mono text-zinc-600">{sim.artificialDelayMs}ms</span>
              </div>
              <input
                type="range"
                min="0"
                max="5000"
                step="500"
                value={sim.artificialDelayMs}
                onChange={(e) => handleDelayChange(parseInt(e.target.value, 10))}
                className="w-full accent-zinc-900"
              />
            </div>
          </div>
        </div>

        <div className="lg:col-span-7 rounded-2xl border border-zinc-200/90 bg-white p-6 shadow-sm space-y-4">
          <div className="border-b border-zinc-100 pb-3 flex items-start justify-between gap-3">
            <div>
              <h3 className="text-sm font-semibold text-zinc-900">Commit & Metadata Probes</h3>
              <p className="text-[11px] text-zinc-500 mt-1">
                Each probe is delivered as a webhook from the side you pick. The pair mirrors it onto the other side.
              </p>
            </div>
            <button
              type="button"
              onClick={() => setGuideFocus('all')}
              className="shrink-0 inline-flex items-center gap-1 text-[11px] font-medium text-zinc-600 hover:text-zinc-900"
              aria-label="Open the probe field guide"
            >
              <HelpCircle className="w-3.5 h-3.5" />
              <span>Field guide</span>
            </button>
          </div>

          <form onSubmit={handleEmit} className="space-y-3 text-xs">
            <div>
              <FieldHint
                label="Pair"
                whatIsIt="The mirror pair this probe is delivered for."
                howItWorks="Source and destination come from this pair. The webhook is applied to the other side of the same pair."
              />
              <select
                value={mapping?.id || ''}
                onChange={(e) => setSelectedMappingId(e.target.value)}
                className={fieldClass}
              >
                {mappings.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.name}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <FieldHint
                label="Arrive on"
                whatIsIt="The repository the event pretends to come from."
                howItWorks="Source sends the probe as a change on the first repo. Destination sends it as a change on the mirror. The other side is the one that gets updated."
              />
              <div className="grid grid-cols-2 gap-2">
                {(['SOURCE', 'DESTINATION'] as const).map((value) => {
                  const url = value === 'SOURCE' ? mapping?.repoAUrl : mapping?.repoBUrl;
                  const selected = side === value;
                  return (
                    <button
                      key={value}
                      type="button"
                      onClick={() => setSide(value)}
                      className={`text-left rounded-lg border px-3 py-2 ${
                        selected ? 'border-zinc-900 bg-zinc-900 text-white' : 'border-zinc-200 bg-white text-zinc-800'
                      }`}
                    >
                      <span className="block text-[11px] font-semibold">{value === 'SOURCE' ? 'Source' : 'Destination'}</span>
                      <span className={`block font-mono text-[10px] truncate ${selected ? 'text-zinc-300' : 'text-zinc-500'}`}>
                        {shortRepo(url) || 'No repository'}
                      </span>
                    </button>
                  );
                })}
              </div>
            </div>

            <div>
              <div className="flex items-center justify-between gap-2">
              <FieldHint
                label="Case"
                whatIsIt="The kind of GitHub event to deliver: a ref, a pull request, a release, a commit status, or a check run."
                howItWorks="Each case only offers the operations that event supports. LFS is not listed because GitHub does not send an LFS webhook. The field guide has a section for the case you have selected."
              />
              <GuideButton onClick={() => setGuideFocus(kind as ProbeGuideFocus)} />
              </div>
              <select value={kind} onChange={(e) => selectKind(e.target.value)} className={fieldClass}>
                {SCENARIOS.map((item) => (
                  <option key={item.id} value={item.id}>{item.label}</option>
                ))}
              </select>
            </div>

            <div>
              <FieldHint
                label="Operation"
                whatIsIt="What happened on the side you selected."
                howItWorks="Push and publish create or update. Delete, close, merge, and unpublish remove or finish the object. The mirror applies that same operation on the other side."
              />
              <div className="flex flex-wrap gap-1.5">
                {scenario.operations.map((item) => (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => setOperation(item.id)}
                    className={`px-2.5 py-1 rounded-full border text-[11px] font-medium ${
                      operation === item.id
                        ? 'bg-zinc-900 text-white border-zinc-900'
                        : 'bg-white text-zinc-700 border-zinc-200 hover:bg-zinc-50'
                    }`}
                  >
                    {item.label}
                  </button>
                ))}
              </div>
            </div>

            {isRef && (
              <>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <FieldHint
                      label={refKindLabel}
                      whatIsIt={`The ${refKindLabel.toLowerCase()} name on the side you are sending from. Do not include refs/.`}
                      howItWorks="Push updates this name. Delete removes it on the other side when that side still has it."
                      recommended={kind === 'note' ? 'Notes are not listed on the GitHub website. The field guide explains how the tip lookup still reads them.' : undefined}
                    />
                    <input value={refName} onChange={(e) => setRefName(e.target.value)} className={`${fieldClass} font-mono`} />
                  </div>
                  <div>
                    <FieldHint
                      label="Author"
                      whatIsIt="The name written on the simulated commit."
                      howItWorks="This is the commit author in the webhook. It does not have to match a GitHub login."
                    />
                    <input value={authorName} onChange={(e) => setAuthorName(e.target.value)} className={fieldClass} />
                  </div>
                </div>
                <div>
                  <div className="flex items-center justify-between gap-2">
                  <FieldHint
                    label={isPush ? 'New tip SHA' : 'Tip being removed'}
                    whatIsIt={isPush
                      ? 'The commit this push moves the ref to. The mirror fetches this SHA from the side you send from, so it has to exist there.'
                      : 'The commit the ref pointed at when it was deleted. Used only as the before value on the delete event.'}
                    howItWorks={isPush
                      ? 'A random SHA is filled in so the form is not blank. Replace it with a real commit before you send, or the fetch on the other side cannot find it.'
                      : 'Filled from the current tip on the side you selected. Replace it if you are deleting a different SHA.'}
                    recommended={isPush
                      ? `After you commit on ${shortRepo(sideUrl) || 'the selected side'}, copy the new commit’s full SHA from the GitHub commit page.`
                      : `Or run: ${tipLookup}`}
                  />
                  <GuideButton onClick={() => setGuideFocus('tips')} />
                  </div>
                  <input
                    value={commitSha}
                    onChange={(e) => {
                      if (isPush) setCommitSha(e.target.value);
                      else keepTypedTip(e.target.value, true);
                    }}
                    className={`${fieldClass} font-mono text-[11px]`}
                  />
                  {!isPush && (
                    <TipFill note={tipLoading ? 'Reading the current tip…' : tipNote} onUse={useCurrentTip} />
                  )}
                </div>
                {isPush && (
                  <div>
                    <div className="flex items-center justify-between gap-2">
                    <FieldHint
                      label="Previous tip"
                      whatIsIt="The SHA this ref pointed at before the push. A new ref uses forty zeros. An existing ref uses the tip on the side you are sending from."
                      howItWorks="The form reads that tip for you. The mirror treats the push as a fast-forward when this matches the tip it already has and the new commit contains it. Replace it when you want to test a push that did not start from the current tip."
                      recommended="Open the field guide for the full explanation of previous tip and new tip. Use current tip fills this again."
                    />
                    <GuideButton onClick={() => setGuideFocus('tips')} />
                    </div>
                    <input
                      value={beforeSha}
                      onChange={(e) => keepTypedTip(e.target.value, false)}
                      className={`${fieldClass} font-mono text-[11px]`}
                    />
                    <TipFill note={tipLoading ? 'Reading the current tip…' : tipNote} onUse={useCurrentTip} />
                  </div>
                )}
                {isPush && (
                  <div>
                    <FieldHint
                      label="Message"
                      whatIsIt="The commit message carried on the webhook."
                      howItWorks="Shown on the sync job. The mirror still fetches the real commit from the side you send from."
                    />
                    <input value={commitMessage} onChange={(e) => setCommitMessage(e.target.value)} className={fieldClass} />
                  </div>
                )}
              </>
            )}

            {kind === 'pull_request' && (
              <>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <FieldHint
                      label="Pull request number"
                      whatIsIt="The number of the pull request on the side you are sending from."
                      howItWorks="Open can use a new number. Edit, close, and merge must use a pull request that already exists on that side, or the mirror cannot find the copy on the other side."
                      recommended={`On GitHub, open the pull request on ${shortRepo(sideUrl) || 'the selected side'}. The number is in the URL and next to the title.`}
                    />
                    <input value={prNumber} onChange={(e) => setPrNumber(e.target.value)} className={`${fieldClass} font-mono`} />
                  </div>
                  <div>
                    <FieldHint
                      label="Head SHA"
                      whatIsIt="The commit at the tip of the pull request head branch."
                      howItWorks="Merge uses this as the merge commit when you leave the merge operation selected. It has to be a commit that exists on the side you send from."
                      recommended={`Open the pull request and copy the full SHA from the Commits tab, or from the latest commit on the head branch.`}
                    />
                    <input value={commitSha} onChange={(e) => setCommitSha(e.target.value)} className={`${fieldClass} font-mono text-[11px]`} />
                  </div>
                </div>
                <div>
                  <FieldHint
                    label="Title"
                    whatIsIt="The pull request title copied onto the other side."
                    howItWorks="Open and edit send this title. Close and merge keep it so the mirror can match the same request."
                  />
                  <input value={title} onChange={(e) => setTitle(e.target.value)} className={fieldClass} />
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <FieldHint
                      label="Head branch"
                      whatIsIt="The branch that contains the pull request commits."
                      howItWorks="This branch must already exist on the side you send from. The mirror opens or updates the request against this same branch name."
                    />
                    <input value={refName} onChange={(e) => setRefName(e.target.value)} className={`${fieldClass} font-mono`} />
                  </div>
                  <div>
                    <FieldHint
                      label="Base branch"
                      whatIsIt="The branch the pull request merges into, usually main."
                      howItWorks="Open uses this as the base on the other side. Close and merge do not move it."
                    />
                    <input value={baseBranch} onChange={(e) => setBaseBranch(e.target.value)} className={`${fieldClass} font-mono`} />
                  </div>
                </div>
                <div>
                  <FieldHint
                    label="Body"
                    whatIsIt="The pull request description."
                    howItWorks="Sent with open and edit. The mirror copies this text onto the other side."
                  />
                  <input value={body} onChange={(e) => setBody(e.target.value)} className={fieldClass} />
                </div>
              </>
            )}

            {kind === 'release' && (
              <>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <FieldHint
                      label="Tag"
                      whatIsIt="The git tag this GitHub release is attached to."
                      howItWorks="Publish creates or updates the release for this tag on the other side. Unpublish turns it into a draft. Delete removes that release. The git tag itself is a separate Tag case."
                      recommended={`Use a tag that already exists on ${shortRepo(sideUrl) || 'the selected side'}. On GitHub, open Releases and copy the tag name under the release title.`}
                    />
                    <input value={releaseTag} onChange={(e) => setReleaseTag(e.target.value)} className={`${fieldClass} font-mono`} />
                  </div>
                  <div>
                    <FieldHint
                      label="Name"
                      whatIsIt="The release title people see on GitHub."
                      howItWorks="Publish and unpublish send this name. Delete matches the release by tag, not by this name."
                    />
                    <input value={title} onChange={(e) => setTitle(e.target.value)} className={fieldClass} />
                  </div>
                </div>
                <div>
                  <FieldHint
                    label="Body"
                    whatIsIt="The release notes."
                    howItWorks="Copied onto the release on the other side when you publish or unpublish."
                  />
                  <input value={body} onChange={(e) => setBody(e.target.value)} className={fieldClass} />
                </div>
              </>
            )}

            {(kind === 'status' || kind === 'check_run') && (
              <>
                <div>
                  <FieldHint
                    label="Commit SHA"
                    whatIsIt="The commit this status or check run is attached to."
                    howItWorks="The same SHA must exist on the other side, usually after that commit has already been mirrored. A random SHA is accepted as a webhook and then has nothing to attach to."
                    recommended={`On GitHub, open the commit on ${shortRepo(sideUrl) || 'the selected side'} and copy the full SHA. Or run: git ls-remote ${sideUrl || '<repo-url>'} refs/heads/main`}
                  />
                  <input value={commitSha} onChange={(e) => setCommitSha(e.target.value)} className={`${fieldClass} font-mono text-[11px]`} />
                </div>
                <div>
                  <FieldHint
                    label={kind === 'status' ? 'Context' : 'Check name'}
                    whatIsIt={kind === 'status'
                      ? 'The status label, such as ci/build. The same context on the same commit is one status.'
                      : 'The check run name, such as a workflow job. The same name and conclusion on the other side is treated as already mirrored.'}
                    howItWorks={kind === 'status'
                      ? 'Sending the same context and state again does not write a second copy.'
                      : 'GitHub has no delete event for a check run. Success and failure are the operations you can send.'}
                  />
                  <input value={context} onChange={(e) => setContext(e.target.value)} className={`${fieldClass} font-mono`} />
                </div>
                <div>
                  <FieldHint
                    label="Summary"
                    whatIsIt="The short description stored with the status or check run."
                    howItWorks="Copied onto the other side with the result you selected."
                  />
                  <input value={commitMessage} onChange={(e) => setCommitMessage(e.target.value)} className={fieldClass} />
                </div>
              </>
            )}

            {notice && (
              <div className="p-2.5 rounded-lg bg-emerald-50 border border-emerald-200 text-emerald-800 text-[11px] flex items-center space-x-1.5">
                <CheckCircle2 className="w-3.5 h-3.5 text-emerald-600 shrink-0" />
                <span>{notice} · {shortRepo(sideUrl)}</span>
              </div>
            )}
            {error && (
              <div className="p-2.5 rounded-lg bg-rose-50 border border-rose-200 text-rose-800 text-[11px]">
                {error}
              </div>
            )}

            <div className="pt-2">
              <button
                type="submit"
                disabled={emitting || mappings.length === 0}
                className="w-full flex items-center justify-center space-x-1.5 bg-zinc-900 hover:bg-zinc-800 text-white py-2 rounded-lg font-medium text-xs transition-colors shadow-sm disabled:opacity-40"
              >
                <Send className="w-3.5 h-3.5" />
                <span>{emitting ? 'Delivering...' : `Send to ${side === 'SOURCE' ? 'source' : 'destination'}`}</span>
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
};
