package com.gitutility.service;

import com.gitutility.model.entity.SyncJob;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncJobServiceUsageScoreTest {

    @Test
    void usageScoreSumsAttributableRestGraphqlAndGitCalls() {
        SyncJob heavyGraphql = SyncJob.builder()
                .restCallCount(2)
                .graphqlCallCount(40)
                .gitHttpFetchCount(1)
                .gitHttpPushBatchCount(1)
                .build();
        SyncJob heavyGit = SyncJob.builder()
                .restCallCount(8)
                .graphqlCallCount(0)
                .gitHttpFetchCount(12)
                .gitHttpPushBatchCount(9)
                .build();
        assertEquals(44, SyncJobService.usageScore(heavyGraphql));
        assertEquals(29, SyncJobService.usageScore(heavyGit));
        assertTrue(SyncJobService.usageScore(heavyGraphql) > SyncJobService.usageScore(heavyGit));
        assertEquals(0, SyncJobService.usageScore(new SyncJob()));
    }
}
