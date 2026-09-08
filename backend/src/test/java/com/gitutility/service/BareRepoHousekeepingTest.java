package com.gitutility.service;

import com.gitutility.model.dto.DiffInspectOptions;
import com.gitutility.model.dto.SyncDiffReport.BranchDiffDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BareRepoHousekeepingTest {

    @Test
    void purgeAppleDoubleArtifactsRemovesRootLevelFiles(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("._packed-refs"), "junk");
        Files.createDirectories(tempDir.resolve("objects/pack"));

        int removed = BareRepoHousekeeping.purgeAppleDoubleArtifacts(tempDir);

        assertEquals(1, removed);
        assertFalse(Files.exists(tempDir.resolve("._packed-refs")));
    }

    @Test
    void purgeAppleDoubleArtifactsRemovesDotUnderscoreFiles(@TempDir Path tempDir) throws Exception {
        Path packDir = tempDir.resolve("objects/pack");
        Files.createDirectories(packDir);
        Files.writeString(packDir.resolve("pack-abc.pack"), "real");
        Files.writeString(packDir.resolve("._pack-abc.pack"), "junk");
        Files.writeString(packDir.resolve("._pack-abc.idx"), "junk");

        int removed = BareRepoHousekeeping.purgeAppleDoubleArtifacts(tempDir);

        assertEquals(2, removed);
        assertTrue(Files.exists(packDir.resolve("pack-abc.pack")));
        assertFalse(Files.exists(packDir.resolve("._pack-abc.pack")));
    }

    @Test
    void hasAnyHeadRefsDetectsPackedAndLooseHeads(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("refs/heads"));
        assertFalse(BareRepoHousekeeping.hasAnyHeadRefs(tempDir.toFile()));

        Files.writeString(tempDir.resolve("packed-refs"), "# pack\nabc refs/heads/main\n");
        assertTrue(BareRepoHousekeeping.hasAnyHeadRefs(tempDir.toFile()));

        Files.delete(tempDir.resolve("packed-refs"));
        Files.writeString(tempDir.resolve("refs/heads/feature"), "ref: abc\n");
        assertTrue(BareRepoHousekeeping.hasAnyHeadRefs(tempDir.toFile()));
    }

    @Test
    void uniqueBranchTipObjectIdsReadsPackedHeadsAndSourceRemotes(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("packed-refs"), """
                # pack
                abc1111111111111111111111111111111111111 refs/heads/main
                def2222222222222222222222222222222222222 refs/heads/feature
                def2222222222222222222222222222222222222 refs/remotes/source/feature
                """);

        try (var repo = new org.eclipse.jgit.internal.storage.file.FileRepository(tempDir.toFile())) {
            Set<org.eclipse.jgit.lib.ObjectId> tips = BareRepoHousekeeping.uniqueBranchTipObjectIds(repo);
            assertEquals(2, tips.size());
            assertTrue(tips.contains(org.eclipse.jgit.lib.ObjectId.fromString("abc1111111111111111111111111111111111111")));
            assertTrue(tips.contains(org.eclipse.jgit.lib.ObjectId.fromString("def2222222222222222222222222222222222222")));
        }
    }

    @Test
    void listPairRefNamesSeparatesSourceAndDest(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("packed-refs"), """
                # pack
                abc1111111111111111111111111111111111111 refs/heads/main
                def2222222222222222222222222222222222222 refs/remotes/target/main
                aaa3333333333333333333333333333333333333 refs/remotes/target/dest-only
                bbb4444444444444444444444444444444444444 refs/tags/v1
                ccc5555555555555555555555555555555555555 refs/remotes/target-tags/v1
                """);

        try (var repo = new org.eclipse.jgit.internal.storage.file.FileRepository(tempDir.toFile())) {
            BareRepoHousekeeping.PairRefNames names = BareRepoHousekeeping.listPairRefNames(repo);
            assertEquals(1, names.sourceBranchCount());
            assertEquals(2, names.destBranchCount());
            assertEquals(1, names.destOnlyBranchCount());
            assertTrue(names.sourceTags().contains("v1"));
            assertTrue(names.destTags().contains("v1"));
        }
    }
}

class GitComparisonServiceBranchSelectTest {

    @Test
    void buildBranchDetailListPrioritizesActionableBranches() {
        List<BranchDiffDetail> actionable = List.of(
                BranchDiffDetail.builder().branchName("feature-x").status("AHEAD").build(),
                BranchDiffDetail.builder().branchName("feature-y").status("DIVERGED").build()
        );
        List<BranchDiffDetail> inSync = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            inSync.add(BranchDiffDetail.builder().branchName("ok-" + i).status("IN_SYNC").build());
        }
        inSync.add(BranchDiffDetail.builder().branchName("main").status("IN_SYNC").build());

        List<BranchDiffDetail> selected = GitComparisonService.buildBranchDetailList(actionable, inSync, 5);

        assertEquals(5, selected.size());
        assertEquals("feature-x", selected.get(0).getBranchName());
        assertEquals("feature-y", selected.get(1).getBranchName());
        assertTrue(selected.stream().anyMatch(b -> "main".equals(b.getBranchName())));
    }

    @Test
    void pageBranchesAppliesSearchStatusAndOffset() {
        List<BranchDiffDetail> actionable = List.of(
                BranchDiffDetail.builder().branchName("feature-pending").status("AHEAD").build(),
                BranchDiffDetail.builder().branchName("feature-diverged").status("DIVERGED").build()
        );
        List<BranchDiffDetail> inSync = List.of(
                BranchDiffDetail.builder().branchName("main").status("IN_SYNC").build(),
                BranchDiffDetail.builder().branchName("release-1").status("IN_SYNC").build(),
                BranchDiffDetail.builder().branchName("dependabot/foo").status("SOURCE_MISSING").build()
        );
        DiffInspectOptions options = new DiffInspectOptions(false, false, 1, 1, null, "IN_SYNC");

        var slice = GitComparisonService.pageBranches(actionable, inSync, options, false);

        assertEquals(1, slice.rows().size());
        assertEquals("release-1", slice.rows().get(0).getBranchName());
        assertEquals(2, slice.filteredTotal());
        // offset=1, limit=1 on 2 filtered rows is the last page
        assertFalse(slice.hasMore());
    }

    @Test
    void selectBranchesPrioritizesTrunkAndCapsCount() {
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < 200; i++) {
            names.add("feature-" + i);
        }
        names.add("main");
        names.add("release/1.0");

        List<String> selected = GitComparisonService.selectBranchesForAnalysis(names, 5);

        assertEquals(5, selected.size());
        assertEquals("main", selected.get(0));
        // After trunks, remaining names are filled alphabetically up to the cap
        assertEquals(List.of("main", "feature-0", "feature-1", "feature-10", "feature-100"), selected);
        assertFalse(selected.contains("release/1.0"));
    }
}
