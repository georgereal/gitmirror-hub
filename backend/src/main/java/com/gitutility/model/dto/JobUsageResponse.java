package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobUsageResponse {

    private Instant since;
    private Instant capturedAt;

    @Builder.Default
    private List<JobUsageRow> jobs = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JobUsageRow {
        private Long id;
        private Long mappingId;
        private String pairName;
        private String status;
        private String triggerType;
        private String branch;
        private Instant startedAt;
        private Instant completedAt;
        private Long durationMs;
        private Integer restCallCount;
        private Integer graphqlCallCount;
        private Integer graphqlPointsUsed;
        private Integer graphql429Count;
        private Integer lfsApiCallCount;
        private Integer lfsTransferHttpCount;
        private Integer gitHttpFetchCount;
        private Integer gitHttpPushBatchCount;
        private Integer rateLimit429Count;
        private Integer gitHttpThrottleCount;
        private Long gitReadBytes;
        private Long gitWriteBytes;
        private Long lfsBytes;
        private Long bytesTransferred;
        private String provider;
        private Integer usageScore;
    }
}
