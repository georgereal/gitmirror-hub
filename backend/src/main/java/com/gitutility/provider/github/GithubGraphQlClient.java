package com.gitutility.provider.github;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.gitutility.model.dto.MirrorMetadataSnapshot;
import com.gitutility.model.dto.PrListPage;
import com.gitutility.model.dto.SyncDiffReport;
import com.gitutility.service.ProviderRateMeter;
import com.gitutility.service.ScmInstallationKeyResolver;
import com.gitutility.service.ScmQuotaContext;
import com.gitutility.service.ScmQuotaTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class GithubGraphQlClient {

    private final RestTemplate restTemplate;
    private final ScmQuotaTracker scmQuotaTracker;
    private final ScmInstallationKeyResolver installationKeyResolver;
    private final ProviderRateMeter providerRateMeter;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public JsonNode execute(String graphqlUrl, String token, String query, Map<String, Object> variables) {
        return execute(graphqlUrl, token, query, variables, null);
    }

    public JsonNode execute(String graphqlUrl,
                            String token,
                            String query,
                            Map<String, Object> variables,
                            String repoFullName) {
        if (graphqlUrl == null || graphqlUrl.isBlank() || token == null || token.isBlank()) {
            return null;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token.trim());
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));

            Map<String, Object> body = new HashMap<>();
            body.put("query", query);
            if (variables != null && !variables.isEmpty()) {
                body.put("variables", variables);
            }

            ResponseEntity<String> response = restTemplate.postForEntity(
                    URI.create(graphqlUrl), new HttpEntity<>(body, headers), String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            recordGraphqlQuota(graphqlUrl, response, root, repoFullName);
            if (root.has("errors") && root.path("errors").isArray() && !root.path("errors").isEmpty()) {
                log.debug("GitHub GraphQL errors: {}", root.path("errors"));
                return null;
            }
            return root.path("data");
        } catch (Exception e) {
            log.debug("GitHub GraphQL request failed: {}", e.getMessage());
            return null;
        }
    }

    private void recordGraphqlQuota(String graphqlUrl,
                                    ResponseEntity<String> response,
                                    JsonNode root,
                                    String repoFullName) {
        if (response == null) {
            return;
        }
        try {
            String host = URI.create(graphqlUrl).getHost();
            String provider = host != null && host.toLowerCase().contains("github.com") ? "github" : "ghes";
            if (host != null && host.toLowerCase().contains("github") && !host.toLowerCase().contains("github.com")) {
                provider = "ghes";
            }
            ScmQuotaContext.Bound ctx = ScmQuotaContext.current();
            if (ctx != null && ctx.provider() != null) {
                provider = ctx.provider();
            }
            String install = ctx != null && ctx.installationKey() != null
                    ? ctx.installationKey()
                    : (installationKeyResolver != null ? installationKeyResolver.resolve(provider) : "default");
            String repo = repoFullName != null ? repoFullName
                    : (ctx != null ? ctx.repoFullName() : null);

            HttpHeaders headers = response.getHeaders();
            Integer remaining = firstInt(headers, "X-RateLimit-Remaining", "RateLimit-Remaining");
            Integer limit = firstInt(headers, "X-RateLimit-Limit", "RateLimit-Limit");
            Integer cost = null;
            JsonNode rl = root != null ? root.path("data").path("rateLimit") : null;
            if (rl != null && !rl.isMissingNode() && !rl.isNull()) {
                if (rl.has("remaining")) {
                    remaining = rl.path("remaining").asInt();
                }
                if (rl.has("limit")) {
                    limit = rl.path("limit").asInt();
                }
                if (rl.has("cost")) {
                    cost = rl.path("cost").asInt();
                }
            }
            boolean rateLimited = response.getStatusCode().value() == 429;
            if (scmQuotaTracker != null) {
                scmQuotaTracker.recordGraphqlQuota(provider, install, repo, remaining, limit, cost, rateLimited);
            }
            if (providerRateMeter != null) {
                providerRateMeter.recordGraphqlPoints(cost);
            }
        } catch (Exception e) {
            log.debug("GraphQL quota record notice: {}", e.getMessage());
        }
    }

    private static Integer firstInt(HttpHeaders headers, String... names) {
        for (String name : names) {
            String raw = headers.getFirst(name);
            if (raw != null && !raw.isBlank()) {
                try {
                    return Integer.parseInt(raw.trim().split(",")[0].trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    public PrListPage fetchOpenPullRequestsPage(String graphqlUrl,
                                              String token,
                                              String repoFullName,
                                              String cursor,
                                              int pageSize) {
        String[] parts = splitRepoFullName(repoFullName);
        if (parts == null) {
            return PrListPage.empty();
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("owner", parts[0]);
        variables.put("name", parts[1]);
        variables.put("first", Math.max(1, Math.min(pageSize, 100)));
        if (cursor != null && !cursor.isBlank()) {
            variables.put("after", cursor);
        }
        JsonNode data = execute(graphqlUrl, token, GithubGraphQlQueries.OPEN_PULL_REQUESTS_PAGE, variables, repoFullName);
        if (data == null) {
            return null;
        }
        return GithubPullRequestGraphQl.parseOpenPullRequestsPage(data, repoFullName);
    }

    public PrListPage fetchClosedPullRequestsPage(String graphqlUrl,
                                                 String token,
                                                 String repoFullName,
                                                 String cursor,
                                                 int pageSize) {
        String[] parts = splitRepoFullName(repoFullName);
        if (parts == null) {
            return PrListPage.empty();
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("owner", parts[0]);
        variables.put("name", parts[1]);
        variables.put("first", Math.max(1, Math.min(pageSize, 100)));
        if (cursor != null && !cursor.isBlank()) {
            variables.put("after", cursor);
        }
        JsonNode data = execute(graphqlUrl, token, GithubGraphQlQueries.CLOSED_PULL_REQUESTS_PAGE, variables, repoFullName);
        if (data == null) {
            return null;
        }
        return GithubPullRequestGraphQl.parseOpenPullRequestsPage(data, repoFullName);
    }

    public MirrorMetadataSnapshot fetchMirrorMetadataSnapshot(String graphqlUrl,
                                                            String token,
                                                            String repoFullName,
                                                            int prPreviewLimit,
                                                            int releaseLimit) {
        String[] parts = splitRepoFullName(repoFullName);
        if (parts == null) {
            return null;
        }
        int prPreview = Math.max(1, Math.min(prPreviewLimit, 100));
        Map<String, Object> variables = Map.of(
                "owner", parts[0],
                "name", parts[1],
                "releaseCount", Math.max(1, Math.min(releaseLimit, 30)),
                "prPreview", prPreview);
        JsonNode data = execute(graphqlUrl, token, GithubGraphQlQueries.REPOSITORY_MIRROR_SNAPSHOT, variables, repoFullName);
        if (data == null) {
            return null;
        }
        return GithubMirrorSnapshotGraphQl.parseMirrorSnapshot(data, repoFullName, prPreview);
    }

    public List<SyncDiffReport.ReleaseDetail> fetchReleases(String graphqlUrl,
                                                          String token,
                                                          String repoFullName,
                                                          int releaseLimit) {
        String[] parts = splitRepoFullName(repoFullName);
        if (parts == null) {
            return null;
        }
        Map<String, Object> variables = Map.of(
                "owner", parts[0],
                "name", parts[1],
                "releaseCount", Math.max(1, Math.min(releaseLimit, 30)));
        JsonNode data = execute(graphqlUrl, token, GithubGraphQlQueries.RELEASES_LIST, variables, repoFullName);
        if (data == null) {
            return null;
        }
        return GithubMirrorSnapshotGraphQl.parseReleases(data);
    }

    static String[] splitRepoFullName(String repoFullName) {
        if (repoFullName == null || repoFullName.isBlank()) {
            return null;
        }
        int slash = repoFullName.indexOf('/');
        if (slash <= 0 || slash >= repoFullName.length() - 1) {
            return null;
        }
        return new String[]{repoFullName.substring(0, slash), repoFullName.substring(slash + 1)};
    }
}
