import React, { useEffect } from 'react';
import { X } from 'lucide-react';

export type ProbeGuideFocus =
  | 'all'
  | 'tips'
  | 'chaos'
  | 'branch'
  | 'tag'
  | 'note'
  | 'pull_request'
  | 'release'
  | 'status'
  | 'check_run';

interface SimulationProbeGuideProps {
  onClose: () => void;
  focus?: ProbeGuideFocus;
}

const Steps: React.FC<{ items: string[] }> = ({ items }) => (
  <ol className="list-decimal pl-4 space-y-1.5">
    {items.map((item) => (
      <li key={item}>{item}</li>
    ))}
  </ol>
);

export const SimulationProbeGuide: React.FC<SimulationProbeGuideProps> = ({ onClose, focus = 'all' }) => {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  useEffect(() => {
    if (!focus || focus === 'all') return;
    const frame = window.requestAnimationFrame(() => {
      document.getElementById(`probe-guide-${focus}`)?.scrollIntoView({ block: 'start' });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [focus]);

  return (
    <div
      className="fixed inset-0 z-[70] bg-black/40 backdrop-blur-sm flex items-center justify-center p-4"
      onMouseDown={onClose}
    >
      <div
        role="dialog"
        aria-labelledby="probe-guide-title"
        className="bg-white border border-zinc-200 rounded-2xl w-full max-w-2xl shadow-2xl flex flex-col max-h-[88vh]"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="px-6 py-4 border-b border-zinc-100 flex items-start justify-between gap-4 shrink-0">
          <div>
            <h3 id="probe-guide-title" className="text-sm font-semibold text-zinc-900">Simulation field guide</h3>
            <p className="text-xs text-zinc-500 mt-1">
              Chaos controls, then every probe: branch, tag, note, pull request, release, commit status, and check run.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="p-1.5 text-zinc-400 hover:text-zinc-700 rounded-lg hover:bg-zinc-100"
            aria-label="Close field guide"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="px-6 py-5 overflow-y-auto text-xs text-zinc-700 leading-relaxed space-y-6">
          <section className="space-y-2">
            <h4 className="text-[13px] font-semibold text-zinc-900">Shared probe fields</h4>
            <p>
              Send delivers one GitHub-style event for the pair you picked. The event is real. The mirror applies it on the other repository.
            </p>
            <p>
              <span className="font-medium text-zinc-900">Pair</span> chooses the two repositories. <span className="font-medium text-zinc-900">Arrive on</span> chooses which one the event came from. Source is the first repository. Destination is the mirror. Pick the side where you made the change. The other side is the one that gets updated.
            </p>
            <p>
              <span className="font-medium text-zinc-900">Case</span> is the kind of event. <span className="font-medium text-zinc-900">Operation</span> is what happened. The help icon on Case opens this guide on the case you have selected. LFS has no GitHub webhook, so it is not a case. A push that adds an LFS file is still a branch push.
            </p>
          </section>

          <section id="probe-guide-tips" className="space-y-3 scroll-mt-4">
            <h4 className="text-[13px] font-semibold text-zinc-900">Tips</h4>
            <p>
              A tip is the commit a branch, tag, or note points at right now. It is a 40-character SHA. On GitHub the branch page shows a short hash. Open that commit and copy the full id. Branch, tag, and note pushes all use these two fields.
            </p>
            <div className="rounded-xl border border-zinc-200 overflow-hidden">
              <table className="w-full text-left">
                <thead className="bg-zinc-50 text-[10px] uppercase tracking-wider text-zinc-500">
                  <tr>
                    <th className="px-3 py-2 font-medium">Field</th>
                    <th className="px-3 py-2 font-medium">Meaning</th>
                    <th className="px-3 py-2 font-medium">What to enter</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-zinc-100">
                  <tr>
                    <td className="px-3 py-2 font-medium text-zinc-900 align-top">Previous tip</td>
                    <td className="px-3 py-2 align-top">Where the ref pointed before this push.</td>
                    <td className="px-3 py-2 align-top">Leave the value the form fills in. Type over it only for the advanced case below.</td>
                  </tr>
                  <tr>
                    <td className="px-3 py-2 font-medium text-zinc-900 align-top">New tip SHA</td>
                    <td className="px-3 py-2 align-top">Where that same ref points after this push.</td>
                    <td className="px-3 py-2 align-top">A commit that already exists on the side you are sending from. The random value is only a placeholder.</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p>
              Example. <span className="font-mono text-[11px]">main</span> points at commit A. You add commit B on top of A. Previous tip is A. New tip is B. The mirror moves the ref from A to B on the other side.
            </p>
            <p>
              When you choose the pair, the side, and the name, the form asks that repository what the ref points at.
            </p>
            <ul className="list-disc pl-4 space-y-1.5">
              <li><span className="font-medium text-zinc-900">Filled from the current tip.</span> The ref exists. Leave that SHA for a normal push.</li>
              <li><span className="font-medium text-zinc-900">This ref is not on that side yet.</span> Previous tip becomes forty zeros. Zeros mean the ref is new.</li>
              <li><span className="font-medium text-zinc-900">Could not read the current tip.</span> Type the SHA, or choose Use current tip to try again.</li>
              <li><span className="font-medium text-zinc-900">Using the SHA you entered.</span> You replaced the filled value. Use current tip puts the looked-up tip back.</li>
            </ul>
            <p>
              Replace the previous tip when you want a push that did not start from the commit the ref points at now. The mirror then sees two histories that did not grow from the same tip. On a bidirectional pair that can park the incoming commit and open a conflict pull request.
            </p>
            <p>
              New tip SHA must be a commit you already pushed to the side you are sending from. The mirror downloads that commit. A SHA that was never pushed there cannot be mirrored.
            </p>
            <p>
              On delete, <span className="font-medium text-zinc-900">Tip being removed</span> is the commit the ref pointed at when it was deleted. The form fills it from the current tip. The mirror removes that ref on the other side when the other side still has it.
            </p>
          </section>

          <section id="probe-guide-branch" className="space-y-2 scroll-mt-4">
            <h4 className="text-[13px] font-semibold text-zinc-900">Branch</h4>
            <p>
              A branch probe says a branch moved or was deleted. Type the short name, such as <span className="font-mono text-[11px]">test/sim-probe</span>, without <span className="font-mono text-[11px]">refs/</span>. Author and message are written on the event. They do not have to match a GitHub login.
            </p>
            <p><span className="font-medium text-zinc-900">Push commit.</span> Moves the branch to New tip SHA. Read Tips first.</p>
            <Steps items={[
              'Make the commit on the repository you will send from, and copy its full SHA.',
              'Set Arrive on to that side. Case Branch, operation Push commit, and type the branch name.',
              'Wait until Previous tip is filled, or stays forty zeros because the branch is new.',
              'Paste the new commit into New tip SHA, then Send.',
            ]} />
            <p><span className="font-medium text-zinc-900">Delete branch.</span> Removes that branch on the other side when the other side still has it. Tip being removed is filled from the current tip. Protected trunk branches are left in place.</p>
          </section>

          <section id="probe-guide-tag" className="space-y-2 scroll-mt-4">
            <h4 className="text-[13px] font-semibold text-zinc-900">Tag</h4>
            <p>
              A tag probe moves or deletes a git tag. The name is the tag, such as <span className="font-mono text-[11px]">sim-release-v1</span>. Tips work the same way as a branch: previous tip is where the tag pointed, new tip is where it points after the push.
            </p>
            <p><span className="font-medium text-zinc-900">Push tag.</span> Create the tag on the side you are sending from, copy the SHA it points at into New tip SHA, and leave Previous tip as filled. Forty zeros means the tag is new. If the same tag name already points at a different commit on the other side, the mirror skips the move.</p>
            <p><span className="font-medium text-zinc-900">Delete tag.</span> Removes that git tag on the other side when the other side still has it. This does not delete a GitHub release. Use the Release case for the release, and this case for the tag.</p>
          </section>

          <section id="probe-guide-note" className="space-y-2 scroll-mt-4">
            <h4 className="text-[13px] font-semibold text-zinc-900">Note</h4>
            <p>
              A note is a git note stored under <span className="font-mono text-[11px]">refs/notes/</span>. GitHub’s website does not list notes. The form still reads the current tip when you type the note name, such as <span className="font-mono text-[11px]">sim-note</span>.
            </p>
            <p><span className="font-medium text-zinc-900">Push note.</span> The note must already exist on the side you send from. Previous tip and New tip SHA follow the Tips section. The mirror copies that note ref to the other side.</p>
            <p><span className="font-medium text-zinc-900">Delete note.</span> Removes that note on the other side when the other side still has it. Tip being removed is the note’s current tip.</p>
          </section>

          <section id="probe-guide-pull_request" className="space-y-2 scroll-mt-4">
            <h4 className="text-[13px] font-semibold text-zinc-900">Pull request</h4>
            <p>
              Sends an open, edit, close, or merge for one pull request. The head branch must already exist on the side you send from. Push that branch first when it is new.
            </p>
            <ul className="list-disc pl-4 space-y-1.5">
              <li><span className="font-medium text-zinc-900">Pull request number.</span> The number on the side you are sending from. It is in the GitHub URL. Open can use a new number. Edit, close, and merge need a request that already exists on that side, so the mirror can find the copy on the other side.</li>
              <li><span className="font-medium text-zinc-900">Head SHA.</span> The tip of the head branch. Copy it from the pull request’s Commits tab. Merge uses this commit.</li>
              <li><span className="font-medium text-zinc-900">Head branch.</span> The branch that holds the pull request commits, such as <span className="font-mono text-[11px]">test/sim-probe</span>.</li>
              <li><span className="font-medium text-zinc-900">Base branch.</span> The branch it merges into, usually <span className="font-mono text-[11px]">main</span>.</li>
              <li><span className="font-medium text-zinc-900">Title and body.</span> Copied onto the pull request on the other side for open and edit.</li>
            </ul>
            <p><span className="font-medium text-zinc-900">Open</span> creates the matching request on the other side. <span className="font-medium text-zinc-900">Edit</span> updates the title and body of the request that is already mapped. <span className="font-medium text-zinc-900">Close</span> closes it on the other side. <span className="font-medium text-zinc-900">Merge</span> closes it as merged and records the head SHA as the merge commit.</p>
          </section>

          <section id="probe-guide-release" className="space-y-2 scroll-mt-4">
            <h4 className="text-[13px] font-semibold text-zinc-900">Release</h4>
            <p>
              A release is the GitHub release attached to a git tag. The tag itself is the Tag case. Use a tag that already exists on the side you send from, such as <span className="font-mono text-[11px]">meta-sync-v1</span>. Copy the tag name from the release page.
            </p>
            <p>
              <span className="font-medium text-zinc-900">Name</span> is the release title. <span className="font-medium text-zinc-900">Body</span> is the release notes. Publish and unpublish copy both to the other side. Delete matches the release by tag.
            </p>
            <p><span className="font-medium text-zinc-900">Publish</span> creates or updates the release for that tag on the other side. One tag keeps one release. <span className="font-medium text-zinc-900">Unpublish</span> turns that release into a draft on the other side when a published release is still there. <span className="font-medium text-zinc-900">Delete</span> removes the release on the other side. If the side you sent from still has a release for that tag, the peer release stays.</p>
          </section>

          <section id="probe-guide-status" className="space-y-2 scroll-mt-4">
            <h4 className="text-[13px] font-semibold text-zinc-900">Commit status</h4>
            <p>
              A commit status is the success, failure, or pending mark on one commit, such as <span className="font-mono text-[11px]">simulation/status</span>.
            </p>
            <ul className="list-disc pl-4 space-y-1.5">
              <li><span className="font-medium text-zinc-900">Commit SHA.</span> The commit the status belongs to. That commit must already exist on the other side, usually because the branch push was mirrored first. Copy the full SHA from the commit page on the side you are sending from.</li>
              <li><span className="font-medium text-zinc-900">Context.</span> The status label. The same context on the same commit is one status. Sending the same context and result again does not write a second copy.</li>
              <li><span className="font-medium text-zinc-900">Summary.</span> The short description stored with the status.</li>
            </ul>
            <p>Success, failure, and pending are the three results you can send. Pick the operation, then Send.</p>
          </section>

          <section id="probe-guide-check_run" className="space-y-2 scroll-mt-4">
            <h4 className="text-[13px] font-semibold text-zinc-900">Check run</h4>
            <p>
              A check run is a completed check on one commit, such as a workflow job named <span className="font-mono text-[11px]">simulation/ping</span>. GitHub has no delete event for a check run. Success and failure are the operations you can send.
            </p>
            <ul className="list-disc pl-4 space-y-1.5">
              <li><span className="font-medium text-zinc-900">Commit SHA.</span> The commit the check belongs to. It must already be on the other side. Copy the full SHA from that commit.</li>
              <li><span className="font-medium text-zinc-900">Check name.</span> The check run name. The same name and result on the other side is treated as already mirrored.</li>
              <li><span className="font-medium text-zinc-900">Summary.</span> The text stored with the check.</li>
            </ul>
          </section>

          <section id="probe-guide-chaos" className="space-y-2 scroll-mt-4">
            <h4 className="text-[13px] font-semibold text-zinc-900">Chaos controls</h4>
            <p>
              These switches sit beside the probes. They change how this hub behaves while a probe or a real webhook is processed. Turn each one off again when the test is done.
            </p>
            <ul className="list-disc pl-4 space-y-1.5">
              <li><span className="font-medium text-zinc-900">Pause Queue Consumer.</span> Webhooks are accepted and wait. Nothing is mirrored until you set the consumer back to Active.</li>
              <li><span className="font-medium text-zinc-900">Simulate Target Repo Outage.</span> Pushes to the destination fail, so you can watch retry and the dead-letter path.</li>
              <li><span className="font-medium text-zinc-900">Simulate GitHub 429 Rate Limit.</span> API calls are treated as throttled, so you can watch the backoff.</li>
              <li><span className="font-medium text-zinc-900">Artificial Network Latency.</span> Adds the selected delay, up to 5000 ms, on the work this hub does for the event.</li>
            </ul>
          </section>
        </div>
      </div>
    </div>
  );
};
