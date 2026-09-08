package com.gitutility.provider.github;

import tools.jackson.databind.JsonNode;
import com.gitutility.model.dto.MirrorMetadataSnapshot;
import com.gitutility.model.dto.SyncDiffReport;

import java.util.ArrayList;
import java.util.List;

public final class GithubMirrorSnapshotGraphQl {

    private GithubMirrorSnapshotGraphQl() {
    }

    public static MirrorMetadataSnapshot parseMirrorSnapshot(JsonNode data, String repoFullName, int prPreviewLimit) {
        if (data == null || data.isMissingNode()) {
            return MirrorMetadataSnapshot.empty();
        }
        JsonNode repository = data.path("repository");
        if (repository.isMissingNode()) {
            return MirrorMetadataSnapshot.empty();
        }

        JsonNode pullRequests = repository.path("pullRequests");
        int openPrTotal = pullRequests.path("totalCount").asInt(0);
        boolean prTruncated = pullRequests.path("pageInfo").path("hasNextPage").asBoolean(false)
                || openPrTotal > prPreviewLimit;
        List<SyncDiffReport.PrSyncDetail> prPreview = parsePrNodes(
                pullRequests.path("nodes"), repoFullName);

        JsonNode releases = repository.path("releases");
        int releaseTotal = releases.path("totalCount").asInt(0);
        List<SyncDiffReport.ReleaseDetail> releaseItems = parseReleaseNodes(releases.path("nodes"));

        return new MirrorMetadataSnapshot(openPrTotal, prPreview, prTruncated, releaseTotal, releaseItems);
    }

    public static List<SyncDiffReport.ReleaseDetail> parseReleases(JsonNode data) {
        if (data == null || data.isMissingNode()) {
            return List.of();
        }
        return parseReleaseNodes(data.path("repository").path("releases").path("nodes"));
    }

    private static List<SyncDiffReport.PrSyncDetail> parsePrNodes(JsonNode nodes, String repoFullName) {
        List<SyncDiffReport.PrSyncDetail> items = new ArrayList<>();
        if (!nodes.isArray()) {
            return items;
        }
        for (JsonNode pr : nodes) {
            if (pr == null || pr.isNull()) {
                continue;
            }
            String headRepo = pr.path("headRepository").path("nameWithOwner").asText(null);
            boolean isFork = pr.path("isCrossRepository").asBoolean(false)
                    || (headRepo != null && !headRepo.equalsIgnoreCase(repoFullName));
            items.add(SyncDiffReport.PrSyncDetail.builder()
                    .sourcePrNumber(pr.path("number").asLong())
                    .title(pr.path("title").asText(""))
                    .headBranch(pr.path("headRefName").asText())
                    .baseBranch(pr.path("baseRefName").asText())
                    .state("open")
                    .isFork(isFork)
                    .authorLogin(pr.path("author").path("login").asText(null))
                    .sourcePrUrl(pr.path("url").asText(null))
                    .body(pr.path("body").asText(null))
                    .commentsCount(pr.path("comments").path("totalCount").asInt(0))
                    .reviewCommentsCount(pr.path("reviewThreads").path("totalCount").asInt(0))
                    .draft(pr.path("isDraft").asBoolean(false))
                    .build());
        }
        return items;
    }

    private static List<SyncDiffReport.ReleaseDetail> parseReleaseNodes(JsonNode nodes) {
        List<SyncDiffReport.ReleaseDetail> items = new ArrayList<>();
        if (!nodes.isArray()) {
            return items;
        }
        for (JsonNode rel : nodes) {
            if (rel == null || rel.isNull()) {
                continue;
            }
            List<SyncDiffReport.ReleaseAssetDetail> assets = new ArrayList<>();
            JsonNode assetNodes = rel.path("releaseAssets").path("nodes");
            if (assetNodes.isArray()) {
                for (JsonNode asset : assetNodes) {
                    long size = asset.path("size").asLong(0);
                    assets.add(SyncDiffReport.ReleaseAssetDetail.builder()
                            .name(asset.path("name").asText())
                            .sizeBytes(size)
                            .formattedSize(formatBytes(size))
                            .downloadUrl(asset.path("downloadUrl").asText(null))
                            .contentType("application/octet-stream")
                            .downloadCount(asset.path("downloadCount").asInt(0))
                            .build());
                }
            }
            items.add(SyncDiffReport.ReleaseDetail.builder()
                    .id(rel.path("databaseId").asLong(0))
                    .name(rel.path("name").asText(rel.path("tagName").asText()))
                    .tagName(rel.path("tagName").asText())
                    .body(rel.path("description").asText(""))
                    .publishedAt(rel.path("publishedAt").asText(null))
                    .author(rel.path("author").path("login").asText("GitHub"))
                    .isDraft(rel.path("isDraft").asBoolean(false))
                    .isPrerelease(rel.path("isPrerelease").asBoolean(false))
                    .htmlUrl(rel.path("url").asText(null))
                    .assets(assets)
                    .build());
        }
        return items;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
