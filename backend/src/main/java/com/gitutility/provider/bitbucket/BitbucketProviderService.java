package com.gitutility.provider.bitbucket;

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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * First-class adapter for Bitbucket Cloud (Atlassian) Git repositories and REST API 2.0.
 * Handles Git transport credentials, workspace repository browsing, pull request synchronization,
 * build status reporting, and live introspection.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BitbucketProviderService implements ScmProviderAdapter {

    private final GitHubAppConfigRepository configRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private static final Pattern BITBUCKET_URL_PATTERN =
            Pattern.compile("(?:https?://(?:[^@/:]+@)?bitbucket\\.org/|git@bitbucket\\.org:)([^/]+)/([^/.]+)(?:\\.git)?/?");

    private volatile String cachedOAuthToken = null;
    private volatile Instant oAuthTokenExpiry = Instant.MIN;

    @Override
    public ScmProviderType getProviderType() {
        return ScmProviderType.BITBUCKET;
    }

    @Override
    public boolean supportsUrl(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) return false;
        String lower = repoUrl.toLowerCase().trim();
        return lower.contains("bitbucket.org") || lower.contains("bitbucket");
    }

    @Override
    public String parseRepoFullName(String repoUrl) {
        if (repoUrl == null) return null;
        Matcher m = BITBUCKET_URL_PATTERN.matcher(repoUrl.trim());
        if (m.find()) {
            return m.group(1) + "/" + m.group(2);
        }
        return null;
    }

    private GitHubAppConfig getConfig() {
        return configRepository.findFirstByOrderByIdAsc().orElseGet(() -> GitHubAppConfig.builder().build());
    }

    private String getEffectiveToken(String explicitToken) {
        if (explicitToken != null && !explicitToken.isBlank()) {
            return explicitToken.trim();
        }
        GitHubAppConfig config = getConfig();
        return config.getBitbucketAccessToken() != null ? config.getBitbucketAccessToken().trim() : null;
    }

    private String getEffectiveUsername() {
        GitHubAppConfig config = getConfig();
        return (config.getBitbucketUsername() != null && !config.getBitbucketUsername().isBlank())
                ? config.getBitbucketUsername().trim()
                : "x-token-auth";
    }

    @Override
    public synchronized void invalidateTokenCache() {
        this.cachedOAuthToken = null;
        this.oAuthTokenExpiry = Instant.MIN;
    }

    public synchronized void invalidateCachedToken() {
        invalidateTokenCache();
    }

    private synchronized String resolveBearerOrBasicAuth(String explicitToken, boolean[] isBearerOut) {
        String token = getEffectiveToken(explicitToken);
        GitHubAppConfig config = getConfig();
        String username = getEffectiveUsername();
        String authType = config.getBitbucketAuthType() != null ? config.getBitbucketAuthType() : "OAUTH2";

        if (token == null || token.isBlank()) {
            if (isBearerOut != null && isBearerOut.length > 0) isBearerOut[0] = false;
            return null;
        }

        // If we already have a valid cached OAuth2 token for default credentials
        if (explicitToken == null && cachedOAuthToken != null && Instant.now().isBefore(oAuthTokenExpiry)) {
            if (isBearerOut != null && isBearerOut.length > 0) isBearerOut[0] = true;
            return cachedOAuthToken;
        }

        // 1. Direct Access Token mode (Bearer)
        if ("ACCESS_TOKEN".equalsIgnoreCase(authType)) {
            if (isBearerOut != null && isBearerOut.length > 0) isBearerOut[0] = true;
            return token;
        }

        // 2. OAuth 2.0 Client Credentials token exchange
        // Only run OAuth2 exchange if authType is explicitly OAUTH2 or not specified as APP_PASSWORD
        if ("OAUTH2".equalsIgnoreCase(authType) || (!"APP_PASSWORD".equalsIgnoreCase(authType) && !"ACCESS_TOKEN".equalsIgnoreCase(authType) && !username.isBlank() && !"x-token-auth".equalsIgnoreCase(username.trim()))) {
            try {
                HttpHeaders tokenHeaders = new HttpHeaders();
                tokenHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                String auth = username + ":" + token.trim();
                byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
                tokenHeaders.set("Authorization", "Basic " + new String(encodedAuth, StandardCharsets.UTF_8));

                HttpEntity<String> tokenReq = new HttpEntity<>("grant_type=client_credentials", tokenHeaders);
                ResponseEntity<String> tokenResp = restTemplate.exchange(
                        URI.create("https://bitbucket.org/site/oauth2/access_token"),
                        HttpMethod.POST,
                        tokenReq,
                        String.class
                );

                if (tokenResp.getStatusCode().is2xxSuccessful() && tokenResp.getBody() != null) {
                    JsonNode tokenNode = objectMapper.readTree(tokenResp.getBody());
                    String accessToken = tokenNode.path("access_token").asText(null);
                    int expiresIn = tokenNode.path("expires_in").asInt(7200);
                    if (accessToken != null && !accessToken.isBlank()) {
                        if (explicitToken == null) {
                            this.cachedOAuthToken = accessToken;
                            this.oAuthTokenExpiry = Instant.now().plusSeconds(Math.max(60, expiresIn - 60));
                        }
                        if (isBearerOut != null && isBearerOut.length > 0) isBearerOut[0] = true;
                        log.info("Successfully exchanged Bitbucket OAuth 2.0 Client Credentials (expires in {}s)", expiresIn);
                        return accessToken;
                    }
                }
            } catch (Exception e) {
                log.debug("Bitbucket OAuth2 token exchange not applicable (falling back): {}", e.getMessage());
            }
        }

        // 3. If username is "x-token-auth" or blank, treat token as direct Bearer token
        if (username.isBlank() || "x-token-auth".equalsIgnoreCase(username.trim())) {
            if (isBearerOut != null && isBearerOut.length > 0) isBearerOut[0] = true;
            return token;
        }

        // 4. Standard App Password / API Token via Basic Auth
        if (isBearerOut != null && isBearerOut.length > 0) isBearerOut[0] = false;
        return token;
    }

    private HttpHeaders createAuthHeaders(String explicitToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        boolean[] isBearer = new boolean[1];
        String resolved = resolveBearerOrBasicAuth(explicitToken, isBearer);
        if (resolved != null && !resolved.isBlank()) {
            if (isBearer[0]) {
                headers.setBearerAuth(resolved);
            } else {
                String username = getEffectiveUsername();
                String auth = username + ":" + resolved.trim();
                byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
                headers.set("Authorization", "Basic " + new String(encodedAuth, StandardCharsets.UTF_8));
            }
        }
        return headers;
    }

    @Override
    public CredentialsProvider getGitCredentials(String repoUrl, String explicitToken) {
        boolean[] isBearer = new boolean[1];
        String resolved = resolveBearerOrBasicAuth(explicitToken, isBearer);
        if (resolved == null || resolved.isBlank()) {
            return null;
        }
        if (isBearer[0]) {
            return new UsernamePasswordCredentialsProvider("x-token-auth", resolved);
        }
        String username = getEffectiveUsername();
        return new UsernamePasswordCredentialsProvider(username, resolved);
    }

    @Override
    public PermissionCheckReport testConnection(TestConnectionRequest req) {
        String token = getEffectiveToken(req.getToken());
        String repoFullName = parseRepoFullName(req.getRepoUrl());
        if (repoFullName == null && req.getRepoUrl() != null && req.getRepoUrl().contains("/")) {
            repoFullName = req.getRepoUrl().trim();
        }
        boolean writeRequired = PublicReadProbe.writeRequired(req);
        boolean skipPublic = PublicReadProbe.skipAnonymousProbe(req);

        JsonNode publicRepo = skipPublic ? null : probePublicBitbucketRepo(repoFullName);
        boolean publicRead = !skipPublic && publicRepo != null && !publicRepo.path("is_private").asBoolean(true);
        String publicDefaultBranch = publicRead ? publicRepo.path("mainbranch").path("name").asText("main") : "main";
        if (publicRead && !writeRequired) {
            return PublicReadProbe.publicReadSuccess(repoFullName, publicDefaultBranch, "Bitbucket");
        }

        if (token == null || token.isBlank()) {
            if (publicRead && writeRequired) {
                return PublicReadProbe.publicReadButWriteNeedsCredentials(repoFullName, publicDefaultBranch);
            }
            return PermissionCheckReport.builder()
                    .valid(false)
                    .httpStatusCode(401)
                    .message("Bitbucket Access Token / App Password is required.")
                    .errors(List.of("Missing Bitbucket credentials"))
                    .build();
        }

        HttpHeaders headers = createAuthHeaders(token);
        List<String> passed = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try {
            // 1. Verify identity (Workspace for OAuth2 client credentials, /user for App Passwords)
            String workspace = getConfig().getBitbucketWorkspace();
            boolean verified = false;

            if (workspace != null && !workspace.isBlank()) {
                try {
                    String wsUrl = "https://api.bitbucket.org/2.0/workspaces/" + workspace.trim();
                    ResponseEntity<String> wsResp = restTemplate.exchange(URI.create(wsUrl), HttpMethod.GET, new HttpEntity<>(headers), String.class);
                    JsonNode wsNode = objectMapper.readTree(wsResp.getBody());
                    String wsName = wsNode.path("name").asText(workspace);
                    passed.add("Bitbucket Workspace Verified: Connected to '" + wsName + "' (" + workspace + ")");
                    verified = true;
                } catch (Exception wsEx) {
                    log.debug("Notice testing workspace endpoint: {}", wsEx.getMessage());
                }
            }

            if (!verified) {
                try {
                    String userUrl = "https://api.bitbucket.org/2.0/user";
                    ResponseEntity<String> userResp = restTemplate.exchange(URI.create(userUrl), HttpMethod.GET, new HttpEntity<>(headers), String.class);
                    JsonNode userNode = objectMapper.readTree(userResp.getBody());
                    String username = userNode.path("username").asText(userNode.path("display_name").asText("Authenticated"));
                    passed.add("Bitbucket Authentication Verified: Connected as @" + username);
                    verified = true;
                } catch (Exception userEx) {
                    // Try testing repository catalog endpoint
                    String reposUrl = (workspace != null && !workspace.isBlank())
                            ? "https://api.bitbucket.org/2.0/repositories/" + workspace.trim() + "?pagelen=1"
                            : "https://api.bitbucket.org/2.0/repositories?role=member&pagelen=1";
                    restTemplate.exchange(URI.create(reposUrl), HttpMethod.GET, new HttpEntity<>(headers), String.class);
                    passed.add("Bitbucket Repository Access Verified: Successfully queried repository catalog");
                    verified = true;
                }
            }

            // 2. If repo specified, check repo access
            String defaultBranch = "main";
            boolean isPrivate = true;
            if (repoFullName != null && repoFullName.contains("/")) {
                String repoApiUrl = "https://api.bitbucket.org/2.0/repositories/" + repoFullName;
                ResponseEntity<String> repoResp = restTemplate.exchange(URI.create(repoApiUrl), HttpMethod.GET, new HttpEntity<>(headers), String.class);
                JsonNode repoNode = objectMapper.readTree(repoResp.getBody());
                isPrivate = repoNode.path("is_private").asBoolean(true);
                defaultBranch = repoNode.path("mainbranch").path("name").asText("main");
                passed.add("Repository Metadata Reachable: " + repoFullName + " (default branch: " + defaultBranch + ")");
            }

            return PermissionCheckReport.builder()
                    .valid(true)
                    .httpStatusCode(200)
                    .repoFullName(repoFullName)
                    .defaultBranch(defaultBranch)
                    .isPrivate(isPrivate)
                    .accessMode(publicRead || !isPrivate ? "PUBLIC" : "AUTHENTICATED")
                    .message("Bitbucket credentials validated successfully.")
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
            log.warn("Bitbucket test connection failed: {}", e.getMessage());
            errors.add("Bitbucket API Error: " + e.getMessage());
            return PermissionCheckReport.builder()
                    .valid(false)
                    .httpStatusCode(400)
                    .repoFullName(repoFullName)
                    .message("Failed to verify Bitbucket credentials: " + e.getMessage())
                    .errors(errors)
                    .build();
        }
    }

    private JsonNode probePublicBitbucketRepo(String repoFullName) {
        if (repoFullName == null || !repoFullName.contains("/")) {
            return null;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            String repoApiUrl = "https://api.bitbucket.org/2.0/repositories/" + repoFullName;
            ResponseEntity<String> repoResp = restTemplate.exchange(URI.create(repoApiUrl), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (repoResp.getBody() == null) {
                return null;
            }
            return objectMapper.readTree(repoResp.getBody());
        } catch (Exception e) {
            log.debug("Bitbucket anonymous public probe for {}: {}", repoFullName, e.getMessage());
            return null;
        }
    }

    @Override
    public RepoSearchResult searchRepositories(String query, int page, int perPage) {
        String token = getEffectiveToken(null);
        GitHubAppConfig config = getConfig();
        String workspace = config.getBitbucketWorkspace();

        List<GitHubRepoOption> items = new ArrayList<>();
        int total = 0;

        if (token != null && !token.isBlank()) {
            try {
                HttpHeaders headers = createAuthHeaders(token);
                String baseUrl = (workspace != null && !workspace.isBlank())
                        ? "https://api.bitbucket.org/2.0/repositories/" + workspace.trim()
                        : "https://api.bitbucket.org/2.0/repositories";

                String url = baseUrl + "?pagelen=" + Math.min(perPage, 50) + "&page=" + Math.max(page, 1);
                if (query != null && !query.isBlank()) {
                    url += "&q=name~\"" + query.trim() + "\"";
                }

                ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
                JsonNode root = objectMapper.readTree(resp.getBody());
                total = root.path("size").asInt(0);

                JsonNode values = root.path("values");
                if (values.isArray()) {
                    for (JsonNode item : values) {
                        String fullSlug = item.path("full_name").asText();
                        String cloneUrl = "https://bitbucket.org/" + fullSlug + ".git";
                        items.add(GitHubRepoOption.builder()
                                .id("bitbucket-" + item.path("uuid").asText(fullSlug))
                                .name(item.path("name").asText())
                                .fullName(fullSlug)
                                .cloneUrl(cloneUrl)
                                .htmlUrl("https://bitbucket.org/" + fullSlug)
                                .defaultBranch(item.path("mainbranch").path("name").asText("main"))
                                .isPrivate(item.path("is_private").asBoolean(true))
                                .provider("bitbucket")
                                .owner(item.path("owner").path("display_name").asText("Bitbucket"))
                                .hasWriteAccess(true)
                                .hasAdminAccess(true)
                                .build());
                    }
                }
            } catch (Exception e) {
                log.warn("Bitbucket repo search notice: {}", e.getMessage());
            }
        }

        return RepoSearchResult.builder()
                .items(items)
                .totalCount(total > 0 ? total : items.size())
                .page(page)
                .perPage(perPage)
                .hasMore(items.size() >= perPage)
                .provider("bitbucket")
                .build();
    }

    @Override
    public List<GitHubRepoOption> listAccessibleRepositories() {
        return searchRepositories("", 1, 50).getItems();
    }

    @Override
    public boolean createRemoteRepository(CreateRepoRequest req) {
        String token = getEffectiveToken(null);
        if (token == null || token.isBlank()) return false;

        GitHubAppConfig config = getConfig();
        String workspace = config.getBitbucketWorkspace();
        if (workspace == null || workspace.isBlank()) {
            String repoFullName = req.getName();
            if (repoFullName.contains("/")) {
                workspace = repoFullName.substring(0, repoFullName.indexOf("/"));
            } else {
                workspace = getEffectiveUsername();
            }
        }

        String repoSlug = req.getName().contains("/")
                ? req.getName().substring(req.getName().indexOf("/") + 1)
                : req.getName();

        try {
            HttpHeaders headers = createAuthHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("scm", "git");
            body.put("name", repoSlug);
            body.put("is_private", req.isPrivateRepo());
            body.put("description", req.getDescription() != null ? req.getDescription() : "Mirrored by GitMirror Hub");

            String url = "https://api.bitbucket.org/2.0/repositories/" + workspace + "/" + repoSlug;
            restTemplate.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            log.info("Created new Bitbucket repository: {}/{}", workspace, repoSlug);
            return true;
        } catch (Exception e) {
            log.error("Failed to create Bitbucket repository: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<SyncDiffReport.PrSyncDetail> listOpenPullRequests(String repoFullName) {
        String token = getEffectiveToken(null);
        if (token == null || token.isBlank() || repoFullName == null) return Collections.emptyList();

        List<SyncDiffReport.PrSyncDetail> list = new ArrayList<>();
        try {
            HttpHeaders headers = createAuthHeaders(token);
            String url = "https://api.bitbucket.org/2.0/repositories/" + repoFullName + "/pullrequests?state=OPEN&pagelen=50";
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode values = root.path("values");
            if (values.isArray()) {
                for (JsonNode pr : values) {
                    list.add(SyncDiffReport.PrSyncDetail.builder()
                            .sourcePrNumber(pr.path("id").asLong())
                            .title(pr.path("title").asText())
                            .headBranch(pr.path("source").path("branch").path("name").asText())
                            .baseBranch(pr.path("destination").path("branch").path("name").asText())
                            .state("open")
                            .authorLogin(pr.path("author").path("nickname").asText(
                                    pr.path("author").path("display_name").asText(null)))
                            .sourcePrUrl(pr.path("links").path("html").path("href").asText(null))
                            .body(pr.path("description").asText(null))
                            .commentsCount(pr.path("comment_count").asInt(0))
                            .build());
                }
            }
        } catch (Exception e) {
            log.debug("Bitbucket open PRs list note: {}", e.getMessage());
        }
        return list;
    }

    @Override
    public Long createPullRequest(String repoFullName, String title, String body, String headRef, String baseRef) {
        String token = getEffectiveToken(null);
        if (token == null || token.isBlank() || repoFullName == null) return null;

        try {
            HttpHeaders headers = createAuthHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> payload = new HashMap<>();
            payload.put("title", title);
            payload.put("description", body != null ? body : "");
            payload.put("source", Map.of("branch", Map.of("name", headRef)));
            payload.put("destination", Map.of("branch", Map.of("name", baseRef)));

            String url = "https://api.bitbucket.org/2.0/repositories/" + repoFullName + "/pullrequests";
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            long prId = root.path("id").asLong();
            log.info("Created Bitbucket Pull Request #{} on {}", prId, repoFullName);
            return prId;
        } catch (Exception e) {
            log.warn("Failed to create Bitbucket Pull Request on {}: {}", repoFullName, e.getMessage());
            return null;
        }
    }

    @Override
    public void closePullRequest(String repoFullName, long prNumber) {
        String token = getEffectiveToken(null);
        if (token == null || token.isBlank() || repoFullName == null) {
            return;
        }
        try {
            HttpHeaders headers = createAuthHeaders(token);
            String url = "https://api.bitbucket.org/2.0/repositories/" + repoFullName
                    + "/pullrequests/" + prNumber + "/decline";
            restTemplate.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(headers), String.class);
            log.info("Declined Bitbucket Pull Request #{} on {}", prNumber, repoFullName);
        } catch (Exception e) {
            log.warn("Failed to decline Bitbucket Pull Request #{} on {}: {}", prNumber, repoFullName, e.getMessage());
        }
    }

    @Override
    public boolean updatePullRequest(String repoFullName, long prNumber, String title, String body) {
        String token = getEffectiveToken(null);
        if (token == null || token.isBlank() || repoFullName == null) {
            return false;
        }
        try {
            HttpHeaders headers = createAuthHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> payload = new HashMap<>();
            if (title != null) {
                payload.put("title", title);
            }
            if (body != null) {
                payload.put("description", body);
            }
            String url = "https://api.bitbucket.org/2.0/repositories/" + repoFullName + "/pullrequests/" + prNumber;
            restTemplate.exchange(URI.create(url), HttpMethod.PUT, new HttpEntity<>(payload, headers), String.class);
            return true;
        } catch (Exception e) {
            log.warn("Failed to update Bitbucket Pull Request #{} on {}: {}", prNumber, repoFullName, e.getMessage());
            return false;
        }
    }

    @Override
    public PullRequestSnapshot getPullRequest(String repoFullName, long prNumber) {
        String token = getEffectiveToken(null);
        if (token == null || token.isBlank() || repoFullName == null) {
            return null;
        }
        try {
            HttpHeaders headers = createAuthHeaders(token);
            String url = "https://api.bitbucket.org/2.0/repositories/" + repoFullName + "/pullrequests/" + prNumber;
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            Instant updated = null;
            String iso = root.path("updated_on").asText(null);
            if (iso != null && !iso.isBlank()) {
                try {
                    updated = Instant.parse(iso);
                } catch (Exception ignored) {}
            }
            String body = root.path("description").asText("");
            if (root.path("content").has("raw")) {
                body = root.path("content").path("raw").asText(body);
            }
            return PullRequestSnapshot.builder()
                    .title(root.path("title").asText(null))
                    .body(body)
                    .updatedAt(updated)
                    .build();
        } catch (Exception e) {
            log.debug("Bitbucket get PR #{} on {}: {}", prNumber, repoFullName, e.getMessage());
            return null;
        }
    }

    @Override
    public void replicateComments(String repoFullName, long prNumber, List<String> comments) {
        String token = getEffectiveToken(null);
        if (token == null || token.isBlank() || repoFullName == null || comments == null) return;

        HttpHeaders headers = createAuthHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        for (String comment : comments) {
            try {
                Map<String, Object> body = Map.of("content", Map.of("raw", comment));
                String url = "https://api.bitbucket.org/2.0/repositories/" + repoFullName + "/pullrequests/" + prNumber + "/comments";
                restTemplate.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            } catch (Exception e) {
                log.debug("Bitbucket comment replication notice: {}", e.getMessage());
            }
        }
    }

    @Override
    public boolean replicateCommitStatus(String repoFullName, String sha, String state, String targetUrl, String description, String context) {
        String token = getEffectiveToken(null);
        if (token == null || token.isBlank() || repoFullName == null || sha == null) return false;

        try {
            HttpHeaders headers = createAuthHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Map status state: Bitbucket expects SUCCESSFUL, FAILED, INPROGRESS, STOPPED
            String bbState = "INPROGRESS";
            String lowerState = state.toLowerCase();
            if (lowerState.contains("success")) {
                bbState = "SUCCESSFUL";
            } else if (lowerState.contains("fail") || lowerState.contains("error")) {
                bbState = "FAILED";
            } else if (lowerState.contains("stop") || lowerState.contains("cancel")) {
                bbState = "STOPPED";
            }

            Map<String, Object> body = new HashMap<>();
            body.put("key", context != null ? context : "git-mirror-ci");
            body.put("state", bbState);
            body.put("name", context != null ? context : "GitMirror CI Check");
            if (targetUrl != null) body.put("url", targetUrl);
            if (description != null) body.put("description", description + " (Mirrored)");

            String url = "https://api.bitbucket.org/2.0/repositories/" + repoFullName + "/commit/" + sha.trim() + "/statuses/build";
            restTemplate.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            log.info("Replicated Bitbucket build status [{}] on {} for commit {}", bbState, repoFullName, sha.substring(0, Math.min(sha.length(), 7)));
            return true;
        } catch (Exception e) {
            log.debug("Bitbucket build status replication notice for {}: {}", repoFullName, e.getMessage());
            return false;
        }
    }

    @Override
    public List<SyncDiffReport.ReleaseDetail> listReleases(String repoFullName) {
        String token = getEffectiveToken(null);
        if (token == null || token.isBlank() || repoFullName == null) return Collections.emptyList();

        List<SyncDiffReport.ReleaseDetail> list = new ArrayList<>();
        try {
            HttpHeaders headers = createAuthHeaders(token);
            String url = "https://api.bitbucket.org/2.0/repositories/" + repoFullName + "/refs/tags?pagelen=30";
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode values = root.path("values");
            if (values.isArray()) {
                for (JsonNode tag : values) {
                    String tagName = tag.path("name").asText();
                    list.add(SyncDiffReport.ReleaseDetail.builder()
                            .id((long) tagName.hashCode())
                            .name("Tag: " + tagName)
                            .tagName(tagName)
                            .body(tag.path("message").asText(""))
                            .publishedAt(tag.path("target").path("date").asText(null))
                            .author(tag.path("tagger").path("raw").asText("Bitbucket"))
                            .htmlUrl("https://bitbucket.org/" + repoFullName + "/src/" + tagName)
                            .isDraft(false)
                            .isPrerelease(false)
                            .assets(new ArrayList<>())
                            .build());
                }
            }
        } catch (Exception e) {
            log.debug("Bitbucket tags/releases inspection notice: {}", e.getMessage());
        }
        return list;
    }

    @Override
    public List<SyncDiffReport.CiCheckRunDetail> listCiCheckRuns(String repoFullName, String commitSha) {
        String token = getEffectiveToken(null);
        if (token == null || token.isBlank() || repoFullName == null || commitSha == null) return Collections.emptyList();

        List<SyncDiffReport.CiCheckRunDetail> list = new ArrayList<>();
        try {
            HttpHeaders headers = createAuthHeaders(token);
            String url = "https://api.bitbucket.org/2.0/repositories/" + repoFullName + "/commit/" + commitSha.trim() + "/statuses?pagelen=30";
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode values = root.path("values");
            if (values.isArray()) {
                for (JsonNode st : values) {
                    String bbState = st.path("state").asText();
                    String conclusion = "neutral";
                    String status = "completed";
                    if ("SUCCESSFUL".equalsIgnoreCase(bbState)) conclusion = "success";
                    else if ("FAILED".equalsIgnoreCase(bbState)) conclusion = "failure";
                    else if ("INPROGRESS".equalsIgnoreCase(bbState)) {
                        status = "in_progress";
                        conclusion = null;
                    }

                    list.add(SyncDiffReport.CiCheckRunDetail.builder()
                            .id((long) st.path("key").asText("check").hashCode())
                            .name(st.path("name").asText(st.path("key").asText("Bitbucket Pipeline")))
                            .status(status)
                            .conclusion(conclusion)
                            .startedAt(st.path("created_on").asText(null))
                            .completedAt(st.path("updated_on").asText(null))
                            .htmlUrl(st.path("url").asText(null))
                            .appName("Bitbucket Pipelines")
                            .headSha(commitSha)
                            .build());
                }
            }
        } catch (Exception e) {
            log.debug("Bitbucket CI check runs notice: {}", e.getMessage());
        }
        return list;
    }
}
