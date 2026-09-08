package com.gitutility.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PullRequestSyncServiceHeadFastPathTest {

    @Test
    void forkReplicaHeadUsesSyntheticBranchName() {
        assertEquals("fork-pr-99", PullRequestSyncService.replicaHeadBranch(99, "contributor/feature", true));
        assertEquals("feature-x", PullRequestSyncService.replicaHeadBranch(12, "feature-x", false));
        assertEquals("fork-pr-65", PullRequestSyncService.replicaHeadBranch(65, "main", true));
    }

    @Test
    void destHeadsNeedingPrefetchSkipsTipsAlreadyOnGitLaneTracking(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("mirror.git").toFile();
        try (Git git = Git.init().setBare(true).setDirectory(repoDir).call()) {
            ObjectId commitId = insertEmptyCommit(git);

            GitSyncEngine.applyPushedRefToDestTracking(git, "refs/heads/feature-a", commitId);
            GitSyncEngine.applyPushedRefToDestTracking(git, "refs/heads/feature-b", commitId);

            List<String> missing = PullRequestSyncService.destHeadsNeedingPrefetch(
                    git, List.of("feature-a", "feature-b", "main"));
            assertTrue(missing.isEmpty(), "git-lane dest tracking should skip dest prefetch");
        }
    }

    @Test
    void destHeadsNeedingPrefetchReturnsOnlyMissingTips(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("mirror.git").toFile();
        try (Git git = Git.init().setBare(true).setDirectory(repoDir).call()) {
            ObjectId commitId = insertEmptyCommit(git);
            GitSyncEngine.applyPushedRefToDestTracking(git, "refs/heads/feature-a", commitId);

            List<String> missing = PullRequestSyncService.destHeadsNeedingPrefetch(
                    git, List.of("feature-a", "feature-b", "feature-c", "feature-a"));
            assertEquals(List.of("feature-b", "feature-c"), missing);
        }
    }

    @Test
    void destHeadAlreadyMaterializedTrustsTargetRemoteOnly(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("mirror.git").toFile();
        try (Git git = Git.init().setBare(true).setDirectory(repoDir).call()) {
            ObjectId commitId = insertEmptyCommit(git);
            org.eclipse.jgit.lib.RefUpdate head = git.getRepository().updateRef("refs/heads/feature-a");
            head.setNewObjectId(commitId);
            head.update();

            assertFalse(PullRequestSyncService.destHeadAlreadyMaterialized(git, "target", "feature-a"));

            GitSyncEngine.applyPushedRefToDestTracking(git, "refs/heads/feature-a", commitId);
            assertTrue(PullRequestSyncService.destHeadAlreadyMaterialized(git, "target", "feature-a"));
            Ref tracked = git.getRepository().exactRef("refs/remotes/target/feature-a");
            assertNotNull(tracked);
            assertEquals(commitId, tracked.getObjectId());
        }
    }

    @Test
    void destBranchesNeedingPrefetchIncludesTrunkBases(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("mirror.git").toFile();
        try (Git git = Git.init().setBare(true).setDirectory(repoDir).call()) {
            ObjectId commitId = insertEmptyCommit(git);
            GitSyncEngine.applyPushedRefToDestTracking(git, "refs/heads/feature-a", commitId);

            // main is a trunk: head prefetch skips it; base prefetch must still list it when cold.
            assertEquals(List.of("feature/old-base"),
                    PullRequestSyncService.destHeadsNeedingPrefetch(git, List.of("main", "feature/old-base")));
            assertEquals(List.of("main", "feature/old-base"),
                    PullRequestSyncService.destBranchesNeedingPrefetch(git, List.of("main", "feature/old-base")));

            GitSyncEngine.applyPushedRefToDestTracking(git, "refs/heads/main", commitId);
            assertEquals(List.of("feature/old-base"),
                    PullRequestSyncService.destBranchesNeedingPrefetch(
                            git, List.of("main", "feature/old-base", "main")));
        }
    }

    @Test
    void shouldFlushPrPrepKeepsForkOnlyPagesQueuedUntilBatchOrEnd() {
        assertFalse(PullRequestSyncService.shouldFlushPrPrep(0, false, false, 32));
        assertFalse(PullRequestSyncService.shouldFlushPrPrep(5, false, false, 32));
        assertTrue(PullRequestSyncService.shouldFlushPrPrep(32, false, false, 32));
        assertTrue(PullRequestSyncService.shouldFlushPrPrep(1, true, false, 32));
        assertTrue(PullRequestSyncService.shouldFlushPrPrep(1, false, true, 32));
    }

    @Test
    void pullRefsMissingLocallySkipsRefsAlreadyOnDisk(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("mirror.git").toFile();
        try (Git git = Git.init().setBare(true).setDirectory(repoDir).call()) {
            ObjectId commitId = insertEmptyCommit(git);
            org.eclipse.jgit.lib.RefUpdate pull = git.getRepository().updateRef("refs/pull/12/head");
            pull.setNewObjectId(commitId);
            pull.update();

            assertEquals(List.of(13L, 14L),
                    PullRequestSyncService.pullRefsMissingLocally(git, List.of(12L, 13L, 14L)));
        }
    }

    @Test
    void forkFetchBudgetSkipsAfterTwoAllMissBatches() {
        PullRequestSyncService.PrForkFetchBudget budget = new PullRequestSyncService.PrForkFetchBudget();
        budget.recordCacheResult(0, 3, true);
        assertFalse(budget.skipFetches);
        budget.recordCacheResult(0, 2, true);
        assertTrue(budget.skipFetches);
        budget.recordCacheResult(4, 0, true);
        assertTrue(budget.skipFetches, "already tripped; later success must not reopen mid-job");
    }

    @Test
    void forkFetchBudgetResetsOnSuccessfulCacheBeforeTrip() {
        PullRequestSyncService.PrForkFetchBudget budget = new PullRequestSyncService.PrForkFetchBudget();
        budget.recordCacheResult(0, 3, true);
        budget.recordCacheResult(5, 0, true);
        assertFalse(budget.skipFetches);
        budget.recordCacheResult(0, 1, true);
        assertFalse(budget.skipFetches);
    }

    private static ObjectId insertEmptyCommit(Git git) throws Exception {
        org.eclipse.jgit.lib.TreeFormatter tree = new org.eclipse.jgit.lib.TreeFormatter();
        ObjectId treeId = git.getRepository().newObjectInserter().insert(tree);
        org.eclipse.jgit.lib.CommitBuilder commit = new org.eclipse.jgit.lib.CommitBuilder();
        commit.setTreeId(treeId);
        commit.setAuthor(new org.eclipse.jgit.lib.PersonIdent("test", "test@example.com"));
        commit.setCommitter(new org.eclipse.jgit.lib.PersonIdent("test", "test@example.com"));
        ObjectId commitId = git.getRepository().newObjectInserter().insert(commit);
        git.getRepository().newObjectInserter().flush();
        return commitId;
    }
}
