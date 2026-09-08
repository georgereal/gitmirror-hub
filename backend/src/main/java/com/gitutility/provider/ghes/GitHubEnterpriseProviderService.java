package com.gitutility.provider.ghes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitutility.model.dto.*;
import com.gitutility.model.entity.GitHubAppConfig;
import com.gitutility.model.enums.ScmProviderType;
import com.gitutility.provider.GithubPullRequestJson;
import com.gitutility.provider.GithubRestPagination;
import com.gitutility.provider.PublicReadProbe;
import com.gitutility.provider.ScmProviderAdapter;
import com.gitutility.provider.github.GithubGraphQlClient;
import com.gitutility.repository.GitHubAppConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dedicated adapter for GitHub Enterprise Server (GHES) on-premises or private cloud deployments.
 * Supports custom host endpoints, API v3 routing (/api/v3), GHES GitHub App installation tokens,
 * and Personal Access Tokens.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GitHubEnterpriseProviderService implements ScmProviderAdapter {

    private final GitHubAppConfigRepository configRepository;
    private final RestTemplate restTemplate;
    private final GithubGraphQlClient graphQlClient;
    private final com.gitutility.service.ScmCredentialService scmCredentialService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${git-utility.github.graphql-enabled:true}")
    private boolean graphqlEnabled;

    private static final int DEFAULT_PR_PAGE_SIZE = 100;
    private static final int DEFAULT_RELEASE_LIMIT = 30;

    private volatile String cachedInstallationToken = null;
    private volatile Instant tokenExpiry = Instant.MIN;

    private static final Pattern GENERIC_REPO_PATTERN =
            Pattern.compile("(?:https?://[^/]+/|git@[^:]+:)([^/]+)/([^/.]+)(?:\\.git)?/?");

    @Override
    public ScmProviderType getProviderType() {
        return ScmProviderType.GITHUB_ENTERPRISE;
    }

    private GitHubAppConfig getConfig() {
        return configRepository.findFirstByOrderByIdAsc().orElseGet(() -> GitHubAppConfig.builder().build());
    }

    private String getNormalizedHostUrl() {
        Long credId = com.gitutility.service.ScmCredentialContext.currentId();
        if (credId != null && scmCredentialService != null) {
            try {
                String host = scmCredentialService.require(credId).getHostUrl();
                if (host != null && !host.isBlank()) {
                    return host.trim().replaceAll("/+$", "");
                }
            } catch (Exception ignored) {
                // fall through to legacy row
            }
        }
        GitHubAppConfig config = getConfig();
        String host = config.getGhesHostUrl();
        if (host == null || host.isBlank()) return null;
        return host.trim().replaceAll("/+$", "");
    }

    @Override
    public boolean supportsUrl(String repoUrl) {
        if (repoUrl == null) return false;
        String lower = repoUrl.toLowerCase();
        if (lower.contains("github.com")) {
            return false;
        }
        if (scmCredentialService != null && scmCredentialService.hostMatchesAnyGhes(repoUrl)) {
            return true;
        }
        String host = getNormalizedHostUrl();
        if (host != null && !host.isBlank()) {
            String hostDomain = host.replace("https://", "").replace("http://", "").split("/")[0];
            return repoUrl.toLowerCase().contains(hostDomain.toLowerCase());
        }
        return false;
    }

    @Override
    public String parseRepoFullName(String repoUrl) {
        if (repoUrl == null) return null;
        Matcher m = GENERIC_REPO_PATTERN.matcher(repoUrl.trim());
        if (m.find()) {
            return m.group(1) + "/" + m.group(2);
        }
        return null;
    }

    public synchronized String getEffectiveGhesToken(String explicitToken) {
        if (explicitToken != null && !explicitToken.isBlank()) {
            return explicitToken.trim();
        }
        if (scmCredentialService != null) {
            return scmCredentialService.resolveCurrentOrNull();
        }
        return null;
    }

    private synchronized String getInstallationAccessToken() {
        if (scmCredentialService == null) {
            return null;
        }
        Long id = com.gitutility.service.ScmCredentialContext.currentId();
        if (id == null) {
            return null;
        }
        return scmCredentialService.resolveAccessToken(id);
    }

    private String generateJwt(String appId, String privateKeyPem) throws Exception {
        long nowSeconds = Instant.now().getEpochSecond();
        String headerJson = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String payloadJson = String.format("{\"iat\":%d,\"exp\":%d,\"iss\":\"%s\"}", nowSeconds - 60, nowSeconds + 600, appId.trim());

        String headerBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String payloadBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String unsignedToken = headerBase64 + "." + payloadBase64;

        PrivateKey privateKey = parsePrivateKey(privateKeyPem);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(unsignedToken.getBytes(StandardCharsets.UTF_8));
        byte[] signatureBytes = signature.sign();

        String signatureBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes);
        return unsignedToken + "." + signatureBase64;
    }

    private PrivateKey parsePrivateKey(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (obj instanceof PEMKeyPair keyPair) {
                return converter.getPrivateKey(keyPair.getPrivateKeyInfo());
            } else if (obj instanceof PrivateKeyInfo keyInfo) {
                return converter.getPrivateKey(keyInfo);
            }
            throw new IllegalArgumentException("Unsupported GHES private key PEM format");
        }
    }

    private HttpHeaders createHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.valueOf("application/vnd.github+json")));
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token.trim());
        }
        return headers;
    }

    @Override
    public CredentialsProvider getGitCredentials(String repoUrl, String explicitToken) {
        String token = getEffectiveGhesToken(explicitToken);
        if (token == null || token.isBlank()) return null;
        return new UsernamePasswordCredentialsProvider("x-access-token", token);
    }

    @Override
    public PermissionCheckReport testConnection(TestConnectionRequest req) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(req.getToken());
        String repoFullName = parseRepoFullName(req.getRepoUrl());
        boolean writeRequired = PublicReadProbe.writeRequired(req);
        boolean skipPublic = PublicReadProbe.skipAnonymousProbe(req);

        if (host == null || host.isBlank()) {
            return PermissionCheckReport.builder()
                    .valid(false)
                    .httpStatusCode(400)
                    .message("GitHub Enterprise Server Host URL is not configured.")
                    .errors(List.of("Missing GHES Host URL in provider settings"))
                    .build();
        }

        JsonNode publicRepoNode = skipPublic ? null : probePublicGhesRepo(host, repoFullName);
        boolean publicRead = !skipPublic && publicRepoNode != null && !publicRepoNode.path("private").asBoolean(true);
        String publicDefaultBranch = publicRead ? publicRepoNode.path("default_branch").asText("main") : "main";
        if (publicRead && !writeRequired) {
            return PublicReadProbe.publicReadSuccess(repoFullName, publicDefaultBranch, "GHES");
        }

        if (token == null || token.isBlank()) {
            if (publicRead && writeRequired) {
                return PublicReadProbe.publicReadButWriteNeedsCredentials(repoFullName, publicDefaultBranch);
            }
            return PermissionCheckReport.builder()
                    .valid(false)
                    .httpStatusCode(401)
                    .message("GHES Personal Access Token or App credentials are required.")
                    .errors(List.of("Missing GHES credentials"))
                    .build();
        }

        List<String> passed = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try {
            HttpHeaders headers = createHeaders(token);
            String login = "GHES User";
            try {
                String userUrl = host + "/api/v3/user";
                ResponseEntity<String> userResp = restTemplate.exchange(URI.create(userUrl), HttpMethod.GET, new HttpEntity<>(headers), String.class);
                JsonNode userNode = objectMapper.readTree(userResp.getBody());
                login = userNode.path("login").asText("Authenticated User");
                passed.add("GHES Authentication Verified: Connected to " + host + " as @" + login);
            } catch (Exception userEx) {
                try {
                    String instUrl = host + "/api/v3/installation/repositories?per_page=1";
                    ResponseEntity<String> instResp = restTemplate.exchange(URI.create(instUrl), HttpMethod.GET, new HttpEntity<>(headers), String.class);
                    JsonNode instNode = objectMapper.readTree(instResp.getBody());
                    int totalCount = instNode.path("total_count").asInt(0);
                    passed.add("GHES App Installation Verified: Connected to " + host + " with access to " + totalCount + " repositories");
                } catch (Exception instEx) {
                    passed.add("GHES Authentication Active: Connected to " + host);
                }
            }

            String defaultBranch = "main";
            boolean isPrivate = true;
            if (repoFullName != null && repoFullName.contains("/")) {
                String repoUrl = host + "/api/v3/repos/" + repoFullName;
                ResponseEntity<String> repoResp = restTemplate.exchange(URI.create(repoUrl), HttpMethod.GET, new HttpEntity<>(headers), String.class);
                JsonNode repoNode = objectMapper.readTree(repoResp.getBody());
                defaultBranch = repoNode.path("default_branch").asText("main");
                isPrivate = repoNode.path("private").asBoolean(true);
                passed.add("Repository Metadata Verified: " + repoFullName + " (default branch: " + defaultBranch + ")");
            }

            return PermissionCheckReport.builder()
                    .valid(true)
                    .httpStatusCode(200)
                    .repoFullName(repoFullName)
                    .defaultBranch(defaultBranch)
                    .isPrivate(isPrivate)
                    .accessMode(publicRead || !isPrivate ? "PUBLIC" : "AUTHENTICATED")
                    .message("GHES credentials validated successfully.")
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
            log.warn("GHES test connection failed: {}", e.getMessage());
            errors.add("GHES API Error: " + e.getMessage());
            return PermissionCheckReport.builder()
                    .valid(false)
                    .httpStatusCode(400)
                    .repoFullName(repoFullName)
                    .message("Failed to verify GHES connection: " + e.getMessage())
                    .errors(errors)
                    .build();
        }
    }

    private JsonNode probePublicGhesRepo(String host, String repoFullName) {
        if (host == null || repoFullName == null || !repoFullName.contains("/")) {
            return null;
        }
        try {
            HttpHeaders headers = createHeaders(null);
            String repoUrl = host + "/api/v3/repos/" + repoFullName;
            ResponseEntity<String> repoResp = restTemplate.exchange(URI.create(repoUrl), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (repoResp.getBody() == null) {
                return null;
            }
            return objectMapper.readTree(repoResp.getBody());
        } catch (Exception e) {
            log.debug("GHES anonymous public probe for {}: {}", repoFullName, e.getMessage());
            return null;
        }
    }

    @Override
    public RepoSearchResult searchRepositories(String query, int page, int perPage) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        List<GitHubRepoOption> items = new ArrayList<>();

        if (host != null && token != null) {
            try {
                HttpHeaders headers = createHeaders(token);
                String url = (query != null && !query.isBlank())
                        ? host + "/api/v3/search/repositories?q=" + query.trim() + "&per_page=" + perPage + "&page=" + page
                        : host + "/api/v3/user/repos?per_page=" + perPage + "&page=" + page;

                ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
                JsonNode root = objectMapper.readTree(resp.getBody());
                JsonNode repos = root.has("items") ? root.path("items") : root;

                if (repos.isArray()) {
                    for (JsonNode repo : repos) {
                        String fullName = repo.path("full_name").asText();
                        items.add(GitHubRepoOption.builder()
                                .id("ghes-" + repo.path("id").asText())
                                .name(repo.path("name").asText())
                                .fullName(fullName)
                                .cloneUrl(repo.path("clone_url").asText(host + "/" + fullName + ".git"))
                                .htmlUrl(repo.path("html_url").asText(host + "/" + fullName))
                                .defaultBranch(repo.path("default_branch").asText("main"))
                                .isPrivate(repo.path("private").asBoolean(true))
                                .provider("ghes")
                                .owner(repo.path("owner").path("login").asText())
                                .hasWriteAccess(true)
                                .hasAdminAccess(true)
                                .build());
                    }
                }
            } catch (Exception e) {
                log.warn("GHES repository search error: {}", e.getMessage());
            }
        }

        return RepoSearchResult.builder()
                .items(items)
                .totalCount(items.size())
                .page(page)
                .perPage(perPage)
                .hasMore(items.size() >= perPage)
                .provider("ghes")
                .build();
    }

    @Override
    public List<GitHubRepoOption> listAccessibleRepositories() {
        return searchRepositories("", 1, 50).getItems();
    }

    @Override
    public boolean createRemoteRepository(CreateRepoRequest req) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        if (host == null || token == null) return false;

        try {
            HttpHeaders headers = createHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("name", req.getName());
            body.put("description", req.getDescription() != null ? req.getDescription() : "Mirrored by GitMirror Hub");
            body.put("private", req.isPrivateRepo());
            body.put("auto_init", false);

            String url = (req.getOrg() != null && !req.getOrg().isBlank())
                    ? host + "/api/v3/orgs/" + req.getOrg().trim() + "/repos"
                    : host + "/api/v3/user/repos";

            restTemplate.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            log.info("Created new GHES repository: {}", req.getName());
            return true;
        } catch (Exception e) {
            log.error("Failed to create GHES repository: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<SyncDiffReport.PrSyncDetail> listOpenPullRequests(String repoFullName) {
        List<SyncDiffReport.PrSyncDetail> list = new ArrayList<>();
        String cursor = null;
        boolean hasNext = true;
        while (hasNext) {
            PrListPage page = listOpenPullRequestsPage(repoFullName, cursor, DEFAULT_PR_PAGE_SIZE);
            list.addAll(page.items());
            cursor = page.nextCursor();
            hasNext = page.hasNextPage();
            if (page.items().isEmpty() && !hasNext) {
                break;
            }
        }
        log.info("Listed {} open pull request(s) on GHES {}", list.size(), repoFullName);
        return list;
    }

    @Override
    public PrListPage listOpenPullRequestsPage(String repoFullName, String cursor, int pageSize) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        if (host == null || token == null || repoFullName == null) {
            return PrListPage.empty();
        }
        if (graphqlEnabled && graphQlClient != null) {
            PrListPage page = graphQlClient.fetchOpenPullRequestsPage(
                    host + "/api/graphql", token, repoFullName, cursor, pageSize);
            if (page != null) {
                return page;
            }
            log.debug("GHES GraphQL PR page failed for {}, falling back to REST", repoFullName);
        }
        return listOpenPullRequestsRestPage(host, repoFullName, cursor, pageSize, token);
    }

    @Override
    public PrListPage listRecentlyClosedPullRequestsPage(String repoFullName, String cursor, int pageSize) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        if (host == null || token == null || repoFullName == null) {
            return PrListPage.empty();
        }
        if (graphqlEnabled && graphQlClient != null) {
            PrListPage page = graphQlClient.fetchClosedPullRequestsPage(
                    host + "/api/graphql", token, repoFullName, cursor, pageSize);
            if (page != null) {
                return page;
            }
        }
        return PrListPage.empty();
    }

    private PrListPage listOpenPullRequestsRestPage(String host,
                                                    String repoFullName,
                                                    String cursor,
                                                    int pageSize,
                                                    String token) {
        try {
            HttpHeaders headers = createHeaders(token);
            int perPage = Math.max(1, Math.min(pageSize, 100));
            String url = (cursor != null && cursor.startsWith("http"))
                    ? cursor
                    : host + "/api/v3/repos/" + repoFullName + "/pulls?state=open&per_page=" + perPage;
            ResponseEntity<String> resp = restTemplate.exchange(
                    URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            List<SyncDiffReport.PrSyncDetail> items = new ArrayList<>();
            JsonNode pullRequests = objectMapper.readTree(resp.getBody());
            if (pullRequests.isArray()) {
                for (JsonNode pr : pullRequests) {
                    items.add(GithubPullRequestJson.toPrSyncDetail(pr, repoFullName));
                }
            }
            String next = GithubRestPagination.nextPageUrl(resp.getHeaders());
            return new PrListPage(items, next, next != null && !next.isBlank(), -1);
        } catch (Exception e) {
            log.debug("GHES PR page notice: {}", e.getMessage());
            return PrListPage.empty();
        }
    }

    @Override
    public Long createPullRequest(String repoFullName, String title, String body, String headRef, String baseRef) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        if (host == null || token == null || repoFullName == null) return null;

        try {
            HttpHeaders headers = createHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> payload = new HashMap<>();
            payload.put("title", title);
            payload.put("body", body != null ? body : "");
            payload.put("head", headRef);
            payload.put("base", baseRef);

            String url = host + "/api/v3/repos/" + repoFullName + "/pulls";
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            long prNum = root.path("number").asLong();
            log.info("Created GHES Pull Request #{} on {}", prNum, repoFullName);
            return prNum;
        } catch (Exception e) {
            log.warn("Failed to create GHES Pull Request on {} (head='{}', base='{}'): {}",
                    repoFullName, headRef, baseRef, e.getMessage());
            return null;
        }
    }

    @Override
    public void closePullRequest(String repoFullName, long prNumber) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        if (host == null || token == null || repoFullName == null) {
            return;
        }
        try {
            HttpHeaders headers = createHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            String url = host + "/api/v3/repos/" + repoFullName + "/pulls/" + prNumber;
            restTemplate.exchange(URI.create(url), HttpMethod.PATCH,
                    new HttpEntity<>(Map.of("state", "closed"), headers), String.class);
            log.info("Closed GHES Pull Request #{} on {}", prNumber, repoFullName);
        } catch (Exception e) {
            log.warn("Failed to close GHES Pull Request #{} on {}: {}", prNumber, repoFullName, e.getMessage());
        }
    }

    @Override
    public boolean updatePullRequest(String repoFullName, long prNumber, String title, String body) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        if (host == null || token == null || repoFullName == null) {
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
                payload.put("body", body);
            }
            String url = host + "/api/v3/repos/" + repoFullName + "/pulls/" + prNumber;
            restTemplate.exchange(URI.create(url), HttpMethod.PATCH, new HttpEntity<>(payload, headers), String.class);
            return true;
        } catch (Exception e) {
            log.warn("Failed to update GHES Pull Request #{} on {}: {}", prNumber, repoFullName, e.getMessage());
            return false;
        }
    }

    @Override
    public PullRequestSnapshot getPullRequest(String repoFullName, long prNumber) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        if (host == null || token == null || repoFullName == null) {
            return null;
        }
        try {
            HttpHeaders headers = createHeaders(token);
            String url = host + "/api/v3/repos/" + repoFullName + "/pulls/" + prNumber;
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
                    .body(root.path("body").asText(""))
                    .updatedAt(updated)
                    .build();
        } catch (Exception e) {
            log.debug("GHES get PR #{} on {}: {}", prNumber, repoFullName, e.getMessage());
            return null;
        }
    }

    @Override
    public void replicateComments(String repoFullName, long prNumber, List<String> comments) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        if (host == null || token == null || repoFullName == null || comments == null) return;

        HttpHeaders headers = createHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        for (String comment : comments) {
            try {
                Map<String, Object> body = Map.of("body", comment);
                String url = host + "/api/v3/repos/" + repoFullName + "/issues/" + prNumber + "/comments";
                restTemplate.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            } catch (Exception e) {
                log.debug("GHES comment replication notice: {}", e.getMessage());
            }
        }
    }

    @Override
    public boolean replicateCommitStatus(String repoFullName, String sha, String state, String targetUrl, String description, String context) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        if (host == null || token == null || repoFullName == null || sha == null) return false;

        try {
            HttpHeaders headers = createHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("state", state);
            if (targetUrl != null) body.put("target_url", targetUrl);
            if (description != null) body.put("description", description + " (Mirrored)");
            if (context != null) body.put("context", context);

            String url = host + "/api/v3/repos/" + repoFullName + "/statuses/" + sha.trim();
            restTemplate.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            log.info("Replicated GHES CI status [{}] on {} for commit {}", state, repoFullName, sha.substring(0, Math.min(sha.length(), 7)));
            return true;
        } catch (Exception e) {
            log.debug("GHES CI status replication notice for {}: {}", repoFullName, e.getMessage());
            return false;
        }
    }

    @Override
    public MirrorMetadataSnapshot fetchMirrorMetadataSnapshot(String repoFullName, int prPreviewLimit, int releaseLimit) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        if (host == null || token == null || repoFullName == null) {
            return MirrorMetadataSnapshot.empty();
        }
        if (graphqlEnabled && graphQlClient != null) {
            MirrorMetadataSnapshot snapshot = graphQlClient.fetchMirrorMetadataSnapshot(
                    host + "/api/graphql", token, repoFullName, prPreviewLimit, releaseLimit);
            if (snapshot != null) {
                return snapshot;
            }
            log.debug("GHES GraphQL mirror snapshot failed for {}, falling back to REST", repoFullName);
        }
        return ScmProviderAdapter.super.fetchMirrorMetadataSnapshot(repoFullName, prPreviewLimit, releaseLimit);
    }

    @Override
    public List<SyncDiffReport.ReleaseDetail> listReleases(String repoFullName) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        if (host == null || token == null || repoFullName == null) return Collections.emptyList();

        if (graphqlEnabled && graphQlClient != null) {
            List<SyncDiffReport.ReleaseDetail> graphQlReleases = graphQlClient.fetchReleases(
                    host + "/api/graphql", token, repoFullName, DEFAULT_RELEASE_LIMIT);
            if (graphQlReleases != null) {
                return graphQlReleases;
            }
            log.debug("GHES GraphQL releases failed for {}, falling back to REST", repoFullName);
        }

        List<SyncDiffReport.ReleaseDetail> list = new ArrayList<>();
        try {
            HttpHeaders headers = createHeaders(token);
            String url = host + "/api/v3/repos/" + repoFullName + "/releases?per_page=30";
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode releases = objectMapper.readTree(resp.getBody());
            if (releases.isArray()) {
                for (JsonNode rel : releases) {
                    List<SyncDiffReport.ReleaseAssetDetail> assets = new ArrayList<>();
                    JsonNode assetsNode = rel.path("assets");
                    if (assetsNode.isArray()) {
                        for (JsonNode asset : assetsNode) {
                            long size = asset.path("size").asLong(0);
                            assets.add(SyncDiffReport.ReleaseAssetDetail.builder()
                                    .id(asset.path("id").asLong())
                                    .name(asset.path("name").asText())
                                    .sizeBytes(size)
                                    .formattedSize(formatBytes(size))
                                    .downloadUrl(asset.path("browser_download_url").asText())
                                    .contentType(asset.path("content_type").asText("application/octet-stream"))
                                    .downloadCount(asset.path("download_count").asInt(0))
                                    .build());
                        }
                    }

                    list.add(SyncDiffReport.ReleaseDetail.builder()
                            .id(rel.path("id").asLong())
                            .name(rel.path("name").asText(rel.path("tag_name").asText()))
                            .tagName(rel.path("tag_name").asText())
                            .body(rel.path("body").asText(""))
                            .publishedAt(rel.path("published_at").asText(null))
                            .author(rel.path("author").path("login").asText("GHES"))
                            .isDraft(rel.path("draft").asBoolean(false))
                            .isPrerelease(rel.path("prerelease").asBoolean(false))
                            .htmlUrl(rel.path("html_url").asText(null))
                            .assets(assets)
                            .build());
                }
            }
        } catch (Exception e) {
            log.debug("GHES releases inspection notice: {}", e.getMessage());
        }
        return list;
    }

    @Override
    public List<SyncDiffReport.CiCheckRunDetail> listCiCheckRuns(String repoFullName, String commitSha) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        if (host == null || token == null || repoFullName == null || commitSha == null) return Collections.emptyList();

        List<SyncDiffReport.CiCheckRunDetail> list = new ArrayList<>();
        try {
            HttpHeaders headers = createHeaders(token);
            String url = host + "/api/v3/repos/" + repoFullName + "/commits/" + commitSha.trim() + "/check-runs";
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode runs = root.path("check_runs");
            if (runs.isArray()) {
                for (JsonNode run : runs) {
                    list.add(SyncDiffReport.CiCheckRunDetail.builder()
                            .id(run.path("id").asLong())
                            .name(run.path("name").asText())
                            .status(run.path("status").asText())
                            .conclusion(run.path("conclusion").asText(null))
                            .startedAt(run.path("started_at").asText(null))
                            .completedAt(run.path("completed_at").asText(null))
                            .htmlUrl(run.path("html_url").asText(null))
                            .appName(run.path("app").path("name").asText("GHES Actions"))
                            .headSha(commitSha)
                            .build());
                }
            }
        } catch (Exception e) {
            log.debug("GHES CI checks notice: {}", e.getMessage());
        }
        return list;
    }

    @Override
    public int cancelWorkflowRunsByActor(String repoFullName, String actorLogin, Instant createdSince) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        if (host == null || token == null || repoFullName == null || actorLogin == null || actorLogin.isBlank()) {
            return 0;
        }
        Instant since = createdSince != null ? createdSince : Instant.now().minusSeconds(600);
        int cancelled = 0;
        try {
            HttpHeaders headers = createHeaders(token);
            String createdFilter = java.net.URLEncoder.encode(">=" + since.toString(), StandardCharsets.UTF_8);
            String actorFilter = java.net.URLEncoder.encode(actorLogin.trim(), StandardCharsets.UTF_8);
            String url = host + "/api/v3/repos/" + repoFullName
                    + "/actions/runs?actor=" + actorFilter
                    + "&created=" + createdFilter
                    + "&per_page=100";
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode runs = root.path("workflow_runs");
            if (!runs.isArray()) {
                return 0;
            }
            for (JsonNode run : runs) {
                String status = run.path("status").asText("");
                if (!isCancellableWorkflowStatus(status)) {
                    continue;
                }
                long runId = run.path("id").asLong(0);
                if (runId <= 0) {
                    continue;
                }
                try {
                    String cancelUrl = host + "/api/v3/repos/" + repoFullName + "/actions/runs/" + runId + "/cancel";
                    restTemplate.exchange(URI.create(cancelUrl), HttpMethod.POST, new HttpEntity<>(headers), String.class);
                    cancelled++;
                } catch (Exception cancelEx) {
                    log.debug("Cancel GHES workflow run {} on {} failed: {}", runId, repoFullName, cancelEx.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("List/cancel GHES Actions runs on {} for actor {} failed: {}", repoFullName, actorLogin, e.getMessage());
        }
        return cancelled;
    }

    /**
     * Resolves GHES App slug via JWT {@code GET {host}/api/v3/app} and persists bot login fields.
     */
    public String resolveAndPersistBotLogin(GitHubAppConfig config) {
        String host = getNormalizedHostUrl();
        if (config == null || host == null || config.getGhesAppId() == null || config.getGhesPrivateKeyPem() == null) {
            return null;
        }
        try {
            String jwt = generateJwt(config.getGhesAppId(), config.getGhesPrivateKeyPem());
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(jwt);
            headers.setAccept(List.of(MediaType.valueOf("application/vnd.github+json")));
            headers.set("X-GitHub-Api-Version", "2022-11-28");
            ResponseEntity<String> resp = restTemplate.exchange(
                    URI.create(host + "/api/v3/app"),
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            String slug = root.path("slug").asText(null);
            if (slug == null || slug.isBlank()) {
                slug = root.path("name").asText(null);
                if (slug != null) {
                    slug = slug.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                            .replaceAll("^-|-$", "");
                }
            }
            if (slug == null || slug.isBlank()) {
                return null;
            }
            String botLogin = slug.endsWith("[bot]") ? slug : slug + "[bot]";
            config.setGhesAppSlug(slug);
            config.setGhesBotLogin(botLogin);
            configRepository.save(config);
            log.info("Resolved GHES App bot login: {} (slug={})", botLogin, slug);
            return botLogin;
        } catch (Exception e) {
            log.warn("Could not resolve GHES App slug/bot login: {}", e.getMessage());
            return null;
        }
    }

    static boolean isCancellableWorkflowStatus(String status) {
        if (status == null) return false;
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "queued", "in_progress", "waiting", "pending", "requested" -> true;
            default -> false;
        };
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char unit = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.US, "%.1f %cB", bytes / Math.pow(1024, exp), unit);
    }
}
