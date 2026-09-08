package com.gitutility.provider.github;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.gitutility.model.dto.MirrorMetadataSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GithubMirrorSnapshotGraphQlTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void parseMirrorSnapshotMapsPrTotalPreviewAndReleases() throws Exception {
        String json = """
                {
                  "repository": {
                    "pullRequests": {
                      "totalCount": 2501,
                      "pageInfo": { "hasNextPage": true },
                      "nodes": [
                        {
                          "number": 1,
                          "title": "latest pr",
                          "body": "body",
                          "url": "https://github.com/microsoft/vscode/pull/1",
                          "isDraft": false,
                          "author": { "login": "alice" },
                          "headRefName": "feature",
                          "baseRefName": "main",
                          "isCrossRepository": false,
                          "headRepository": { "nameWithOwner": "microsoft/vscode" },
                          "comments": { "totalCount": 2 },
                          "reviewThreads": { "totalCount": 1 }
                        }
                      ]
                    },
                    "releases": {
                      "totalCount": 120,
                      "nodes": [
                        {
                          "databaseId": 99,
                          "name": "1.95",
                          "tagName": "1.95.0",
                          "description": "Release notes",
                          "isDraft": false,
                          "isPrerelease": false,
                          "publishedAt": "2024-10-01T00:00:00Z",
                          "url": "https://github.com/microsoft/vscode/releases/tag/1.95.0",
                          "author": { "login": "bot" },
                          "releaseAssets": {
                            "nodes": [
                              { "name": "vscode.dmg", "downloadUrl": "https://example.com/vscode.dmg", "size": 2048, "downloadCount": 10 }
                            ]
                          }
                        }
                      ]
                    }
                  }
                }
                """;

        MirrorMetadataSnapshot snapshot = GithubMirrorSnapshotGraphQl.parseMirrorSnapshot(
                mapper.readTree(json), "microsoft/vscode", 100);

        assertEquals(2501, snapshot.openPrTotalCount());
        assertTrue(snapshot.pullRequestsTruncated());
        assertEquals(1, snapshot.prPreview().size());
        assertEquals("alice", snapshot.prPreview().getFirst().getAuthorLogin());
        assertEquals(120, snapshot.releaseTotalCount());
        assertEquals(1, snapshot.releases().size());
        assertEquals("1.95.0", snapshot.releases().getFirst().getTagName());
        assertEquals(1, snapshot.releases().getFirst().getAssets().size());
    }
}
