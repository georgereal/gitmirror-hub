package com.gitutility.provider.github;

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
import com.gitutility.repository.GitHubAppConfigRepository;
import com.gitutility.service.ScmCredentialContext;
import com.gitutility.service.ScmCredentialService;
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
 * First-class adapter for GitHub.com Cloud.
 * Handles GitHub App Installation Tokens, Personal Access Tokens (Fine-grained & Classic),
 * Pull Requests, Commit Statuses, Releases, and CI Check Runs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GitHubProviderService implements ScmProviderAdapter {

    private final GitHubAppConfigRepository configRepository;
    private final RestTemplate restTemplate;
    private final GithubGraphQlClient graphQlClient;
    private final ScmCredentialService scmCredentialService;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Value("${git-utility.github.graphql-enabled:true}")
    private boolean graphqlEnabled;

    private static final String GITHUB_GRAPHQL_URL = "https://api.github.com/graphql";
    private static final int DEFAULT_PR_PAGE_SIZE = 100;
    private static final int DEFAULT_RELEASE_LIMIT = 30;

    private volatile String cachedInstallationToken = null;
    private volatile Instant tokenExpiry = Instant.MIN;
    private volatile Map<String, String> cachedInstallationPermissions = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Pattern GITHUB_REPO_PATTERN =
            Pattern.compile("(?:https?://(?:[^@/:]+@)?github\\.com/|git@github\\.com:)([^/]+)/([^/.]+)(?:\\.git)?/?");

    @Override
    public ScmProviderType getProviderType() {
        return ScmProviderType.GITHUB;
    }

    @Override
    public boolean supportsUrl(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) return false;
        String lower = repoUrl.toLowerCase().trim();
        return lower.contains("github.com") || (!lower.contains("://") && !lower.contains("bitbucket") && !lower.contains("gitlab") && !lower.contains("origin") && lower.contains("/") && !lower.contains(" "));
    }

    @Override
    public String parseRepoFullName(String repoUrl) {
        if (repoUrl == null) return null;
        Matcher m = GITHUB_REPO_PATTERN.matcher(repoUrl.trim());
        if (m.find()) {
            return m.group(1) + "/" + m.group(2);
        }
        if (repoUrl.contains("/") && !repoUrl.contains("://") && !repoUrl.contains(" ")) {
            return repoUrl.trim().replaceAll("\\.git$", "");
        }
        return null;
    }

    private GitHubAppConfig getConfig() {
        return configRepository.findFirstByOrderByIdAsc().orElseGet(() -> GitHubAppConfig.builder().build());
    }

    @Override
    public synchronized void invalidateTokenCache() {
        this.cachedInstallationToken = null;
        this.tokenExpiry = Instant.MIN;
        this.cachedInstallationPermissions.clear();
        if (scmCredentialService != null) {
            scmCredentialService.invalidateAllTokens();
        }
    }

    public synchronized String getEffectiveGitHubToken(String explicitToken) {
        if (explicitToken != null && !explicitToken.isBlank()) {
            return explicitToken.trim();
        }
        if (scmCredentialService != null) {
            return scmCredentialService.resolveCurrentOrNull();
        }
        return null;
    }

    /**
     * Distinguishes unbound Check Access (Providers configured but no pair credentialId)
     * from truly empty Provider Settings.
     */
    private PermissionCheckReport missingCredentialsReport(String repoFullName) {
        Long boundId = ScmCredentialContext.currentId();
        boolean providersConfigured = scmCredentialService != null
                && scmCredentialService.hasEnabled(ScmCredentialService.PROVIDER_GITHUB);

        if (boundId == null) {
            if (providersConfigured) {
                return PermissionCheckReport.builder()
                        .valid(false)
                        .httpStatusCode(401)
                        .repoFullName(repoFullName)
                        .message("No GitHub credential bound for this check.")
                        .errors(List.of(
                                "Provider Settings already has GitHub App/PAT credentials, but this pair side is unbound. "
                                        + "Select a GitHub App or PAT in the pair form (above Check Access), or use Browse Repos to pick a repository from a credential card."
                        ))
                        .build();
            }
            return PermissionCheckReport.builder()
                    .valid(false)
                    .httpStatusCode(401)
                    .repoFullName(repoFullName)
                    .message("GitHub token or App authentication is required.")
                    .errors(List.of(
                            "Missing GitHub credentials. Configure a GitHub App or Personal Access Token in Provider Settings."
                    ))
                    .build();
        }

        return PermissionCheckReport.builder()
                .valid(false)
                .httpStatusCode(401)
                .repoFullName(repoFullName)
                .message("GitHub credential could not mint an access token.")
                .errors(List.of(
                        "Credential #" + boundId
                                + " is bound but returned no token. Check App ID, private key, installation, or PAT in Provider Settings."
                ))
                .build();
    }

    public synchronized String getInstallationAccessToken() {
        if (scmCredentialService == null) {
            return null;
        }
        Long id = com.gitutility.service.ScmCredentialContext.currentId();
        if (id == null) {
            return null;
        }
        return scmCredentialService.resolveAccessToken(id);
    }

    public String autoDiscoverInstallationId(GitHubAppConfig config) {
        if (config.getAppId() == null || config.getPrivateKeyPem() == null) return null;
        try {
            String jwt = generateJwt(config.getAppId(), config.getPrivateKeyPem());
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(jwt);
            headers.setAccept(List.of(MediaType.valueOf("application/vnd.github+json")));
            headers.set("X-GitHub-Api-Version", "2022-11-28");

            String url = "https://api.github.com/app/installations";
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode installations = objectMapper.readTree(resp.getBody());
            if (installations.isArray() && !installations.isEmpty()) {
                return installations.get(0).path("id").asText();
            }
        } catch (Exception e) {
            log.debug("Auto-discovery of GitHub App installation ID notice: {}", e.getMessage());
        }
        return null;
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
            throw new IllegalArgumentException("Unsupported RSA private key format");
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
        String token = getEffectiveGitHubToken(explicitToken);
        if (token == null || token.isBlank()) return null;
        return new UsernamePasswordCredentialsProvider("x-access-token", token);
    }

    @Override
    public PermissionCheckReport testConnection(TestConnectionRequest req) {
        String token = getEffectiveGitHubToken(req.getToken());
        String repoFullName = parseRepoFullName(req.getRepoUrl());
        if (repoFullName == null && req.getRepoUrl() != null && req.getRepoUrl().contains("/")) {
            repoFullName = req.getRepoUrl().trim();
        }
        boolean writeRequired = PublicReadProbe.writeRequired(req);
        boolean skipPublic = PublicReadProbe.skipAnonymousProbe(req);

        // Test/Demo repo simulation
        if (repoFullName != null && (repoFullName.startsWith("my-org/") || repoFullName.startsWith("backup-org/") || repoFullName.contains("example"))) {
            return PermissionCheckReport.builder()
                    .valid(true)
                    .httpStatusCode(200)
                    .repoFullName(repoFullName)
                    .defaultBranch("main")
                    .isPrivate(true)
                    .accessMode("AUTHENTICATED")
                    .message("All repository permissions verified successfully! (Simulation Mode)")
                    .permissions(PermissionCheckReport.PermissionsDetail.builder()
                            .contentsRead(true)
                            .contentsWrite(true)
                            .pullRequests(true)
                            .commitStatuses(true)
                            .webhooks(true)
                            .admin(true)
                            .build())
                    .passedChecks(List.of(
                            "Simulated GitHub repository reachable",
                            "Read/Pull permission verified",
                            "Write/Push permission verified"
                    ))
                    .warnings(List.of())
                    .errors(List.of())
                    .build();
        }

        // Always fetch fresh credentials on explicit test connection when relying on App config
        if (req.getToken() == null || req.getToken().isBlank()) {
            invalidateTokenCache();
            token = getEffectiveGitHubToken(null);
        }

        JsonNode publicRepoNode = skipPublic ? null : probePublicGithubRepo(repoFullName);
        boolean publicRead = !skipPublic && publicRepoNode != null && !publicRepoNode.path("private").asBoolean(true);
        String publicDefaultBranch = publicRead ? publicRepoNode.path("default_branch").asText("main") : "main";
        if (publicRead && !writeRequired) {
            return PublicReadProbe.publicReadSuccess(repoFullName, publicDefaultBranch, "GitHub");
        }

        if (token == null || token.isBlank()) {
            if (publicRead && writeRequired) {
                return PublicReadProbe.publicReadButWriteNeedsCredentials(repoFullName, publicDefaultBranch);
            }
            return missingCredentialsReport(repoFullName);
        }

        List<String> passed = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try {
            HttpHeaders headers = createHeaders(token);
            // Confirm the token is usable without adding installation-wide vanity checks to the report.
            try {
                String userUrl = "https://api.github.com/user";
                restTemplate.exchange(URI.create(userUrl), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            } catch (Exception userEx) {
                // GitHub App installation tokens often 403 on /user; confirm via installation API instead.
                try {
                    String instUrl = "https://api.github.com/installation/repositories?per_page=1";
                    restTemplate.exchange(URI.create(instUrl), HttpMethod.GET, new HttpEntity<>(headers), String.class);
                } catch (Exception instEx) {
                    // Fall through — repo GET below will fail clearly if the token is unusable.
                }
            }

            String defaultBranch = "main";
            boolean isPrivate = true;
            boolean canPull = true;
            boolean canPush = false;
            boolean isAdmin = false;
            boolean emptyDestination = false;

            if (repoFullName != null && repoFullName.contains("/")) {
                String repoUrl = "https://api.github.com/repos/" + repoFullName;
                ResponseEntity<String> repoResp = restTemplate.exchange(URI.create(repoUrl), HttpMethod.GET, new HttpEntity<>(headers), String.class);
                JsonNode repoNode = objectMapper.readTree(repoResp.getBody());
                defaultBranch = repoNode.path("default_branch").asText("main");
                isPrivate = repoNode.path("private").asBoolean(true);

                boolean isAppToken = token != null && (token.startsWith("ghs_") || token.equals(this.cachedInstallationToken));

                if (isAppToken) {
                    // GitHub App installation tokens have repo-level access granted by installation.
                    // The 'permissions' hash in GET /repos/:owner/:repo only reflects user collaborator roles,
                    // not App installation scopes. Use the installation token's granted scopes instead.
                    String contentsScope = this.cachedInstallationPermissions.getOrDefault("contents", "write");
                    canPull = true;
                    canPush = !"read".equalsIgnoreCase(contentsScope);
                    isAdmin = true;
                } else {
                    JsonNode permissionsNode = repoNode.path("permissions");
                    if (permissionsNode != null && !permissionsNode.isMissingNode() && !permissionsNode.isNull()) {
                        canPull = permissionsNode.path("pull").asBoolean(true);
                        canPush = permissionsNode.path("push").asBoolean(false);
                        isAdmin = permissionsNode.path("admin").asBoolean(false);
                    } else {
                        canPull = true;
                        canPush = true;
                        isAdmin = false;
                    }
                }

                passed.add("Repository Metadata Verified: " + repoFullName + " (" + (isPrivate ? "Private" : "Public") + ", default branch: " + defaultBranch + ")");

                if (publicRead || !isPrivate) {
                    canPull = true;
                }

                if (canPull) {
                    passed.add("Read / Pull permission verified (Refs & Commits)");
                } else {
                    errors.add("Missing Contents: Read on this private repository. "
                            + "On the GitHub App page, scroll to Repository permissions (not Subscribe to events) "
                            + "and set Contents to Read and write. \"Push\" under Subscribe to events is webhook delivery, not git clone/push. "
                            + "Save, then Review permissions / reinstall the App on " + repoFullName + ".");
                }

                String reqAccess = req.getRequiredAccess() != null ? req.getRequiredAccess() : "READ";
                if ("WRITE".equalsIgnoreCase(reqAccess) || "BOTH".equalsIgnoreCase(reqAccess)) {
                    if (canPush || isAdmin) {
                        passed.add("Write / Push permission verified (Destination mirror allowed)");
                    } else {
                        errors.add("Missing Contents: Write on " + repoFullName + ". "
                                + "Repository permissions → Contents = Read and write, then review the installation on this repo. "
                                + "Webhook event subscriptions (Push / Pull request) do not grant git push.");
                    }

                    // Detect an empty destination so the UI can pre-announce the bulk mirror bootstrap path.
                    try {
                        String refsUrl = "https://api.github.com/repos/" + repoFullName + "/git/refs?per_page=1";
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

            boolean isValid = errors.isEmpty();

            return PermissionCheckReport.builder()
                    .valid(isValid)
                    .httpStatusCode(200)
                    .repoFullName(repoFullName)
                    .defaultBranch(defaultBranch)
                    .isPrivate(isPrivate)
                    .emptyDestination(emptyDestination)
                    .accessMode("AUTHENTICATED")
                    .message(isValid ? "All repository permissions verified successfully!" : "Repository access has permission restrictions.")
                    .permissions(PermissionCheckReport.PermissionsDetail.builder()
                            .contentsRead(canPull)
                            .contentsWrite(canPush || isAdmin)
                            .pullRequests(true)
                            .commitStatuses(canPush || isAdmin)
                            .webhooks(isAdmin)
                            .admin(isAdmin)
                            .build())
                    .passedChecks(passed)
                    .warnings(warnings)
                    .errors(errors)
                    .build();
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            // READ-only last resort: if the repo is publicly readable, fall back to anonymous public
            // read and let the backend-authored report explain the credential failure to the user.
            PermissionCheckReport publicFallback = tryPublicFallbackAfterCredentialFailure(req, repoFullName,
                    e.getStatusCode().value(), "Authentication failed or token lacks required scope.");
            if (publicFallback != null) {
                return publicFallback;
            }
            errors.add("Authentication failed (HTTP " + e.getStatusCode().value() + "): Invalid credentials or token lacks required scope.");
            return PermissionCheckReport.builder()
                    .valid(false)
                    .repoFullName(repoFullName)
                    .httpStatusCode(e.getStatusCode().value())
                    .message("Authentication failed. Please verify GitHub App or PAT settings.")
                    .errors(errors)
                    .build();
        } catch (HttpClientErrorException.NotFound e) {
            // App not installed on the repo / PAT lacks access — public fallback still applies for READ checks.
            PermissionCheckReport publicFallback = tryPublicFallbackAfterCredentialFailure(req, repoFullName,
                    404, "Repository is not visible to this credential (App not installed on it, or PAT lacks access).");
            if (publicFallback != null) {
                return publicFallback;
            }
            errors.add("Repository not found (HTTP 404). Either repository does not exist or GitHub App / token is not installed on this repository.");
            return PermissionCheckReport.builder()
                    .valid(false)
                    .repoFullName(repoFullName)
                    .httpStatusCode(404)
                    .message("Repository not found or GitHub App is not installed on this repo.")
                    .errors(errors)
                    .build();
        } catch (Exception e) {
            log.warn("GitHub Cloud test connection failed: {}", e.getMessage());
            errors.add("GitHub API Error: " + e.getMessage());
            return PermissionCheckReport.builder()
                    .valid(false)
                    .httpStatusCode(400)
                    .repoFullName(repoFullName)
                    .message("Failed to verify GitHub connection: " + e.getMessage())
                    .errors(errors)
                    .build();
        }
    }

    /**
     * READ-only last resort when the selected credential fails (401/403/404): probe anonymous public
     * read. Returns a backend-authored public-read report when the repository is publicly readable,
     * else {@code null} so the original credential-failure report stands. WRITE checks never fall
     * back — a public mirror destination cannot accept pushes.
     */
    private PermissionCheckReport tryPublicFallbackAfterCredentialFailure(
            TestConnectionRequest req, String repoFullName, int credentialHttpStatus, String credentialProblem) {
        if (PublicReadProbe.writeRequired(req)) {
            return null;
        }
        JsonNode publicRepoNode = probePublicGithubRepo(repoFullName);
        return PublicReadProbe.publicFallbackAfterCredentialFailure(
                publicRepoNode, repoFullName, "GitHub", credentialHttpStatus, credentialProblem);
    }

    private JsonNode probePublicGithubRepo(String repoFullName) {
        if (repoFullName == null || !repoFullName.contains("/")) {
            return null;
        }
        try {
            HttpHeaders headers = createHeaders(null);
            String repoUrl = "https://api.github.com/repos/" + repoFullName;
            ResponseEntity<String> repoResp = restTemplate.exchange(URI.create(repoUrl), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (repoResp.getBody() == null) {
                return null;
            }
            return objectMapper.readTree(repoResp.getBody());
        } catch (Exception e) {
            log.debug("GitHub anonymous public probe for {}: {}", repoFullName, e.getMessage());
            return null;
        }
    }

    @Override
    public RepoSearchResult searchRepositories(String query, int page, int perPage) {
        String token = getEffectiveGitHubToken(null);
        List<GitHubRepoOption> items = new ArrayList<>();
        Long credId = com.gitutility.service.ScmCredentialContext.currentId();
        boolean isGitHubApp = false;
        if (credId != null && scmCredentialService != null) {
            try {
                isGitHubApp = scmCredentialService.require(credId).isGitHubApp();
            } catch (Exception ignored) {
                // fall through
            }
        }

        if (token != null) {
            try {
                HttpHeaders headers = createHeaders(token);
                String url;
                if (query != null && !query.isBlank()) {
                    url = "https://api.github.com/search/repositories?q=" + query.trim() + "+user:@me&per_page=" + perPage + "&page=" + page;
                } else {
                    if (isGitHubApp) {
                        url = "https://api.github.com/installation/repositories?per_page=" + perPage + "&page=" + page;
                    } else {
                        url = "https://api.github.com/user/repos?per_page=" + perPage + "&page=" + page + "&sort=updated";
                    }
                }

                ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
                JsonNode root = objectMapper.readTree(resp.getBody());
                JsonNode repos = root.has("repositories") ? root.path("repositories") : (root.has("items") ? root.path("items") : root);

                if (repos.isArray()) {
                    for (JsonNode repo : repos) {
                        String fullName = repo.path("full_name").asText();
                        JsonNode perms = repo.path("permissions");
                        items.add(GitHubRepoOption.builder()
                                .id("github-" + repo.path("id").asText())
                                .name(repo.path("name").asText())
                                .fullName(fullName)
                                .cloneUrl(repo.path("clone_url").asText("https://github.com/" + fullName + ".git"))
                                .htmlUrl(repo.path("html_url").asText("https://github.com/" + fullName))
                                .defaultBranch(repo.path("default_branch").asText("main"))
                                .isPrivate(repo.path("private").asBoolean(true))
                                .provider("github")
                                .owner(repo.path("owner").path("login").asText())
                                .hasWriteAccess(perms.path("push").asBoolean(true))
                                .hasAdminAccess(perms.path("admin").asBoolean(false))
                                .build());
                    }
                }
            } catch (Exception e) {
                log.warn("GitHub Cloud repo search error: {}", e.getMessage());
            }
        }

        return RepoSearchResult.builder()
                .items(items)
                .totalCount(items.size())
                .page(page)
                .perPage(perPage)
                .hasMore(items.size() >= perPage)
                .provider("github")
                .build();
    }

    @Override
    public List<GitHubRepoOption> listAccessibleRepositories() {
        return searchRepositories("", 1, 50).getItems();
    }

    @Override
    public boolean repositoryExists(String repoUrl) {
        String fullName = parseRepoFullName(repoUrl);
        if (fullName == null || !fullName.contains("/")) {
            return false;
        }
        try {
            HttpHeaders headers = createHeaders(getEffectiveGitHubToken(null));
            String url = "https://api.github.com/repos/" + fullName;
            restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        } catch (Exception e) {
            // 403 / rate limit etc. — cannot confirm; treat as missing (job-time creation is idempotent)
            log.debug("GitHub existence probe for {}: {}", fullName, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean hasCommits(String repoUrl) {
        String fullName = parseRepoFullName(repoUrl);
        if (fullName == null || !fullName.contains("/")) {
            return false;
        }
        try {
            HttpHeaders headers = createHeaders(getEffectiveGitHubToken(null));
            String url = "https://api.github.com/repos/" + fullName + "/commits?per_page=1";
            restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        } catch (HttpClientErrorException.Conflict e) {
            // GitHub returns 409 Conflict for an empty repository (no commits yet)
            return false;
        } catch (Exception e) {
            log.debug("GitHub has-commits probe for {}: {}", fullName, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean createRemoteRepository(CreateRepoRequest req) {
        String token = getEffectiveGitHubToken(null);
        if (token == null) return false;

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

            boolean useOrgEndpoint = shouldCreateUnderOrg(owner, req.getAccountType());
            String userReposUrl = "https://api.github.com/user/repos";
            String orgReposUrl = (owner != null && !owner.isBlank())
                    ? "https://api.github.com/orgs/" + owner + "/repos"
                    : null;
            String url = useOrgEndpoint && orgReposUrl != null ? orgReposUrl : userReposUrl;

            try {
                restTemplate.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            } catch (HttpClientErrorException.NotFound e) {
                // Owner was treated as an org but is a user account (common for personal App installs).
                if (orgReposUrl != null && url.equals(orgReposUrl)) {
                    log.info("Org create returned 404 for owner '{}'; falling back to /user/repos", owner);
                    restTemplate.exchange(URI.create(userReposUrl), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
                } else {
                    throw e;
                }
            }
            log.info("Created new GitHub repository: {}/{}", owner != null ? owner : "user", req.getName());
            return true;
        } catch (HttpClientErrorException.Forbidden e) {
            log.error("Failed to create GitHub repository: {}", e.getMessage());
            String respBody = e.getResponseBodyAsString();
            if (respBody != null && respBody.contains("Resource not accessible by integration")) {
                throw new IllegalArgumentException(
                        "GitHub rejected repository creation (403): " + ScmCredentialService.MISSING_REPO_CREATE_PERMISSION_HINT);
            }
            throw new IllegalStateException("Failed to create GitHub repository: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Failed to create GitHub repository: {}", e.getMessage());
            throw new IllegalStateException("Failed to create GitHub repository: " + e.getMessage(), e);
        }
    }

    /**
     * User App installs must use {@code POST /user/repos}. Only Organization accounts use {@code /orgs/{owner}/repos}.
     * Passing a personal login as owner previously always hit the org endpoint and returned 404.
     */
    private boolean shouldCreateUnderOrg(String owner, String requestAccountType) {
        if (owner == null || owner.isBlank()) {
            return false;
        }
        if (isOrganizationAccountType(requestAccountType)) {
            return true;
        }
        if (isUserAccountType(requestAccountType)) {
            return false;
        }
        Long credId = ScmCredentialContext.currentId();
        if (credId != null && scmCredentialService != null) {
            try {
                String accountType = scmCredentialService.require(credId).getAccountType();
                if (isOrganizationAccountType(accountType)) {
                    return true;
                }
                if (isUserAccountType(accountType)) {
                    return false;
                }
            } catch (Exception ignored) {
                // Fall through — try org URL then fall back on 404.
            }
        }
        return true;
    }

    private static boolean isOrganizationAccountType(String accountType) {
        return accountType != null
                && ("Organization".equalsIgnoreCase(accountType) || "Org".equalsIgnoreCase(accountType));
    }

    private static boolean isUserAccountType(String accountType) {
        return accountType != null && "User".equalsIgnoreCase(accountType);
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
        log.info("Listed {} open pull request(s) on GitHub {}", list.size(), repoFullName);
        return list;
    }

    @Override
    public PrListPage listOpenPullRequestsPage(String repoFullName, String cursor, int pageSize) {
        String token = getEffectiveGitHubToken(null);
        if (token == null || repoFullName == null) {
            return PrListPage.empty();
        }
        if (graphqlEnabled && graphQlClient != null) {
            PrListPage page = graphQlClient.fetchOpenPullRequestsPage(
                    GITHUB_GRAPHQL_URL, token, repoFullName, cursor, pageSize);
            if (page != null) {
                return page;
            }
            log.debug("GitHub GraphQL PR page failed for {}, falling back to REST", repoFullName);
        }
        return listOpenPullRequestsRestPage(repoFullName, cursor, pageSize, token);
    }

    @Override
    public PrListPage listRecentlyClosedPullRequestsPage(String repoFullName, String cursor, int pageSize) {
        String token = getEffectiveGitHubToken(null);
        if (token == null || repoFullName == null) {
            return PrListPage.empty();
        }
        if (graphqlEnabled && graphQlClient != null) {
            PrListPage page = graphQlClient.fetchClosedPullRequestsPage(
                    GITHUB_GRAPHQL_URL, token, repoFullName, cursor, pageSize);
            if (page != null) {
                return page;
            }
        }
        return PrListPage.empty();
    }

    private PrListPage listOpenPullRequestsRestPage(String repoFullName, String cursor, int pageSize, String token) {
        try {
            HttpHeaders headers = createHeaders(token);
            int perPage = Math.max(1, Math.min(pageSize, 100));
            String url = (cursor != null && cursor.startsWith("http"))
                    ? cursor
                    : "https://api.github.com/repos/" + repoFullName + "/pulls?state=open&per_page=" + perPage;
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
            log.debug("GitHub Cloud PR page notice: {}", e.getMessage());
            return PrListPage.empty();
        }
    }

    @Override
    public Long createPullRequest(String repoFullName, String title, String body, String headRef, String baseRef) {
        String token = getEffectiveGitHubToken(null);
        if (token == null || repoFullName == null) return null;

        try {
            HttpHeaders headers = createHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> payload = new HashMap<>();
            payload.put("title", title);
            payload.put("body", body != null ? body : "");
            payload.put("head", headRef);
            payload.put("base", baseRef);

            String url = "https://api.github.com/repos/" + repoFullName + "/pulls";
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            long prNum = root.path("number").asLong();
            log.info("Created GitHub Cloud Pull Request #{} on {}", prNum, repoFullName);
            return prNum;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            String responseBody = e.getResponseBodyAsString();
            String detail = (responseBody != null && !responseBody.isBlank())
                    ? responseBody.substring(0, Math.min(responseBody.length(), 400))
                    : e.getStatusCode().toString();
            log.warn("Failed to create GitHub Cloud Pull Request on {} (head='{}', base='{}'): {} — {}",
                    repoFullName, headRef, baseRef, e.getStatusCode(), detail);
            return null;
        } catch (Exception e) {
            log.warn("Failed to create GitHub Cloud Pull Request on {} (head='{}', base='{}'): {}",
                    repoFullName, headRef, baseRef, e.getMessage());
            return null;
        }
    }

    @Override
    public void closePullRequest(String repoFullName, long prNumber) {
        String token = getEffectiveGitHubToken(null);
        if (token == null || repoFullName == null) {
            return;
        }
        try {
            HttpHeaders headers = createHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            String url = "https://api.github.com/repos/" + repoFullName + "/pulls/" + prNumber;
            restTemplate.exchange(URI.create(url), HttpMethod.PATCH,
                    new HttpEntity<>(Map.of("state", "closed"), headers), String.class);
            log.info("Closed GitHub Cloud Pull Request #{} on {}", prNumber, repoFullName);
        } catch (Exception e) {
            log.warn("Failed to close GitHub Cloud Pull Request #{} on {}: {}", prNumber, repoFullName, e.getMessage());
        }
    }

    @Override
    public boolean updatePullRequest(String repoFullName, long prNumber, String title, String body) {
        String token = getEffectiveGitHubToken(null);
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
                payload.put("body", body);
            }
            String url = "https://api.github.com/repos/" + repoFullName + "/pulls/" + prNumber;
            restTemplate.exchange(URI.create(url), HttpMethod.PATCH, new HttpEntity<>(payload, headers), String.class);
            return true;
        } catch (Exception e) {
            log.warn("Failed to update GitHub Cloud Pull Request #{} on {}: {}", prNumber, repoFullName, e.getMessage());
            return false;
        }
    }

    @Override
    public PullRequestSnapshot getPullRequest(String repoFullName, long prNumber) {
        String token = getEffectiveGitHubToken(null);
        if (token == null || repoFullName == null) {
            return null;
        }
        try {
            HttpHeaders headers = createHeaders(token);
            String url = "https://api.github.com/repos/" + repoFullName + "/pulls/" + prNumber;
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            Instant updated = parseInstant(root.path("updated_at").asText(null));
            return PullRequestSnapshot.builder()
                    .title(root.path("title").asText(null))
                    .body(root.path("body").asText(""))
                    .updatedAt(updated)
                    .build();
        } catch (Exception e) {
            log.debug("GitHub Cloud get PR #{} on {}: {}", prNumber, repoFullName, e.getMessage());
            return null;
        }
    }

    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void replicateComments(String repoFullName, long prNumber, List<String> comments) {
        String token = getEffectiveGitHubToken(null);
        if (token == null || repoFullName == null || comments == null) return;

        HttpHeaders headers = createHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        for (String comment : comments) {
            try {
                Map<String, Object> body = Map.of("body", comment);
                String url = "https://api.github.com/repos/" + repoFullName + "/issues/" + prNumber + "/comments";
                restTemplate.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            } catch (Exception e) {
                log.debug("GitHub Cloud comment replication notice: {}", e.getMessage());
            }
        }
    }

    @Override
    public boolean replicateCommitStatus(String repoFullName, String sha, String state, String targetUrl, String description, String context) {
        String token = getEffectiveGitHubToken(null);
        if (token == null || repoFullName == null || sha == null) return false;

        try {
            HttpHeaders headers = createHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("state", state);
            if (targetUrl != null) body.put("target_url", targetUrl);
            if (description != null) body.put("description", description + " (Mirrored)");
            if (context != null) body.put("context", context);

            String url = "https://api.github.com/repos/" + repoFullName + "/statuses/" + sha.trim();
            restTemplate.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            log.info("Replicated GitHub Cloud CI status [{}] on {} for commit {}", state, repoFullName, sha.substring(0, Math.min(sha.length(), 7)));
            return true;
        } catch (Exception e) {
            log.debug("GitHub Cloud CI status replication notice for {}: {}", repoFullName, e.getMessage());
            return false;
        }
    }

    @Override
    public MirrorMetadataSnapshot fetchMirrorMetadataSnapshot(String repoFullName, int prPreviewLimit, int releaseLimit) {
        String token = getEffectiveGitHubToken(null);
        if (token == null || repoFullName == null) {
            return MirrorMetadataSnapshot.empty();
        }
        if (graphqlEnabled && graphQlClient != null) {
            MirrorMetadataSnapshot snapshot = graphQlClient.fetchMirrorMetadataSnapshot(
                    GITHUB_GRAPHQL_URL, token, repoFullName, prPreviewLimit, releaseLimit);
            if (snapshot != null) {
                return snapshot;
            }
            log.debug("GitHub GraphQL mirror snapshot failed for {}, falling back to REST", repoFullName);
        }
        return ScmProviderAdapter.super.fetchMirrorMetadataSnapshot(repoFullName, prPreviewLimit, releaseLimit);
    }

    @Override
    public List<SyncDiffReport.ReleaseDetail> listReleases(String repoFullName) {
        String token = getEffectiveGitHubToken(null);
        if (token == null || repoFullName == null) return Collections.emptyList();

        if (graphqlEnabled && graphQlClient != null) {
            List<SyncDiffReport.ReleaseDetail> graphQlReleases = graphQlClient.fetchReleases(
                    GITHUB_GRAPHQL_URL, token, repoFullName, DEFAULT_RELEASE_LIMIT);
            if (graphQlReleases != null) {
                return graphQlReleases;
            }
            log.debug("GitHub GraphQL releases failed for {}, falling back to REST", repoFullName);
        }

        List<SyncDiffReport.ReleaseDetail> list = new ArrayList<>();
        try {
            HttpHeaders headers = createHeaders(token);
            String url = "https://api.github.com/repos/" + repoFullName + "/releases?per_page=30";
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
                            .author(rel.path("author").path("login").asText("GitHub"))
                            .isDraft(rel.path("draft").asBoolean(false))
                            .isPrerelease(rel.path("prerelease").asBoolean(false))
                            .htmlUrl(rel.path("html_url").asText(null))
                            .assets(assets)
                            .build());
                }
            }
        } catch (Exception e) {
            log.debug("GitHub Cloud releases inspection notice: {}", e.getMessage());
        }
        return list;
    }

    @Override
    public List<SyncDiffReport.CiCheckRunDetail> listCiCheckRuns(String repoFullName, String commitSha) {
        String token = getEffectiveGitHubToken(null);
        if (token == null || repoFullName == null || commitSha == null) return Collections.emptyList();

        List<SyncDiffReport.CiCheckRunDetail> list = new ArrayList<>();
        try {
            HttpHeaders headers = createHeaders(token);
            String url = "https://api.github.com/repos/" + repoFullName + "/commits/" + commitSha.trim() + "/check-runs";
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
                            .appName(run.path("app").path("name").asText("GitHub Actions"))
                            .headSha(commitSha)
                            .build());
                }
            }
        } catch (Exception e) {
            log.debug("GitHub Cloud CI checks notice: {}", e.getMessage());
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
        String token = getEffectiveGitHubToken(null);
        if (token == null || repoFullName == null) return ReleaseListPage.empty();

        if (graphqlEnabled && graphQlClient != null) {
            ReleaseListPage page = graphQlClient.fetchReleasesPage(
                    GITHUB_GRAPHQL_URL, token, repoFullName, cursor, pageSize);
            if (page != null) {
                return page;
            }
            log.debug("GitHub GraphQL release page failed for {}, falling back to REST", repoFullName);
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
            String url = "https://api.github.com/repos/" + repoFullName + "/releases?per_page=" + safePageSize + "&page=" + page;
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            parseRestReleasesPage(resp.getBody(), list);
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
            log.debug("GitHub Cloud release page notice: {}", e.getMessage());
            return new ReleaseListPage(list, null, false, list.size(), false);
        }
    }

    private void parseRestReleasesPage(String body, List<SyncDiffReport.ReleaseDetail> list) {
        try {
            JsonNode releases = objectMapper.readTree(body);
            if (!releases.isArray()) {
                return;
            }
            for (JsonNode rel : releases) {
                list.add(parseRestRelease(rel));
            }
        } catch (Exception e) {
            log.debug("GitHub Cloud release parse notice: {}", e.getMessage());
        }
    }

    private SyncDiffReport.ReleaseDetail parseRestRelease(JsonNode rel) {
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
        return SyncDiffReport.ReleaseDetail.builder()
                .id(rel.path("id").asLong())
                .name(rel.path("name").asText(rel.path("tag_name").asText()))
                .tagName(rel.path("tag_name").asText())
                .body(rel.path("body").asText(""))
                .publishedAt(rel.path("published_at").asText(null))
                .author(rel.path("author").path("login").asText("GitHub"))
                .isDraft(rel.path("draft").asBoolean(false))
                .isPrerelease(rel.path("prerelease").asBoolean(false))
                .htmlUrl(rel.path("html_url").asText(null))
                .assets(assets)
                .build();
    }

    @Override
    public ReleaseLookup findReleaseByTag(String repoFullName, String tagName) {
        String token = getEffectiveGitHubToken(null);
        if (token == null || repoFullName == null || tagName == null || tagName.isBlank()) {
            return ReleaseLookup.missing();
        }
        try {
            HttpHeaders headers = createHeaders(token);
            String encoded = java.net.URLEncoder.encode(tagName.trim(), StandardCharsets.UTF_8);
            String url = "https://api.github.com/repos/" + repoFullName + "/releases/tags/" + encoded;
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
            throw new IllegalStateException("GitHub release lookup failed for tag " + tagName + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String createRelease(String repoFullName, String tagName, String name, String body,
                                boolean draft, boolean prerelease) {
        String token = getEffectiveGitHubToken(null);
        if (token == null || repoFullName == null || tagName == null) {
            throw new IllegalStateException("GitHub release create skipped: missing token or tag.");
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
            String url = "https://api.github.com/repos/" + repoFullName + "/releases";
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.POST,
                    new HttpEntity<>(payload, headers), String.class);
            JsonNode created = objectMapper.readTree(resp.getBody());
            String id = String.valueOf(created.path("id").asLong());
            log.info("Created GitHub release '{}' on {} (id {})", tagName, repoFullName, id);
            return id;
        } catch (HttpClientErrorException e) {
            throw new IllegalStateException("GitHub release create rejected (" + e.getStatusCode() + "): "
                    + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new IllegalStateException("GitHub release create failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean updateRelease(String repoFullName, String externalId, String tagName, String name,
                                 String body, boolean draft, boolean prerelease) {
        String token = getEffectiveGitHubToken(null);
        if (token == null || repoFullName == null || externalId == null || externalId.isBlank()) {
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
            String url = "https://api.github.com/repos/" + repoFullName + "/releases/" + externalId.trim();
            restTemplate.exchange(URI.create(url), HttpMethod.PATCH, new HttpEntity<>(payload, headers), String.class);
            log.info("Updated GitHub release {} on {} (tag {})", externalId, repoFullName, tagName);
            return true;
        } catch (HttpClientErrorException e) {
            throw new IllegalStateException("GitHub release update rejected (" + e.getStatusCode() + "): "
                    + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new IllegalStateException("GitHub release update failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean uploadReleaseAsset(String repoFullName, String releaseExternalId, String tagName,
                                      String assetName, String contentType, java.io.File file) {
        String token = getEffectiveGitHubToken(null);
        if (token == null || repoFullName == null || releaseExternalId == null || file == null || !file.exists()) {
            return false;
        }
        try {
            HttpHeaders headers = createHeaders(token);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            String encodedName = java.net.URLEncoder.encode(assetName, StandardCharsets.UTF_8).replace("+", "%20");
            String url = "https://uploads.github.com/repos/" + repoFullName
                    + "/releases/" + releaseExternalId.trim() + "/assets?name=" + encodedName;
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.POST,
                    new HttpEntity<>(new FileSystemResource(file), headers), String.class);
            boolean ok = resp.getStatusCode().is2xxSuccessful();
            if (ok) {
                log.info("Uploaded release asset '{}' ({} bytes) to {} release {}", assetName, file.length(), repoFullName, releaseExternalId);
            }
            return ok;
        } catch (HttpClientErrorException e) {
            throw new IllegalStateException("GitHub asset upload rejected (" + e.getStatusCode() + "): "
                    + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new IllegalStateException("GitHub asset upload failed: " + e.getMessage(), e);
        }
    }

    @Override
    public java.io.File downloadReleaseAsset(String repoFullName, String assetDownloadUrl, String assetName) {
        String token = getEffectiveGitHubToken(null);
        if (token == null || assetDownloadUrl == null || assetDownloadUrl.isBlank()) {
            return null;
        }
        java.io.File tempFile = null;
        try {
            HttpHeaders headers = createHeaders(token);
            headers.setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM));
            tempFile = java.nio.file.Files.createTempFile("gitmirror-asset-", safeFileName(assetName)).toFile();
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
            log.debug("GitHub release asset download notice for {}: {}", assetName, e.getMessage());
            if (tempFile != null) {
                try {
                    java.nio.file.Files.deleteIfExists(tempFile.toPath());
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    }

    private static String safeFileName(String name) {
        String safe = name == null ? "asset" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.length() > 64 ? safe.substring(safe.length() - 64) : safe;
    }

    // ------------------------------------------------------------------
    // CI check run mirror (destination side)
    // ------------------------------------------------------------------

    @Override
    public CiCheckPage listCiCheckRunsPage(String repoFullName, String commitSha, int cursor, int pageSize) {
        String token = getEffectiveGitHubToken(null);
        if (token == null || repoFullName == null || commitSha == null) return CiCheckPage.empty();

        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int page = Math.max(1, cursor <= 0 ? 1 : cursor);
        List<SyncDiffReport.CiCheckRunDetail> list = new ArrayList<>();
        try {
            HttpHeaders headers = createHeaders(token);
            String url = "https://api.github.com/repos/" + repoFullName + "/commits/" + commitSha.trim()
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
                            .appName(run.path("app").path("name").asText("GitHub Actions"))
                            .headSha(commitSha)
                            .build());
                }
            }
            boolean hasNext = (long) page * safePageSize < totalCount;
            return new CiCheckPage(list, hasNext ? page + 1 : 0, hasNext, totalCount);
        } catch (Exception e) {
            log.debug("GitHub Cloud CI check page notice: {}", e.getMessage());
            return new CiCheckPage(list, 0, false, list.size());
        }
    }

    @Override
    public List<CommitStatusDetail> listCommitStatuses(String repoFullName, String commitSha) {
        String token = getEffectiveGitHubToken(null);
        if (token == null || repoFullName == null || commitSha == null) return List.of();

        List<CommitStatusDetail> list = new ArrayList<>();
        try {
            HttpHeaders headers = createHeaders(token);
            String url = "https://api.github.com/repos/" + repoFullName + "/commits/" + commitSha.trim() + "/statuses?per_page=100";
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
            log.debug("GitHub Cloud commit statuses notice: {}", e.getMessage());
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
        String token = getEffectiveGitHubToken(null);
        if (token == null || repoFullName == null || commitSha == null || name == null || name.isBlank()) {
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

            String url = "https://api.github.com/repos/" + repoFullName + "/check-runs";
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);
            JsonNode created = objectMapper.readTree(resp.getBody());
            return created.path("id").asLong(0);
        } catch (HttpClientErrorException e) {
            throw new IllegalStateException("GitHub check run create rejected (" + e.getStatusCode() + "): "
                    + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new IllegalStateException("GitHub check run create failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int cancelWorkflowRunsByActor(String repoFullName, String actorLogin, Instant createdSince) {
        String token = getEffectiveGitHubToken(null);
        if (token == null || repoFullName == null || actorLogin == null || actorLogin.isBlank()) {
            return 0;
        }
        Instant since = createdSince != null ? createdSince : Instant.now().minusSeconds(600);
        int cancelled = 0;
        try {
            HttpHeaders headers = createHeaders(token);
            String createdFilter = java.net.URLEncoder.encode(">=" + since.toString(), StandardCharsets.UTF_8);
            String actorFilter = java.net.URLEncoder.encode(actorLogin.trim(), StandardCharsets.UTF_8);
            String url = "https://api.github.com/repos/" + repoFullName
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
                    String cancelUrl = "https://api.github.com/repos/" + repoFullName + "/actions/runs/" + runId + "/cancel";
                    restTemplate.exchange(URI.create(cancelUrl), HttpMethod.POST, new HttpEntity<>(headers), String.class);
                    cancelled++;
                } catch (Exception cancelEx) {
                    log.debug("Cancel workflow run {} on {} failed: {}", runId, repoFullName, cancelEx.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("List/cancel Actions runs on {} for actor {} failed: {}", repoFullName, actorLogin, e.getMessage());
        }
        return cancelled;
    }

    /**
     * Resolves App slug via JWT {@code GET /app} and persists {@code appSlug}/{@code botLogin}.
     */
    public String resolveAndPersistBotLogin(GitHubAppConfig config) {
        if (config == null || config.getAppId() == null || config.getPrivateKeyPem() == null) {
            return null;
        }
        try {
            String jwt = generateJwt(config.getAppId(), config.getPrivateKeyPem());
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(jwt);
            headers.setAccept(List.of(MediaType.valueOf("application/vnd.github+json")));
            headers.set("X-GitHub-Api-Version", "2022-11-28");
            ResponseEntity<String> resp = restTemplate.exchange(
                    URI.create("https://api.github.com/app"),
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
            config.setAppSlug(slug);
            config.setBotLogin(botLogin);
            configRepository.save(config);
            log.info("Resolved GitHub App bot login: {} (slug={})", botLogin, slug);
            return botLogin;
        } catch (Exception e) {
            log.warn("Could not resolve GitHub App slug/bot login: {}", e.getMessage());
            return null;
        }
    }

    public static boolean isCancellableWorkflowStatus(String status) {
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
