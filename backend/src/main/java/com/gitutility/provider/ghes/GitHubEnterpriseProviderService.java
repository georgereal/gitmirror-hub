package com.gitutility.provider.ghes;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
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
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
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
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

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
            // Confirm the token is usable without installation-wide vanity checks in the report.
            try {
                String userUrl = host + "/api/v3/user";
                restTemplate.exchange(URI.create(userUrl), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            } catch (Exception userEx) {
                try {
                    String instUrl = host + "/api/v3/installation/repositories?per_page=1";
                    restTemplate.exchange(URI.create(instUrl), HttpMethod.GET, new HttpEntity<>(headers), String.class);
                } catch (Exception instEx) {
                    // Fall through — repo GET below will fail clearly if the token is unusable.
                }
            }

            String defaultBranch = "main";
            boolean isPrivate = true;
            boolean emptyDestination = false;
            if (repoFullName != null && repoFullName.contains("/")) {
                String repoUrl = host + "/api/v3/repos/" + repoFullName;
                ResponseEntity<String> repoResp = restTemplate.exchange(URI.create(repoUrl), HttpMethod.GET, new HttpEntity<>(headers), String.class);
                JsonNode repoNode = objectMapper.readTree(repoResp.getBody());
                defaultBranch = repoNode.path("default_branch").asText("main");
                isPrivate = repoNode.path("private").asBoolean(true);
                passed.add("Repository Metadata Verified: " + repoFullName + " (default branch: " + defaultBranch + ")");

                // Detect an empty destination so the UI can pre-announce the bulk mirror bootstrap path.
                if (writeRequired) {
                    try {
                        String refsUrl = host + "/api/v3/repos/" + repoFullName + "/git/refs?per_page=1";
                        ResponseEntity<String> refsResp = restTemplate.exchange(
                                URI.create(refsUrl), HttpMethod.GET, new HttpEntity<>(headers), String.class);
                        JsonNode refs = objectMapper.readTree(refsResp.getBody());
                        emptyDestination = refs.isArray() && refs.size() == 0;
                    } catch (Exception ignored) {
                        // Leave unknown — the sync engine re-derives blankness from the destination refs at run time.
                    }
                    if (emptyDestination) {
                        warnings.add("Destination repository is empty — the first full mirror will use "
                                + "bulk mirror bootstrap (single-connection push).");
                    }
                }
            }

            return PermissionCheckReport.builder()
                    .valid(true)
                    .httpStatusCode(200)
                    .repoFullName(repoFullName)
                    .defaultBranch(defaultBranch)
                    .isPrivate(isPrivate)
                    .emptyDestination(emptyDestination)
                    .accessMode("AUTHENTICATED")
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

        String owner = req.getOrg() != null ? req.getOrg().trim() : null;
        // Preflight: fail fast with actionable guidance when the App installation lacks Administration (write).
        Long preflightCredId = com.gitutility.service.ScmCredentialContext.currentId();
        if (preflightCredId != null) {
            scmCredentialService.assertCanCreateRepository(preflightCredId, owner);
        }

        try {
            HttpHeaders headers = createHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("name", req.getName());
            body.put("description", req.getDescription() != null ? req.getDescription() : "Mirrored by GitMirror Hub");
            body.put("private", req.isPrivateRepo());
            body.put("auto_init", false);

            boolean useOrg = owner != null && !owner.isBlank()
                    && !"User".equalsIgnoreCase(req.getAccountType());
            Long credId = com.gitutility.service.ScmCredentialContext.currentId();
            if (useOrg && credId != null && scmCredentialService != null) {
                try {
                    String t = scmCredentialService.require(credId).getAccountType();
                    if ("User".equalsIgnoreCase(t)) {
                        useOrg = false;
                    }
                } catch (Exception ignored) {
                    // keep useOrg
                }
            }
            String userReposUrl = host + "/api/v3/user/repos";
            String orgReposUrl = owner != null && !owner.isBlank()
                    ? host + "/api/v3/orgs/" + owner + "/repos"
                    : null;
            String url = useOrg && orgReposUrl != null ? orgReposUrl : userReposUrl;

            try {
                restTemplate.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
                if (orgReposUrl != null && url.equals(orgReposUrl)) {
                    log.info("GHES org create 404 for '{}'; falling back to /user/repos", owner);
                    restTemplate.exchange(URI.create(userReposUrl), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
                } else {
                    throw e;
                }
            }
            log.info("Created new GHES repository: {}", req.getName());
            return true;
        } catch (org.springframework.web.client.HttpClientErrorException.Forbidden e) {
            log.error("Failed to create GHES repository: {}", e.getMessage());
            String respBody = e.getResponseBodyAsString();
            if (respBody != null && respBody.contains("Resource not accessible by integration")) {
                throw new IllegalArgumentException(
                        "GHES rejected repository creation (403): " + com.gitutility.service.ScmCredentialService.MISSING_REPO_CREATE_PERMISSION_HINT);
            }
            throw new IllegalStateException("Failed to create GHES repository: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Failed to create GHES repository: {}", e.getMessage());
            throw new IllegalStateException("Failed to create GHES repository: " + e.getMessage(), e);
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

    // ------------------------------------------------------------------
    // Release mirror mutations (destination side)
    // ------------------------------------------------------------------

    @Override
    public boolean supportsReleaseSync() {
        return true;
    }

    @Override
    public boolean supportsCheckRunSync() {
        return true;
    }

    @Override
    public ReleaseListPage listReleasesPage(String repoFullName, String cursor, int pageSize) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        if (host == null || token == null || repoFullName == null) return ReleaseListPage.empty();

        if (graphqlEnabled && graphQlClient != null) {
            ReleaseListPage page = graphQlClient.fetchReleasesPage(
                    host + "/api/graphql", token, repoFullName, cursor, pageSize);
            if (page != null) {
                return page;
            }
            log.debug("GHES GraphQL release page failed for {}, falling back to REST", repoFullName);
        }

        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int page = 1;
        try {
            if (cursor != null && !cursor.isBlank()) {
                page = Integer.parseInt(cursor.trim());
            }
        } catch (NumberFormatException ignored) {
            page = 1;
        }
        List<SyncDiffReport.ReleaseDetail> list = new ArrayList<>();
        try {
            HttpHeaders headers = createHeaders(token);
            String url = host + "/api/v3/repos/" + repoFullName + "/releases?per_page=" + safePageSize + "&page=" + page;
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
            String linkHeader = resp.getHeaders().getFirst(HttpHeaders.LINK);
            boolean hasNext = linkHeader != null && linkHeader.contains("rel=\"next\"");
            long total = list.size();
            if (linkHeader != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("[?&]page=(\\d+)>; rel=\"last\"").matcher(linkHeader);
                if (m.find()) {
                    total = Long.parseLong(m.group(1)) * (long) safePageSize;
                }
            }
            return new ReleaseListPage(list, hasNext ? String.valueOf(page + 1) : null, hasNext, total, false);
        } catch (Exception e) {
            log.debug("GHES release page notice: {}", e.getMessage());
            return new ReleaseListPage(list, null, false, list.size(), false);
        }
    }

    @Override
    public ReleaseLookup findReleaseByTag(String repoFullName, String tagName) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        if (host == null || token == null || repoFullName == null || tagName == null || tagName.isBlank()) {
            return ReleaseLookup.missing();
        }
        try {
            HttpHeaders headers = createHeaders(token);
            String encoded = java.net.URLEncoder.encode(tagName.trim(), StandardCharsets.UTF_8);
            String url = host + "/api/v3/repos/" + repoFullName + "/releases/tags/" + encoded;
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode rel = objectMapper.readTree(resp.getBody());
            List<String> assetNames = new ArrayList<>();
            JsonNode assetsNode = rel.path("assets");
            if (assetsNode.isArray()) {
                for (JsonNode asset : assetsNode) {
                    assetNames.add(asset.path("name").asText());
                }
            }
            return new ReleaseLookup(true,
                    String.valueOf(rel.path("id").asLong()),
                    rel.path("tag_name").asText(tagName),
                    rel.path("name").asText(null),
                    rel.path("body").asText(null),
                    rel.path("draft").asBoolean(false),
                    rel.path("prerelease").asBoolean(false),
                    assetNames);
        } catch (HttpClientErrorException.NotFound ignored) {
            return ReleaseLookup.missing();
        } catch (Exception e) {
            throw new IllegalStateException("GHES release lookup failed for tag " + tagName + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String createRelease(String repoFullName, String tagName, String name, String body,
                                boolean draft, boolean prerelease) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        if (host == null || token == null || repoFullName == null || tagName == null) {
            throw new IllegalStateException("GHES release create skipped: missing host, token or tag.");
        }
        try {
            HttpHeaders headers = createHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> payload = new HashMap<>();
            payload.put("tag_name", tagName.trim());
            if (name != null) payload.put("name", name);
            if (body != null) payload.put("body", body);
            payload.put("draft", draft);
            payload.put("prerelease", prerelease);
            String url = host + "/api/v3/repos/" + repoFullName + "/releases";
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.POST,
                    new HttpEntity<>(payload, headers), String.class);
            JsonNode created = objectMapper.readTree(resp.getBody());
            String id = String.valueOf(created.path("id").asLong());
            log.info("Created GHES release '{}' on {} (id {})", tagName, repoFullName, id);
            return id;
        } catch (HttpClientErrorException e) {
            throw new IllegalStateException("GHES release create rejected (" + e.getStatusCode() + "): "
                    + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new IllegalStateException("GHES release create failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean updateRelease(String repoFullName, String externalId, String tagName, String name,
                                 String body, boolean draft, boolean prerelease) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        if (host == null || token == null || repoFullName == null || externalId == null || externalId.isBlank()) {
            return false;
        }
        try {
            HttpHeaders headers = createHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> payload = new HashMap<>();
            if (name != null) payload.put("name", name);
            if (body != null) payload.put("body", body);
            payload.put("draft", draft);
            payload.put("prerelease", prerelease);
            String url = host + "/api/v3/repos/" + repoFullName + "/releases/" + externalId.trim();
            restTemplate.exchange(URI.create(url), HttpMethod.PATCH, new HttpEntity<>(payload, headers), String.class);
            log.info("Updated GHES release {} on {} (tag {})", externalId, repoFullName, tagName);
            return true;
        } catch (HttpClientErrorException e) {
            throw new IllegalStateException("GHES release update rejected (" + e.getStatusCode() + "): "
                    + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new IllegalStateException("GHES release update failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean uploadReleaseAsset(String repoFullName, String releaseExternalId, String tagName,
                                      String assetName, String contentType, java.io.File file) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        if (host == null || token == null || repoFullName == null || releaseExternalId == null
                || file == null || !file.exists()) {
            return false;
        }
        try {
            HttpHeaders headers = createHeaders(token);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            String encodedName = java.net.URLEncoder.encode(assetName, StandardCharsets.UTF_8).replace("+", "%20");
            String url = host + "/api/uploads/repos/" + repoFullName
                    + "/releases/" + releaseExternalId.trim() + "/assets?name=" + encodedName;
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.POST,
                    new HttpEntity<>(new FileSystemResource(file), headers), String.class);
            boolean ok = resp.getStatusCode().is2xxSuccessful();
            if (ok) {
                log.info("Uploaded GHES release asset '{}' ({} bytes) to {} release {}", assetName, file.length(), repoFullName, releaseExternalId);
            }
            return ok;
        } catch (HttpClientErrorException e) {
            throw new IllegalStateException("GHES asset upload rejected (" + e.getStatusCode() + "): "
                    + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new IllegalStateException("GHES asset upload failed: " + e.getMessage(), e);
        }
    }

    @Override
    public java.io.File downloadReleaseAsset(String repoFullName, String assetDownloadUrl, String assetName) {
        String token = getEffectiveGhesToken(null);
        if (token == null || assetDownloadUrl == null || assetDownloadUrl.isBlank()) {
            return null;
        }
        java.io.File tempFile = null;
        try {
            HttpHeaders headers = createHeaders(token);
            headers.setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM));
            String safe = assetName == null ? "asset" : assetName.replaceAll("[^A-Za-z0-9._-]", "_");
            String suffix = safe.length() > 64 ? safe.substring(safe.length() - 64) : safe;
            tempFile = java.nio.file.Files.createTempFile("gitmirror-asset-", suffix).toFile();
            final java.io.File targetFile = tempFile;
            restTemplate.execute(URI.create(assetDownloadUrl), HttpMethod.GET, request -> {
                request.getHeaders().putAll(headers);
            }, response -> {
                try (java.io.InputStream in = response.getBody();
                     java.io.OutputStream out = java.nio.file.Files.newOutputStream(targetFile.toPath())) {
                    in.transferTo(out);
                }
                return targetFile;
            });
            return tempFile;
        } catch (Exception e) {
            log.debug("GHES release asset download notice for {}: {}", assetName, e.getMessage());
            if (tempFile != null) {
                try {
                    java.nio.file.Files.deleteIfExists(tempFile.toPath());
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // CI check run mirror (destination side)
    // ------------------------------------------------------------------

    @Override
    public CiCheckPage listCiCheckRunsPage(String repoFullName, String commitSha, int cursor, int pageSize) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        if (host == null || token == null || repoFullName == null || commitSha == null) return CiCheckPage.empty();

        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int page = Math.max(1, cursor <= 0 ? 1 : cursor);
        List<SyncDiffReport.CiCheckRunDetail> list = new ArrayList<>();
        try {
            HttpHeaders headers = createHeaders(token);
            String url = host + "/api/v3/repos/" + repoFullName + "/commits/" + commitSha.trim()
                    + "/check-runs?per_page=" + safePageSize + "&page=" + page;
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode runs = root.path("check_runs");
            long totalCount = root.path("total_count").asLong(0);
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
            boolean hasNext = (long) page * safePageSize < totalCount;
            return new CiCheckPage(list, hasNext ? page + 1 : 0, hasNext, totalCount);
        } catch (Exception e) {
            log.debug("GHES CI check page notice: {}", e.getMessage());
            return new CiCheckPage(list, 0, false, list.size());
        }
    }

    @Override
    public List<CommitStatusDetail> listCommitStatuses(String repoFullName, String commitSha) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        if (host == null || token == null || repoFullName == null || commitSha == null) return List.of();

        List<CommitStatusDetail> list = new ArrayList<>();
        try {
            HttpHeaders headers = createHeaders(token);
            String url = host + "/api/v3/repos/" + repoFullName + "/commits/" + commitSha.trim() + "/statuses?per_page=100";
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode statuses = objectMapper.readTree(resp.getBody());
            if (statuses.isArray()) {
                for (JsonNode st : statuses) {
                    list.add(new CommitStatusDetail(
                            st.path("context").asText(null),
                            st.path("state").asText(null),
                            st.path("target_url").asText(null),
                            st.path("description").asText(null),
                            st.path("created_at").asText(null)));
                }
            }
        } catch (Exception e) {
            log.debug("GHES commit statuses notice: {}", e.getMessage());
        }
        return list;
    }

    @Override
    public long createCheckRun(String repoFullName,
                               String commitSha,
                               String name,
                               String status,
                               String conclusion,
                               String startedAt,
                               String completedAt,
                               String detailsUrl,
                               String summary) {
        String host = getNormalizedHostUrl();
        String token = getEffectiveGhesToken(null);
        if (host == null || token == null || repoFullName == null || commitSha == null || name == null || name.isBlank()) {
            return 0L;
        }
        try {
            HttpHeaders headers = createHeaders(token);
            headers.set(HttpHeaders.ACCEPT, "application/vnd.github+json");
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("name", name.trim());
            body.put("head_sha", commitSha.trim());
            if (status != null && !status.isBlank()) body.put("status", status);
            if (conclusion != null && !conclusion.isBlank()) body.put("conclusion", conclusion);
            if (startedAt != null && !startedAt.isBlank()) body.put("started_at", startedAt);
            if (completedAt != null && !completedAt.isBlank()) body.put("completed_at", completedAt);
            if (detailsUrl != null && !detailsUrl.isBlank()) body.put("details_url", detailsUrl);
            if (conclusion != null && !conclusion.isBlank()) {
                Map<String, Object> output = new HashMap<>();
                output.put("title", name.trim() + " (mirrored)");
                output.put("summary", summary != null && !summary.isBlank() ? summary : "Mirrored CI check run.");
                body.put("output", output);
            }

            String url = host + "/api/v3/repos/" + repoFullName + "/check-runs";
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);
            JsonNode created = objectMapper.readTree(resp.getBody());
            return created.path("id").asLong(0);
        } catch (HttpClientErrorException e) {
            throw new IllegalStateException("GHES check run create rejected (" + e.getStatusCode() + "): "
                    + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new IllegalStateException("GHES check run create failed: " + e.getMessage(), e);
        }
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
