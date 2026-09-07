package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorageStatusResponse {
    private String localDirectory;
    private long localUsedBytes;
    private String localUsedFormatted;

    private String nasDirectory;
    private long nasUsedBytes;
    private String nasUsedFormatted;

    private int totalCachedRepos;
    private int hotReposCount;
    private int autoLruReposCount;
    private int ephemeralReposCount;
    private int nasReposCount;

    private long maxDiskQuotaBytes;
    private String maxDiskQuotaFormatted;
    private double quotaUsedPercent;
}
