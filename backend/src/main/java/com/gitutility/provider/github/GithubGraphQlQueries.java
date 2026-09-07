package com.gitutility.provider.github;

/**
 * GitHub GraphQL v4 queries used for high-volume mirror operations.
 * <p>
 * Other high-value GraphQL use cases for this project:
 * <ul>
 *   <li>{@link #OPEN_PULL_REQUESTS_PAGE} — paginated open PRs with author/body/counts in one round-trip</li>
 *   <li>{@link #REPOSITORY_MIRROR_SNAPSHOT} — open PR total + recent releases for Refresh Diff cards</li>
 *   <li>Future: {@code rateLimit} field to proactively throttle before REST secondary limits</li>
 *   <li>Future: batch {@code ref(qualifiedName)} lookups for branch HEAD SHAs without git fetch</li>
 * </ul>
 */
public final class GithubGraphQlQueries {

    public static final String OPEN_PULL_REQUESTS_PAGE = """
            query OpenPullRequestsPage($owner: String!, $name: String!, $first: Int!, $after: String) {
              rateLimit { cost remaining limit resetAt }
              repository(owner: $owner, name: $name) {
                pullRequests(states: OPEN, first: $first, after: $after, orderBy: {field: UPDATED_AT, direction: DESC}) {
                  totalCount
                  pageInfo { hasNextPage endCursor }
                  nodes {
                    number
                    title
                    body
                    url
                    isDraft
                    author { login }
                    headRefName
                    baseRefName
                    isCrossRepository
                    headRepository { nameWithOwner }
                    comments { totalCount }
                    reviewThreads { totalCount }
                  }
                }
              }
            }
            """;

    /** Single call for Refresh Diff: open PR total + preview + recent releases with assets. */
    public static final String REPOSITORY_MIRROR_SNAPSHOT = """
            query RepositoryMirrorSnapshot($owner: String!, $name: String!, $releaseCount: Int!, $prPreview: Int!) {
              rateLimit { cost remaining limit resetAt }
              repository(owner: $owner, name: $name) {
                pullRequests(states: OPEN, first: $prPreview, orderBy: {field: UPDATED_AT, direction: DESC}) {
                  totalCount
                  pageInfo { hasNextPage }
                  nodes {
                    number
                    title
                    body
                    url
                    isDraft
                    author { login }
                    headRefName
                    baseRefName
                    isCrossRepository
                    headRepository { nameWithOwner }
                    comments { totalCount }
                    reviewThreads { totalCount }
                  }
                }
                releases(first: $releaseCount, orderBy: {field: CREATED_AT, direction: DESC}) {
                  totalCount
                  nodes {
                    databaseId
                    name
                    tagName
                    description
                    isDraft
                    isPrerelease
                    publishedAt
                    url
                    author { login }
                    releaseAssets(first: 20) {
                      nodes {
                        name
                        downloadUrl
                        size
                        downloadCount
                      }
                    }
                  }
                }
              }
            }
            """;

    /** Releases-only query for release sync when PR preview is not needed. */
    public static final String RELEASES_LIST = """
            query RepositoryReleases($owner: String!, $name: String!, $releaseCount: Int!) {
              rateLimit { cost remaining limit resetAt }
              repository(owner: $owner, name: $name) {
                releases(first: $releaseCount, orderBy: {field: CREATED_AT, direction: DESC}) {
                  totalCount
                  nodes {
                    databaseId
                    name
                    tagName
                    description
                    isDraft
                    isPrerelease
                    publishedAt
                    url
                    author { login }
                    releaseAssets(first: 20) {
                      nodes {
                        name
                        downloadUrl
                        size
                        downloadCount
                      }
                    }
                  }
                }
              }
            }
            """;

    private GithubGraphQlQueries() {
    }
}
