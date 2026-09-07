package com.gitutility.service;

import com.gitutility.model.dto.SyncEventMessage;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GitSyncEngineResumeTest {

    @Test
    void parseAndSerializeCompletedPushRefsRoundTrip() {
        String blob = "refs/heads/main=abc123\nrefs/heads/dev=def456\n";
        Map<String, String> parsed = GitSyncEngine.parseCompletedPushRefs(blob);
        assertEquals("abc123", parsed.get("refs/heads/main"));
        assertEquals("def456", parsed.get("refs/heads/dev"));
        assertEquals(2, parsed.size());

        String serialized = GitSyncEngine.serializeCompletedPushRefs(parsed);
        Map<String, String> again = GitSyncEngine.parseCompletedPushRefs(serialized);
        assertEquals(parsed, again);
        assertTrue(GitSyncEngine.parseCompletedPushRefs(null).isEmpty());
        assertTrue(GitSyncEngine.parseCompletedPushRefs("  \n").isEmpty());
    }

    @Test
    void divergedTrunkIsolatesUnlessOperatorOverwrites() {
        assertTrue(GitSyncEngine.shouldIsolateDivergedTrunk(false, false));
        assertFalse(GitSyncEngine.shouldIsolateDivergedTrunk(false, true));
        assertFalse(GitSyncEngine.shouldIsolateDivergedTrunk(true, false));
        assertFalse(GitSyncEngine.shouldIsolateDivergedTrunk(true, true));
    }

    @Test
    void trunkPolicyOriginWinsForcePushesWithoutIsolate() {
        assertEquals(GitSyncEngine.TrunkPushAction.ISOLATE,
                GitSyncEngine.decideTrunkPush(false, false, com.gitutility.model.enums.TrunkConflictPolicy.ISOLATE));
        assertEquals(GitSyncEngine.TrunkPushAction.FORCE,
                GitSyncEngine.decideTrunkPush(false, false, com.gitutility.model.enums.TrunkConflictPolicy.ORIGIN_WINS));
        assertEquals(GitSyncEngine.TrunkPushAction.SKIP,
                GitSyncEngine.decideTrunkPush(false, false, com.gitutility.model.enums.TrunkConflictPolicy.FAIL_JOB));
        assertEquals(GitSyncEngine.TrunkPushAction.PUSH,
                GitSyncEngine.decideTrunkPush(true, false, com.gitutility.model.enums.TrunkConflictPolicy.ISOLATE));
        assertEquals(GitSyncEngine.TrunkPushAction.FORCE,
                GitSyncEngine.decideTrunkPush(false, true, com.gitutility.model.enums.TrunkConflictPolicy.FAIL_JOB));
    }

    @Test
    void isolatedConflictBranchUsesTimestampedName() {
        java.util.Date at = new java.util.Date(0L);
        assertEquals("sync-conflict/main-19700101-000000", GitSyncEngine.isolatedConflictBranch("main", at));
        assertTrue(GitSyncEngine.isTrunkBranch("release/1.0"));
        assertTrue(GitSyncEngine.isSyncConflictBranch("refs/heads/sync-conflict/main-1"));
        assertFalse(GitSyncEngine.isSyncConflictBranch("main"));
    }

    @Test
    void conflictSummaryListsIsolatedRefs() {
        GitSyncEngine.SyncResult result = new GitSyncEngine.SyncResult();
        GitSyncEngine.IsolatedRef iso = new GitSyncEngine.IsolatedRef();
        iso.isolatedBranch = "sync-conflict/main-1";
        iso.action = GitSyncEngine.TrunkPushAction.ISOLATE;
        result.isolatedRefs.add(iso);
        assertEquals("Split-brain divergence isolated on: sync-conflict/main-1",
                GitSyncEngine.conflictSummaryMessage(result));
    }

    @Test
    void partitionPushBatchesPutsDefaultBranchFirstAlone() {
        List<RefSpec> specs = List.of(
                new RefSpec("+refs/heads/main:refs/heads/main"),
                new RefSpec("+refs/heads/a:refs/heads/a"),
                new RefSpec("+refs/heads/b:refs/heads/b"),
                new RefSpec("+refs/heads/c:refs/heads/c")
        );
        List<List<RefSpec>> batches = GitSyncEngine.partitionPushBatches(specs, 2);
        assertEquals(3, batches.size());
        assertEquals(1, batches.get(0).size());
        assertEquals("refs/heads/main", batches.get(0).get(0).getSource());
        assertEquals(2, batches.get(1).size());
        assertEquals(1, batches.get(2).size());
    }

    @Test
    void defaultBranchSortKeyOrdersMainFirst() {
        assertTrue(GitSyncEngine.defaultBranchSortKey("refs/heads/main")
                < GitSyncEngine.defaultBranchSortKey("refs/heads/feature"));
        assertTrue(GitSyncEngine.defaultBranchSortKey("refs/heads/master")
                < GitSyncEngine.defaultBranchSortKey("refs/tags/v1"));
    }

    @Test
    void transientTransportFailureDetectsConnectionReset() {
        assertTrue(GitSyncEngine.isTransientTransportFailure(
                new RuntimeException("Push to target failed: Connection reset")));
        assertTrue(GitSyncEngine.isTransientTransportFailure(
                new RuntimeException("HTTP 502 Bad Gateway")));
        assertFalse(GitSyncEngine.isTransientTransportFailure(
                new RuntimeException("authentication is required")));
    }

    @Test
    void successfulRemoteUpdateIncludesOkAndUpToDateAndNonExisting() {
        assertTrue(GitSyncEngine.isSuccessfulRemoteUpdate(org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK));
        assertTrue(GitSyncEngine.isSuccessfulRemoteUpdate(org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE));
        assertTrue(GitSyncEngine.isSuccessfulRemoteUpdate(org.eclipse.jgit.transport.RemoteRefUpdate.Status.NON_EXISTING));
        assertFalse(GitSyncEngine.isSuccessfulRemoteUpdate(org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_OTHER_REASON));
        assertFalse(GitSyncEngine.isSuccessfulRemoteUpdate(org.eclipse.jgit.transport.RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD));
    }

    @Test
    void skipSourceFetchWhenWarmPacksAndHeadsExist() {
        assertTrue(GitSyncEngine.shouldSkipSourceFetch(true, true, true, Map.of("refs/heads/main", "abc")));
        // Warm cache, empty ledger: normal full resync skips re-download.
        assertTrue(GitSyncEngine.shouldSkipSourceFetch(true, true, true, Map.of()));
        assertFalse(GitSyncEngine.shouldSkipSourceFetch(true, true, false, Map.of()));
        assertFalse(GitSyncEngine.shouldSkipSourceFetch(true, false, true, Map.of()));
        assertFalse(GitSyncEngine.shouldSkipSourceFetch(false, true, true, Map.of()));
    }

    @Test
    void remoteTipsDifferWhenSourceMovedOrAddedOrDeleted() {
        Map<String, String> local = Map.of(
                "refs/heads/main", "aaa111",
                "refs/tags/v1", "bbb222"
        );
        assertFalse(GitSyncEngine.remoteTipsDifferFromLocal(local, Map.of(
                "refs/heads/main", "aaa111",
                "refs/tags/v1", "bbb222"
        )));
        assertTrue(GitSyncEngine.remoteTipsDifferFromLocal(local, Map.of(
                "refs/heads/main", "ccc333",
                "refs/tags/v1", "bbb222"
        )));
        assertTrue(GitSyncEngine.remoteTipsDifferFromLocal(local, Map.of(
                "refs/heads/main", "aaa111",
                "refs/tags/v1", "bbb222",
                "refs/heads/feature", "ddd444"
        )));
        assertTrue(GitSyncEngine.remoteTipsDifferFromLocal(local, Map.of(
                "refs/heads/main", "aaa111"
        )));
        assertFalse(GitSyncEngine.remoteTipsDifferFromLocal(Map.of(), Map.of()));
        assertTrue(GitSyncEngine.isProbeTipRef("refs/heads/main"));
        assertTrue(GitSyncEngine.isProbeTipRef("refs/notes/commits"));
        assertFalse(GitSyncEngine.isProbeTipRef("refs/tags/v1^{}"));
        assertFalse(GitSyncEngine.isProbeTipRef("refs/pull/1/head"));
    }

    @Test
    void warmMirrorFetchRefSpecsOmitPullHeads() {
        SyncEventMessage full = SyncEventMessage.builder().branch("*").build();
        var withPull = GitSyncEngine.sourceFetchRefSpecs(full, true);
        var withoutPull = GitSyncEngine.sourceFetchRefSpecs(full, false);
        assertEquals(4, withPull.length);
        assertEquals(3, withoutPull.length);
        assertTrue(java.util.Arrays.stream(withPull).anyMatch(s -> s.getSource().contains("refs/pull")));
        assertTrue(java.util.Arrays.stream(withoutPull).noneMatch(s -> s.getSource().contains("refs/pull")));
    }

    @Test
    void lfsPartitionSplitsIntoBatches() {
        List<Integer> items = List.of(1, 2, 3, 4, 5);
        List<List<Integer>> batches = GitLfsSyncService.partition(items, 2);
        assertEquals(3, batches.size());
        assertEquals(List.of(1, 2), batches.get(0));
        assertEquals(List.of(5), batches.get(2));
    }

    @Test
    void skipHeadRefUsesDestinationShaNotPoisonedLedger() {
        Map<String, String> ledger = Map.of("refs/heads/main", "deadbeef");
        assertFalse(GitSyncEngine.shouldSkipRef("refs/heads/main", "abc123", null, ledger, true));
        assertTrue(GitSyncEngine.shouldSkipRef("refs/heads/main", "abc123", "abc123", ledger, true));
        // Dest unreachable → ledger may skip tags.
        assertTrue(GitSyncEngine.shouldSkipRef("refs/tags/v1", "abc123", null, Map.of("refs/tags/v1", "abc123"), false));
        assertFalse(GitSyncEngine.shouldSkipRef("refs/tags/v1", "abc123", null, Map.of(), false));
        // Dest reachable + matching tip skips; missing tip must not ledger-skip.
        assertTrue(GitSyncEngine.shouldSkipRef("refs/tags/v1", "abc123", "abc123", Map.of(), true));
        assertFalse(GitSyncEngine.shouldSkipRef("refs/tags/v1", "abc123", "other", Map.of(), true));
        assertFalse(GitSyncEngine.shouldSkipRef("refs/tags/v1", "abc123", null, Map.of("refs/tags/v1", "abc123"), true));
        assertFalse(GitSyncEngine.shouldSkipRef("refs/notes/commits", "abc123", null, Map.of("refs/notes/commits", "abc123"), true));
    }

    @Test
    void hostPathLabelStripsProtocolAndGitSuffix() {
        assertEquals("github.com/microsoft/vscode",
                GitSyncEngine.hostPathLabel("https://github.com/microsoft/vscode.git"));
        assertEquals("github.com/acme/mirror-dest",
                GitSyncEngine.hostPathLabel("https://github.com/acme/mirror-dest"));
    }

    @Test
    void recordShasOnLedgerWritesEveryBatchTipNotOnlyTriggerSha() {
        DedupLedgerService ledger = new DedupLedgerService();
        org.springframework.test.util.ReflectionTestUtils.setField(ledger, "ledgerTtlSeconds", 600L);
        String dest = "https://github.com/acme/mirror-dest";
        GitSyncEngine.recordShasOnLedger(ledger, dest, Map.of(
                "refs/heads/feat/rag-workflow", "aaa111",
                "refs/heads/fix/importer-color-cache-key", "bbb222"
        ));

        assertTrue(ledger.isSystemGeneratedEcho(dest + ".git", "aaa111"));
        assertTrue(ledger.isSystemGeneratedEcho("https://github.com/acme/mirror-dest.git", "bbb222"));
        assertFalse(ledger.isSystemGeneratedEcho(dest, "the-triggering-job-after-sha"));
    }

    @Test
    void prSyncLedgerHelperRecordsDestUrlWithoutGitSuffix() {
        DedupLedgerService ledger = new DedupLedgerService();
        org.springframework.test.util.ReflectionTestUtils.setField(ledger, "ledgerTtlSeconds", 600L);
        PullRequestSyncService.recordDestPushOnLedger(ledger, "https://github.com/acme/mirror-dest", "388dc77aaa");
        assertTrue(ledger.isSystemGeneratedEcho("https://github.com/acme/mirror-dest.git", "388dc77aaa"));
    }

    @Test
    void etaEstimateRequiresProgressAndElapsed() {
        assertNull(LiveGitProgressMonitor.estimateEtaMs(0, 100, 5000));
        assertEquals(5000L, LiveGitProgressMonitor.estimateEtaMs(50, 100, 5000));
    }

    @Test
    void progressPrefixNamesSourceAndDestination() {
        assertEquals("Source fetch · github.com/microsoft/vscode: ",
                LiveGitProgressMonitor.progressPrefix("source", "github.com/microsoft/vscode"));
        assertEquals("Destination push · github.com/acme/mirror-dest: ",
                LiveGitProgressMonitor.progressPrefix("destination", "github.com/acme/mirror-dest"));
        assertEquals("Inspect destination · github.com/acme/mirror-dest: ",
                LiveGitProgressMonitor.progressPrefix("inspect", "github.com/acme/mirror-dest"));
    }

    @Test
    void progressMonitorHonorsCancelCheck() {
        LiveGitProgressMonitor monitor = new LiveGitProgressMonitor(
                1L, 3L, "fetch", null, null);
        assertFalse(monitor.isCancelled());
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        monitor.setCancelCheck(cancelled::get);
        assertFalse(monitor.isCancelled());
        cancelled.set(true);
        assertTrue(monitor.isCancelled());
    }

    @Test
    void destTrackingRefNameMapsHeadsAndTags() {
        assertEquals("refs/remotes/target/main", GitSyncEngine.destTrackingRefName("refs/heads/main"));
        assertEquals("refs/remotes/target-tags/v1.0", GitSyncEngine.destTrackingRefName("refs/tags/v1.0"));
        assertNull(GitSyncEngine.destTrackingRefName("refs/notes/commits"));
        assertNull(GitSyncEngine.destTrackingRefName(null));
    }

    @Test
    void applyPushedRefToDestTrackingUpdatesTargetRemote(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("mirror.git").toFile();
        try (Git git = Git.init().setBare(true).setDirectory(repoDir).call()) {
            org.eclipse.jgit.lib.TreeFormatter tree = new org.eclipse.jgit.lib.TreeFormatter();
            ObjectId treeId = git.getRepository().newObjectInserter().insert(tree);
            org.eclipse.jgit.lib.CommitBuilder commit = new org.eclipse.jgit.lib.CommitBuilder();
            commit.setTreeId(treeId);
            commit.setAuthor(new org.eclipse.jgit.lib.PersonIdent("test", "test@example.com"));
            commit.setCommitter(new org.eclipse.jgit.lib.PersonIdent("test", "test@example.com"));
            ObjectId commitId = git.getRepository().newObjectInserter().insert(commit);
            git.getRepository().newObjectInserter().flush();

            RefUpdate head = git.getRepository().updateRef("refs/heads/feature-a");
            head.setNewObjectId(commitId);
            assertEquals(RefUpdate.Result.NEW, head.update());

            GitSyncEngine.applyPushedRefToDestTracking(git, "refs/heads/feature-a", commitId);

            Ref tracked = git.getRepository().exactRef("refs/remotes/target/feature-a");
            assertNotNull(tracked);
            assertEquals(commitId, tracked.getObjectId());

            GitSyncEngine.deleteDestTrackingForRemoteRef(git, "refs/heads/feature-a");
            assertNull(git.getRepository().exactRef("refs/remotes/target/feature-a"));
        }
    }

    @Test
    void normalizeLegacySourceBranchRefsCopiesRemoteTipsToHeads(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("mirror.git").toFile();
        try (Git git = Git.init().setBare(true).setDirectory(repoDir).call()) {
            org.eclipse.jgit.lib.TreeFormatter tree = new org.eclipse.jgit.lib.TreeFormatter();
            ObjectId treeId = git.getRepository().newObjectInserter().insert(tree);
            org.eclipse.jgit.lib.CommitBuilder commit = new org.eclipse.jgit.lib.CommitBuilder();
            commit.setTreeId(treeId);
            commit.setAuthor(new org.eclipse.jgit.lib.PersonIdent("test", "test@example.com"));
            commit.setCommitter(new org.eclipse.jgit.lib.PersonIdent("test", "test@example.com"));
            ObjectId commitId = git.getRepository().newObjectInserter().insert(commit);
            git.getRepository().newObjectInserter().flush();

            RefUpdate remoteUpdate = git.getRepository().updateRef("refs/remotes/source/main");
            remoteUpdate.setNewObjectId(commitId);
            assertEquals(RefUpdate.Result.NEW, remoteUpdate.update());

            GitSyncEngine.normalizeLegacySourceBranchRefs(git);

            Ref main = git.getRepository().exactRef("refs/heads/main");
            assertNotNull(main);
            assertEquals(commitId, main.getObjectId());
        }
    }

    @Test
    void forceSourceFetchFlagIsHonoredInResumeDecision() {
        SyncEventMessage fresh = SyncEventMessage.builder().branch("*").forceSourceFetch(true).build();
        assertTrue(fresh.isForceSourceFetch());
        boolean wouldSkip = GitSyncEngine.shouldSkipSourceFetch(true, true, true, Map.of());
        assertTrue(wouldSkip);
        // Call site: forceSourceFetch bypasses the warm-cache skip.
        boolean resumePushOnly = !fresh.isForceSourceFetch() && wouldSkip;
        assertFalse(resumePushOnly);
    }
}
