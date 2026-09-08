package com.gitutility.provider.gitlab;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.gitutility.model.dto.*;
import com.gitutility.model.entity.GitHubAppConfig;
import com.gitutility.model.enums.ScmProviderType;
import com.gitutility.provider.PublicReadProbe;
import com.gitutility.provider.ScmProviderAdapter;
import com.gitutility.repository.GitHubAppConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapter for GitLab (GitLab.com and Self-Hosted GitLab instances) using GitLab v4 REST API.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GitLabProviderService implements ScmProviderAdapter {

    private final GitHubAppConfigRepository configRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private static final Pattern GITLAB_REPO_PATTERN =
            Pattern.compile("(?:https?://[^/]+/|git@[^:]+:)([^/]+)/([^/.]+)(?:\\.git)?/?");

    @Override
    public ScmProviderType getProviderType() {
        return ScmProviderType.GITLAB;
    }

    private GitHubAppConfig getConfig() {
        return configRepository.findFirstByOrderByIdAsc().orElseGet(() -> GitHubAppConfig.builder().build());
    }

    private String getNormalizedHostUrl() {
        GitHubAppConfig config = getConfig();
        String host = config.getGitlabHostUrl();
        if (host == null || host.isBlank()) return "https://gitlab.com";
        return host.trim().replaceAll("/+$", "");
    }

    @Override
    public boolean supportsUrl(String repoUrl) {
        if (repoUrl == null) return false;
        String host = getNormalizedHostUrl();
        String hostDomain = host.replace("https://", "").replace("http://", "").split("/")[0];
        return repoUrl.toLowerCase().contains("gitlab.com") || repoUrl.toLowerCase().contains(hostDomain.toLowerCase());
    }

    @Override
    public String parseRepoFullName(String repoUrl) {
        if (repoUrl == null) return null;
        Matcher m = GITLAB_REPO_PATTERN.matcher(repoUrl.trim());
        if (m.find()) {
            return m.group(1) + "/" + m.group(2);
        }
        return null;
    }

    private String getEffectiveToken(String explicitToken) {
        if (explicitToken != null && !explicitToken.isBlank()) {
            return explicitToken.trim();
        }
        GitHubAppConfig config = getConfig();
        return config.getGitlabAccessToken() != null ? config.getGitlabAccessToken().trim() : null;
    }

    private HttpHeaders createHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (token != null && !token.isBlank()) {
            headers.set("PRIVATE-TOKEN", token.trim());
        }
        return headers;
    }

    @Override
    public CredentialsProvider getGitCredentials(String repoUrl, String explicitToken) {
        String token = getEffectiveToken(explicitToken);
        if (token == null || token.isBlank()) return null;
        return new UsernamePasswordCredentialsProvider("oauth2", token);
    }

    @Override
    public PermissionCheckReport testConnection(TestConnectionRequest req) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveToken(req.getToken());
        String repoFullName = parseRepoFullName(req.getRepoUrl());
        boolean writeRequired = PublicReadProbe.writeRequired(req);
        boolean skipPublic = PublicReadProbe.skipAnonymousProbe(req);

        JsonNode publicProject = skipPublic ? null : probePublicGitlabProject(host, repoFullName);
        boolean publicRead = !skipPublic && publicProject != null && "public".equalsIgnoreCase(publicProject.path("visibility").asText("private"));
        String publicDefaultBranch = publicRead ? publicProject.path("default_branch").asText("main") : "main";
        if (publicRead && !writeRequired) {
            return PublicReadProbe.publicReadSuccess(repoFullName, publicDefaultBranch, "GitLab");
        }

        if (token == null || token.isBlank()) {
            if (publicRead && writeRequired) {
                return PublicReadProbe.publicReadButWriteNeedsCredentials(repoFullName, publicDefaultBranch);
            }
            return PermissionCheckReport.builder()
                    .valid(false)
                    .httpStatusCode(401)
                    .message("GitLab Personal Access Token is required.")
                    .errors(List.of("Missing GitLab token"))
                    .build();
        }

        List<String> passed = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try {
            HttpHeaders headers = createHeaders(token);
            String userUrl = host + "/api/v4/user";
            ResponseEntity<String> userResp = restTemplate.exchange(URI.create(userUrl), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode userNode = objectMapper.readTree(userResp.getBody());
            String username = userNode.path("username").asText("Authenticated User");
            passed.add("GitLab Authentication Verified: Connected to " + host + " as @" + username);

            String defaultBranch = "main";
            boolean isPrivate = true;
            if (repoFullName != null && repoFullName.contains("/")) {
                String encodedPath = URLEncoder.encode(repoFullName, StandardCharsets.UTF_8);
                String projectUrl = host + "/api/v4/projects/" + encodedPath;
                ResponseEntity<String> projResp = restTemplate.exchange(URI.create(projectUrl), HttpMethod.GET, new HttpEntity<>(headers), String.class);
                JsonNode projNode = objectMapper.readTree(projResp.getBody());
                defaultBranch = projNode.path("default_branch").asText("main");
                isPrivate = !"public".equalsIgnoreCase(projNode.path("visibility").asText("private"));
                passed.add("GitLab Project Metadata Verified: " + repoFullName + " (default branch: " + defaultBranch + ")");
            }

            return PermissionCheckReport.builder()
                    .valid(true)
                    .httpStatusCode(200)
                    .repoFullName(repoFullName)
                    .defaultBranch(defaultBranch)
                    .isPrivate(isPrivate)
                    .accessMode(publicRead || !isPrivate ? "PUBLIC" : "AUTHENTICATED")
                    .message("GitLab connection successful.")
                    .permissions(PermissionCheckReport.PermissionsDetail.builder()
                            .contentsRead(true)
                            .contentsWrite(true)
                            .pullRequests(true)
                            .commitStatuses(true)
                            .webhooks(true)
                            .admin(true)
                            .build())
                    .passedChecks(passed)
                    .warnings(warnings)
                    .errors(errors)
                    .build();
        } catch (Exception e) {
            log.warn("GitLab test connection failed: {}", e.getMessage());
            errors.add("GitLab API Error: " + e.getMessage());
            return PermissionCheckReport.builder()
                    .valid(false)
                    .httpStatusCode(400)
                    .repoFullName(repoFullName)
                    .message("Failed to verify GitLab connection: " + e.getMessage())
                    .errors(errors)
                    .build();
        }
    }

    private JsonNode probePublicGitlabProject(String host, String repoFullName) {
        if (host == null || repoFullName == null || !repoFullName.contains("/")) {
            return null;
        }
        try {
            HttpHeaders headers = createHeaders(null);
            String encodedPath = URLEncoder.encode(repoFullName, StandardCharsets.UTF_8);
            String projectUrl = host + "/api/v4/projects/" + encodedPath;
            ResponseEntity<String> projResp = restTemplate.exchange(URI.create(projectUrl), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (projResp.getBody() == null) {
                return null;
            }
            return objectMapper.readTree(projResp.getBody());
        } catch (Exception e) {
            log.debug("GitLab anonymous public probe for {}: {}", repoFullName, e.getMessage());
            return null;
        }
    }

    @Override
    public RepoSearchResult searchRepositories(String query, int page, int perPage) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveToken(null);
        List<GitHubRepoOption> items = new ArrayList<>();

        if (token != null) {
            try {
                HttpHeaders headers = createHeaders(token);
                String url = host + "/api/v4/projects?membership=true&per_page=" + perPage + "&page=" + page;
                if (query != null && !query.isBlank()) {
                    url += "&search=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
                }

                ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
                JsonNode projects = objectMapper.readTree(resp.getBody());
                if (projects.isArray()) {
                    for (JsonNode proj : projects) {
                        String pathWithNamespace = proj.path("path_with_namespace").asText();
                        items.add(GitHubRepoOption.builder()
                                .id("gitlab-" + proj.path("id").asText())
                                .name(proj.path("name").asText())
                                .fullName(pathWithNamespace)
                                .cloneUrl(proj.path("http_url_to_repo").asText())
                                .htmlUrl(proj.path("web_url").asText())
                                .defaultBranch(proj.path("default_branch").asText("main"))
                                .isPrivate(!"public".equalsIgnoreCase(proj.path("visibility").asText("private")))
                                .provider("gitlab")
                                .owner(proj.path("namespace").path("name").asText("GitLab"))
                                .hasWriteAccess(true)
                                .hasAdminAccess(true)
                                .build());
                    }
                }
            } catch (Exception e) {
                log.warn("GitLab repo search notice: {}", e.getMessage());
            }
        }

        return RepoSearchResult.builder()
                .items(items)
                .totalCount(items.size())
                .page(page)
                .perPage(perPage)
                .hasMore(items.size() >= perPage)
                .provider("gitlab")
                .build();
    }

    @Override
    public List<GitHubRepoOption> listAccessibleRepositories() {
        return searchRepositories("", 1, 50).getItems();
    }

    @Override
    public boolean createRemoteRepository(CreateRepoRequest req) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveToken(null);
        if (token == null) return false;

        try {
            HttpHeaders headers = createHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("name", req.getName());
            body.put("description", req.getDescription() != null ? req.getDescription() : "Mirrored by GitMirror Hub");
            body.put("visibility", req.isPrivateRepo() ? "private" : "public");

            String url = host + "/api/v4/projects";
            restTemplate.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            log.info("Created new GitLab project: {}", req.getName());
            return true;
        } catch (Exception e) {
            log.error("Failed to create GitLab repository: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<SyncDiffReport.PrSyncDetail> listOpenPullRequests(String repoFullName) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveToken(null);
        if (token == null || repoFullName == null) return Collections.emptyList();

        List<SyncDiffReport.PrSyncDetail> list = new ArrayList<>();
        try {
            HttpHeaders headers = createHeaders(token);
            String encoded = URLEncoder.encode(repoFullName, StandardCharsets.UTF_8);
            String url = host + "/api/v4/projects/" + encoded + "/merge_requests?state=opened&per_page=50";
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode mrs = objectMapper.readTree(resp.getBody());
            if (mrs.isArray()) {
                for (JsonNode mr : mrs) {
                    list.add(SyncDiffReport.PrSyncDetail.builder()
                            .sourcePrNumber(mr.path("iid").asLong())
                            .title(mr.path("title").asText())
                            .headBranch(mr.path("source_branch").asText())
                            .baseBranch(mr.path("target_branch").asText())
                            .state("open")
                            .authorLogin(mr.path("author").path("username").asText(null))
                            .sourcePrUrl(mr.path("web_url").asText(null))
                            .body(mr.path("description").asText(null))
                            .commentsCount(mr.path("user_notes_count").asInt(0))
                            .draft(mr.path("draft").asBoolean(false))
                            .build());
                }
            }
        } catch (Exception e) {
            log.debug("GitLab MR list notice: {}", e.getMessage());
        }
        return list;
    }

    @Override
    public Long createPullRequest(String repoFullName, String title, String body, String headRef, String baseRef) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveToken(null);
        if (token == null || repoFullName == null) return null;

        try {
            HttpHeaders headers = createHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> payload = new HashMap<>();
            payload.put("title", title);
            payload.put("description", body != null ? body : "");
            payload.put("source_branch", headRef);
            payload.put("target_branch", baseRef);

            String encoded = URLEncoder.encode(repoFullName, StandardCharsets.UTF_8);
            String url = host + "/api/v4/projects/" + encoded + "/merge_requests";
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            long mrIid = root.path("iid").asLong();
            log.info("Created GitLab Merge Request #{} on {}", mrIid, repoFullName);
            return mrIid;
        } catch (Exception e) {
            log.warn("Failed to create GitLab Merge Request on {}: {}", repoFullName, e.getMessage());
            return null;
        }
    }

    @Override
    public void closePullRequest(String repoFullName, long prNumber) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveToken(null);
        if (token == null || repoFullName == null) {
            return;
        }
        try {
            HttpHeaders headers = createHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            String encoded = URLEncoder.encode(repoFullName, StandardCharsets.UTF_8);
            String url = host + "/api/v4/projects/" + encoded + "/merge_requests/" + prNumber;
            restTemplate.exchange(URI.create(url), HttpMethod.PUT,
                    new HttpEntity<>(Map.of("state_event", "close"), headers), String.class);
            log.info("Closed GitLab Merge Request #{} on {}", prNumber, repoFullName);
        } catch (Exception e) {
            log.warn("Failed to close GitLab Merge Request #{} on {}: {}", prNumber, repoFullName, e.getMessage());
        }
    }

    @Override
    public boolean updatePullRequest(String repoFullName, long prNumber, String title, String body) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveToken(null);
        if (token == null || repoFullName == null) {
            return false;
        }
        try {
            HttpHeaders headers = createHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> payload = new HashMap<>();
            if (title != null) {
                payload.put("title", title);
            }
            if (body != null) {
                payload.put("description", body);
            }
            String encoded = URLEncoder.encode(repoFullName, StandardCharsets.UTF_8);
            String url = host + "/api/v4/projects/" + encoded + "/merge_requests/" + prNumber;
            restTemplate.exchange(URI.create(url), HttpMethod.PUT, new HttpEntity<>(payload, headers), String.class);
            return true;
        } catch (Exception e) {
            log.warn("Failed to update GitLab Merge Request #{} on {}: {}", prNumber, repoFullName, e.getMessage());
            return false;
        }
    }

    @Override
    public PullRequestSnapshot getPullRequest(String repoFullName, long prNumber) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveToken(null);
        if (token == null || repoFullName == null) {
            return null;
        }
        try {
            HttpHeaders headers = createHeaders(token);
            String encoded = URLEncoder.encode(repoFullName, StandardCharsets.UTF_8);
            String url = host + "/api/v4/projects/" + encoded + "/merge_requests/" + prNumber;
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            Instant updated = null;
            String iso = root.path("updated_at").asText(null);
            if (iso != null && !iso.isBlank()) {
                try {
                    updated = Instant.parse(iso);
                } catch (Exception ignored) {}
            }
            return PullRequestSnapshot.builder()
                    .title(root.path("title").asText(null))
                    .body(root.path("description").asText(""))
                    .updatedAt(updated)
                    .build();
        } catch (Exception e) {
            log.debug("GitLab get MR #{} on {}: {}", prNumber, repoFullName, e.getMessage());
            return null;
        }
    }

    @Override
    public void replicateComments(String repoFullName, long prNumber, List<String> comments) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveToken(null);
        if (token == null || repoFullName == null || comments == null) return;

        HttpHeaders headers = createHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String encoded = URLEncoder.encode(repoFullName, StandardCharsets.UTF_8);

        for (String comment : comments) {
            try {
                Map<String, Object> body = Map.of("body", comment);
                String url = host + "/api/v4/projects/" + encoded + "/merge_requests/" + prNumber + "/notes";
                restTemplate.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            } catch (Exception e) {
                log.debug("GitLab MR comment note: {}", e.getMessage());
            }
        }
    }

    @Override
    public boolean replicateCommitStatus(String repoFullName, String sha, String state, String targetUrl, String description, String context) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveToken(null);
        if (token == null || repoFullName == null || sha == null) return false;

        try {
            HttpHeaders headers = createHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Map state: GitLab expects pending, running, success, failed, canceled
            String glState = "running";
            String lower = state.toLowerCase();
            if (lower.contains("success")) glState = "success";
            else if (lower.contains("fail") || lower.contains("error")) glState = "failed";
            else if (lower.contains("cancel")) glState = "canceled";

            Map<String, Object> body = new HashMap<>();
            body.put("state", glState);
            if (targetUrl != null) body.put("target_url", targetUrl);
            if (description != null) body.put("description", description + " (Mirrored)");
            if (context != null) body.put("name", context);

            String encoded = URLEncoder.encode(repoFullName, StandardCharsets.UTF_8);
            String url = host + "/api/v4/projects/" + encoded + "/statuses/" + sha.trim();
            restTemplate.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            log.info("Replicated GitLab commit status [{}] on {} for commit {}", glState, repoFullName, sha.substring(0, Math.min(sha.length(), 7)));
            return true;
        } catch (Exception e) {
            log.debug("GitLab commit status notice for {}: {}", repoFullName, e.getMessage());
            return false;
        }
    }

    @Override
    public List<SyncDiffReport.ReleaseDetail> listReleases(String repoFullName) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveToken(null);
        if (token == null || repoFullName == null) return Collections.emptyList();

        List<SyncDiffReport.ReleaseDetail> list = new ArrayList<>();
        try {
            HttpHeaders headers = createHeaders(token);
            String encoded = URLEncoder.encode(repoFullName, StandardCharsets.UTF_8);
            String url = host + "/api/v4/projects/" + encoded + "/releases?per_page=30";
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode releases = objectMapper.readTree(resp.getBody());
            if (releases.isArray()) {
                for (JsonNode rel : releases) {
                    list.add(SyncDiffReport.ReleaseDetail.builder()
                            .id((long) rel.path("tag_name").asText().hashCode())
                            .name(rel.path("name").asText(rel.path("tag_name").asText()))
                            .tagName(rel.path("tag_name").asText())
                            .body(rel.path("description").asText(""))
                            .publishedAt(rel.path("released_at").asText(null))
                            .author(rel.path("author").path("name").asText("GitLab"))
                            .htmlUrl(rel.path("_links").path("self").asText(null))
                            .isDraft(false)
                            .isPrerelease(false)
                            .assets(new ArrayList<>())
                            .build());
                }
            }
        } catch (Exception e) {
            log.debug("GitLab releases inspection notice: {}", e.getMessage());
        }
        return list;
    }

    @Override
    public List<SyncDiffReport.CiCheckRunDetail> listCiCheckRuns(String repoFullName, String commitSha) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveToken(null);
        if (token == null || repoFullName == null || commitSha == null) return Collections.emptyList();

        List<SyncDiffReport.CiCheckRunDetail> list = new ArrayList<>();
        try {
            HttpHeaders headers = createHeaders(token);
            String encoded = URLEncoder.encode(repoFullName, StandardCharsets.UTF_8);
            String url = host + "/api/v4/projects/" + encoded + "/repository/commits/" + commitSha.trim() + "/statuses?per_page=30";
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode statuses = objectMapper.readTree(resp.getBody());
            if (statuses.isArray()) {
                for (JsonNode st : statuses) {
                    String glState = st.path("status").asText();
                    String conclusion = "neutral";
                    String status = "completed";
                    if ("success".equalsIgnoreCase(glState)) conclusion = "success";
                    else if ("failed".equalsIgnoreCase(glState)) conclusion = "failure";
                    else if ("running".equalsIgnoreCase(glState) || "pending".equalsIgnoreCase(glState)) {
                        status = "in_progress";
                        conclusion = null;
                    }

                    list.add(SyncDiffReport.CiCheckRunDetail.builder()
                            .id(st.path("id").asLong())
                            .name(st.path("name").asText("GitLab CI"))
                            .status(status)
                            .conclusion(conclusion)
                            .startedAt(st.path("created_at").asText(null))
                            .completedAt(st.path("finished_at").asText(null))
                            .htmlUrl(st.path("target_url").asText(null))
                            .appName("GitLab CI/CD")
                            .headSha(commitSha)
                            .build());
                }
            }
        } catch (Exception e) {
            log.debug("GitLab CI check runs notice: {}", e.getMessage());
        }
        return list;
    }
}
