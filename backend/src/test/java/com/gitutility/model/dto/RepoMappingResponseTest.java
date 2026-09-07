package com.gitutility.model.dto;

import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.enums.SyncDirection;
import com.gitutility.model.enums.SyncStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RepoMappingResponseTest {

    @Test
    void fromEntityFallsBackToLastMirrorWhenSnapshotMissing() {
        RepoMapping mapping = RepoMapping.builder()
                .id(1L)
                .name("testMirror")
                .repoAUrl("https://github.com/acme/mirror-src")
                .repoBUrl("https://github.com/acme/mirror-charts")
                .branchPattern("*")
                .syncDirection(SyncDirection.UNIDIRECTIONAL_A_TO_B)
                .active(true)
                .lastSyncStatus(SyncStatus.SUCCESS)
                .lastMirrorBranchesCount(3)
                .lastMirrorTagsCount(270)
                .lastMirrorLfsObjects(0)
                .build();

        RepoMappingResponse response = RepoMappingResponse.fromEntity(mapping);

        assertEquals(3, response.getSourceBranchesCount());
        assertEquals(270, response.getTagsSourceCount());
        assertEquals(0, response.getLfsTotal());
        assertEquals(0, response.getLfsSynced());
        assertEquals(0, response.getLfsPending());
        assertNull(response.getPrsTotal());
    }

    @Test
    void fromEntityAppliesLastMirrorLfsFloorOverPartialSnapshot() {
        RepoMapping mapping = RepoMapping.builder()
                .id(5L)
                .name("vscode")
                .repoAUrl("https://github.com/microsoft/vscode")
                .repoBUrl("https://github.com/acme/mirror-vscode")
                .branchPattern("*")
                .syncDirection(SyncDirection.UNIDIRECTIONAL_A_TO_B)
                .active(true)
                .lastMirrorLfsObjects(678)
                .lastMirrorBranchesCount(80)
                .diffSnapshotJson("{\"sourceBranchesCount\":80,\"destBranchesCount\":80,\"inSyncBranchesCount\":80,"
                        + "\"pendingBranchesCount\":0,\"divergedBranchesCount\":0,"
                        + "\"prsTotal\":1605,\"prsSynced\":1605,"
                        + "\"lfsTotal\":98,\"lfsSynced\":0,\"lfsPending\":98,"
                        + "\"tagsSourceCount\":200,\"tagsTargetCount\":200}")
                .build();

        RepoMappingResponse response = RepoMappingResponse.fromEntity(mapping);

        assertEquals(80, response.getSourceBranchesCount());
        assertEquals(80, response.getDestBranchesCount());
        assertEquals(80, response.getInSyncBranchesCount());
        assertEquals(1605, response.getPrsTotal());
        assertEquals(1605, response.getPrsSynced());
        assertEquals(678, response.getLfsTotal());
        assertEquals(678, response.getLfsSynced());
        assertEquals(0, response.getLfsPending());
        assertEquals(200, response.getTagsSourceCount());
        assertEquals(200, response.getTagsTargetCount());
    }
}
