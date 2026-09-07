package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoSearchResult {
    private List<GitHubRepoOption> items;
    private int page;
    private int limit;
    private int perPage;
    private int totalCount;
    private boolean hasMore;
    private String provider;
    private String query;
}
