package com.gitutility.provider.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitutility.model.dto.PrListPage;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class GithubPullRequestGraphQlTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseOpenPullRequestsPageMapsAuthorBodyAndCounts() throws Exception {
        String json = """
                {
                  "repository": {
                    "pullRequests": {
                      "totalCount": 2501,
                      "pageInfo": { "hasNextPage": true, "endCursor": "Y3Vyc29yOjEwMA==" },
                      "nodes": [
                        {
                          "number": 42,
                          "title": "feat: bulk sync",
                          "body": "hello",
                          "url": "https://github.com/microsoft/vscode/pull/42",
                          "updatedAt": "2026-09-07T12:00:00Z",
                          "isDraft": true,
                          "author": { "login": "alice" },
                          "headRefName": "feature/bulk",
                          "baseRefName": "main",
                          "isCrossRepository": false,
                          "headRepository": { "nameWithOwner": "microsoft/vscode" },
                          "comments": { "totalCount": 3 },
                          "reviewThreads": { "totalCount": 7 }
                        }
                      ]
                    }
                  }
                }
                """;

        PrListPage page = GithubPullRequestGraphQl.parseOpenPullRequestsPage(
                mapper.readTree(json), "microsoft/vscode");

        assertEquals(2501, page.totalCount());
        assertTrue(page.hasNextPage());
        assertTrue(page.graphql());
        assertEquals("Y3Vyc29yOjEwMA==", page.nextCursor());
        assertEquals(1, page.items().size());
        var pr = page.items().getFirst();
        assertEquals(42L, pr.getSourcePrNumber());
        assertEquals("alice", pr.getAuthorLogin());
        assertEquals("hello", pr.getBody());
        assertEquals(Instant.parse("2026-09-07T12:00:00Z"), pr.getUpdatedAt());
        assertEquals(3, pr.getCommentsCount());
        assertEquals(7, pr.getReviewCommentsCount());
        assertTrue(pr.isDraft());
        assertFalse(pr.isFork());
    }

    @Test
    void parseOpenPullRequestsPageDetectsForkHead() throws Exception {
        String json = """
                {
                  "repository": {
                    "pullRequests": {
                      "totalCount": 1,
                      "pageInfo": { "hasNextPage": false, "endCursor": null },
                      "nodes": [
                        {
                          "number": 9,
                          "title": "fork pr",
                          "body": "",
                          "url": "https://github.com/org/upstream/pull/9",
                          "isDraft": false,
                          "author": { "login": "bob" },
                          "headRefName": "patch",
                          "baseRefName": "main",
                          "isCrossRepository": true,
                          "headRepository": { "nameWithOwner": "bob/fork" },
                          "comments": { "totalCount": 0 },
                          "reviewThreads": { "totalCount": 0 }
                        }
                      ]
                    }
                  }
                }
                """;

        PrListPage page = GithubPullRequestGraphQl.parseOpenPullRequestsPage(
                mapper.readTree(json), "org/upstream");

        assertFalse(page.hasNextPage());
        assertTrue(page.items().getFirst().isFork());
    }
}
