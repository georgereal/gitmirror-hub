package com.gitutility.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.gitutility.model.dto.*;
import com.gitutility.model.entity.GitHubAppConfig;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.ScmCredential;
import com.gitutility.repository.GitHubAppConfigRepository;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.ScmCredentialRepository;
import com.gitutility.security.GitHubAppJwt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScmCredentialService {

    public static final String PROVIDER_GITHUB = "GITHUB";
    public static final String PROVIDER_GHES = "GITHUB_ENTERPRISE";
    public static final String MODE_APP = "GITHUB_APP";
    public static final String MODE_PAT = "PERSONAL_ACCESS_TOKEN";

    private static final Pattern OWNER_REPO = Pattern.compile(
            "(?:https?://[^/]+/|git@[^:]+:)([^/]+)/([^/.]+)(?:\\.git)?/?");

    private final ScmCredentialRepository credentialRepository;
    private final GitHubAppConfigRepository legacyConfigRepository;
    private final RepoMappingRepository mappingRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private final ConcurrentHashMap<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    private record CachedToken(String token, Instant expiresAt) {}

    public List<ScmCredential> list(String provider) {
        if (provider == null || provider.isBlank() || "ALL".equalsIgnoreCase(provider)) {
            return credentialRepository.findAll();
        }
        String norm = normalizeProvider(provider);
        return credentialRepository.findByProviderOrderByIdAsc(norm);
    }

    /** Whether any enabled credential exists for the provider (e.g. {@link #PROVIDER_GITHUB}). */
    public boolean hasEnabled(String provider) {
        String norm = normalizeProvider(provider);
        return !credentialRepository.findByProviderAndEnabledTrueOrderByIdAsc(norm).isEmpty();
    }

    public ScmCredential require(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("credentialId is required");
        }
        return credentialRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("SCM credential not found: " + id));
    }

    public ScmCredential requireEnabled(Long id) {
        ScmCredential cred = require(id);
        if (!cred.isEnabled()) {
            throw new IllegalStateException("SCM credential '" + cred.getLabel() + "' is disabled.");
        }
        return cred;
    }

    public ScmCredential create(ScmCredentialRequest req) {
        ScmCredential cred = new ScmCredential();
        applyRequest(cred, req, true);
        enrichAppMetadata(cred);
        return credentialRepository.save(cred);
    }

    public ScmCredential update(Long id, ScmCredentialRequest req) {
        ScmCredential cred = require(id);
        applyRequest(cred, req, false);
        invalidateToken(cred);
        enrichAppMetadata(cred);
        return credentialRepository.save(cred);
    }

    public void delete(Long id) {
        long used = mappingRepository.countBySourceCredentialIdOrTargetCredentialId(id, id);
        if (used > 0) {
            throw new IllegalStateException(
                    "Cannot delete credential while " + used + " pair(s) still reference it. Disable it or rebind those pairs first.");
        }
        credentialRepository.deleteById(id);
    }

    /**
     * Strict token for this credential only. App never reads PAT; PAT never mints an install token.
     */
    public String resolveAccessToken(ScmCredential cred) {
        if (cred == null) {
            return null;
        }
        if (!cred.isEnabled()) {
            throw new IllegalStateException("SCM credential '" + cred.getLabel() + "' is disabled.");
        }
        if (cred.isPat()) {
            if (cred.getPatToken() == null || cred.getPatToken().isBlank()) {
                throw new IllegalStateException("PAT credential '" + cred.getLabel() + "' has no token stored.");
            }
            return cred.getPatToken().trim();
        }
        if (cred.isGitHubApp()) {
            return mintInstallationToken(cred);
        }
        throw new IllegalStateException("Unknown authMode on credential '" + cred.getLabel() + "': " + cred.getAuthMode());
    }

    public String resolveAccessToken(Long credentialId) {
        return resolveAccessToken(requireEnabled(credentialId));
    }

    public String resolveCurrentOrNull() {
        Long id = ScmCredentialContext.currentId();
        if (id == null) {
            return null;
        }
        return resolveAccessToken(requireEnabled(id));
    }

    public List<ScmInstallationOption> listInstallations(Long credentialId) {
        ScmCredential cred = require(credentialId);
        if (!cred.isGitHubApp() || isBlank(cred.getAppId()) || isBlank(cred.getPrivateKeyPem())) {
            throw new IllegalStateException("Installations can only be listed for a GitHub App with App ID + private key.");
        }
        try {
            String jwt = GitHubAppJwt.generate(cred.getAppId(), cred.getPrivateKeyPem());
            HttpHeaders headers = appJwtHeaders(jwt);
            String url = apiRoot(cred) + "/app/installations?per_page=100";
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            List<ScmInstallationOption> out = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode n : root) {
                    out.add(ScmInstallationOption.builder()
                            .installationId(n.path("id").asText())
                            .accountLogin(n.path("account").path("login").asText(null))
                            .accountType(n.path("account").path("type").asText(null))
                            .repositorySelection(n.path("repository_selection").asText(null))
                            .htmlUrl(n.path("html_url").asText(null))
                            .build());
                }
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list App installations: " + e.getMessage(), e);
        }
    }

    public RepoSearchResult searchRepositories(Long credentialId, String query, int page, int perPage, String access) {
        ScmCredential cred = requireEnabled(credentialId);
        String token = resolveAccessToken(cred);
        int safePage = Math.max(1, page);
        int safeLimit = Math.min(50, Math.max(5, perPage));

        List<GitHubRepoOption> items = listReposWithToken(cred, token);
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<GitHubRepoOption> filtered = new ArrayList<>();
        for (GitHubRepoOption item : items) {
            item.setCredentialId(cred.getId());
            item.setProvider(cred.isEnterprise() ? "GHES" : "GITHUB");
            // Installation / user lists are already access-scoped. Do not hide repos because
            // GitHub App tokens often report permissions.pull/push as false on this endpoint.
            if (!q.isBlank()) {
                String hay = ((item.getFullName() != null ? item.getFullName() : "") + " "
                        + (item.getDescription() != null ? item.getDescription() : "")).toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) {
                    continue;
                }
            }
            filtered.add(item);
        }
        int from = Math.min((safePage - 1) * safeLimit, filtered.size());
        int to = Math.min(from + safeLimit, filtered.size());
        List<GitHubRepoOption> pageItems = filtered.subList(from, to);
        return RepoSearchResult.builder()
                .items(pageItems)
                .totalCount(filtered.size())
                .page(safePage)
                .perPage(safeLimit)
                .limit(safeLimit)
                .hasMore(to < filtered.size())
                .provider(cred.getProvider())
                .query(query)
                .build();
    }

    public PermissionCheckReport testConnection(Long credentialId, TestConnectionRequest req) {
        ScmCredential cred = requireEnabled(credentialId);
        TestConnectionRequest safeReq = req != null ? req : TestConnectionRequest.builder().build();
        safeReq.setCredentialId(credentialId);
        try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(credentialId)) {
            String token = resolveAccessToken(cred);
            safeReq.setToken(token);
            String probeUrl = safeReq.getRepoUrl();
            if (probeUrl == null || probeUrl.isBlank()) {
                HttpHeaders headers = bearerHeaders(token);
                String url = apiRoot(cred) + (cred.isPat() ? "/user" : "/installation/repositories?per_page=1");
                ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
                return PermissionCheckReport.builder()
                        .valid(resp.getStatusCode().is2xxSuccessful())
                        .httpStatusCode(resp.getStatusCode().value())
                        .accessMode("AUTHENTICATED")
                        .message("Credential '" + cred.getLabel() + "' authenticated against " + cred.getHostUrl())
                        .passedChecks(List.of("Token accepted by " + cred.getProvider()))
                        .warnings(List.of())
                        .errors(List.of())
                        .build();
            }
            assertRepoAccessible(cred, probeUrl);
            String fullName = parseOwnerRepo(probeUrl);
            return PermissionCheckReport.builder()
                    .valid(true)
                    .repoFullName(fullName)
                    .httpStatusCode(200)
                    .accessMode("AUTHENTICATED")
                    .message("Credential '" + cred.getLabel() + "' can access " + fullName)
                    .passedChecks(List.of("Owner matches bound credential", "GET /repos succeeded"))
                    .warnings(List.of())
                    .errors(List.of())
                    .build();
        } catch (HttpClientErrorException e) {
            return PermissionCheckReport.builder()
                    .valid(false)
                    .httpStatusCode(e.getStatusCode().value())
                    .message(e.getMessage())
                    .errors(List.of(e.getStatusCode() + " from " + cred.getHostUrl()))
                    .passedChecks(List.of())
                    .warnings(List.of())
                    .build();
        } catch (Exception e) {
            return PermissionCheckReport.builder()
                    .valid(false)
                    .message(e.getMessage())
                    .errors(List.of(e.getMessage() != null ? e.getMessage() : "Credential test failed"))
                    .passedChecks(List.of())
                    .warnings(List.of())
                    .build();
        }
    }

    public boolean createRemoteRepository(Long credentialId, CreateRepoRequest req) {
        ScmCredential cred = requireEnabled(credentialId);
        if (req == null) {
            req = new CreateRepoRequest();
        }
        req.setCredentialId(credentialId);
        req.inferIdentityFromUrl();
        if (req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("repository name is required to create a remote");
        }
        String token = resolveAccessToken(cred);
        try {
            HttpHeaders headers = bearerHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = Map.of(
                    "name", req.getName(),
                    "description", req.getDescription() != null ? req.getDescription() : "Mirrored by GitMirror Hub",
                    "private", req.isPrivateRepo(),
                    "auto_init", false
            );
            String org = req.getOrg() != null && !req.getOrg().isBlank() ? req.getOrg().trim() : cred.getAccountLogin();
            String url = (org != null && !org.isBlank() && !"User".equalsIgnoreCase(cred.getAccountType()))
                    ? apiRoot(cred) + "/orgs/" + org + "/repos"
                    : apiRoot(cred) + "/user/repos";
            restTemplate.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            return true;
        } catch (Exception e) {
            log.error("Failed to create repo with credential {}: {}", credentialId, e.getMessage());
            return false;
        }
    }

    public void assertRepoAccessible(ScmCredential cred, String repoUrl) {
        if (cred == null || repoUrl == null) {
            return;
        }
        String fullName = parseOwnerRepo(repoUrl);
        if (fullName == null) {
            return;
        }
        if (cred.getAccountLogin() != null && !cred.getAccountLogin().isBlank()) {
            String owner = fullName.split("/")[0];
            if (!owner.equalsIgnoreCase(cred.getAccountLogin())) {
                throw new AuthInstallationMismatchException(
                        "Repository owner '" + owner + "' does not match credential '" + cred.getLabel()
                                + "' (bound to " + cred.getAccountLogin() + "). Rebind this pair side — do not auto-switch.");
            }
        }
        try {
            String token = resolveAccessToken(cred);
            HttpHeaders headers = bearerHeaders(token);
            String url = apiRoot(cred) + "/repos/" + fullName;
            restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw new AuthInstallationMismatchException(
                        "Credential '" + cred.getLabel() + "' cannot access " + fullName
                                + " (" + e.getStatusCode() + "). The repo may have moved orgs. Rebind the pair.");
            }
            throw e;
        }
    }

    public boolean hmacMatches(ScmCredential cred, String payload, String signatureHeader) {
        if (cred == null || isBlank(cred.getWebhookSecret()) || signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        try {
            String expectedHash = signatureHeader.substring("sha256=".length()).trim();
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    cred.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String calculated = HexFormat.of().formatHex(rawHmac);
            return MessageDigest.isEqual(
                    calculated.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                    expectedHash.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.debug("HMAC compare failed: {}", e.getMessage());
            return false;
        }
    }

    public ScmCredential matchWebhookSecret(String payload, String signatureHeader, String providerHint) {
        List<ScmCredential> candidates = list(providerHint);
        for (ScmCredential cred : candidates) {
            if (hmacMatches(cred, payload, signatureHeader)) {
                return cred;
            }
        }
        return null;
    }

    public ScmCredential findByInstallation(String installationId, String provider) {
        if (isBlank(installationId)) {
            return null;
        }
        return credentialRepository.findByInstallationIdAndProvider(installationId.trim(), normalizeProvider(provider))
                .orElse(null);
    }

    public List<ScmCredential> suggestForRepoUrl(String repoUrl) {
        String owner = ownerFromUrl(repoUrl);
        String host = hostFromUrl(repoUrl);
        List<ScmCredential> out = new ArrayList<>();
        for (ScmCredential cred : credentialRepository.findByEnabledTrueOrderByIdAsc()) {
            if (host != null && !hostMatches(cred, host)) {
                continue;
            }
            if (owner != null && cred.getAccountLogin() != null
                    && owner.equalsIgnoreCase(cred.getAccountLogin())) {
                out.add(cred);
            }
        }
        return out;
    }

    public boolean hostMatchesAnyGhes(String repoUrl) {
        if (repoUrl == null) {
            return false;
        }
        String lower = repoUrl.toLowerCase(Locale.ROOT);
        if (lower.contains("github.com")) {
            return false;
        }
        for (ScmCredential cred : credentialRepository.findByProviderAndEnabledTrueOrderByIdAsc(PROVIDER_GHES)) {
            String host = hostOf(cred.getHostUrl());
            if (host != null && lower.contains(host.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public void migrateFromLegacyIfEmpty() {
        GitHubAppConfig legacy = legacyConfigRepository.findFirstByOrderByIdAsc().orElse(null);
        if (legacy == null) {
            return;
        }
        if (credentialRepository.count() == 0) {
            boolean githubApp = notBlank(legacy.getAppId()) && notBlank(legacy.getPrivateKeyPem());
            boolean githubPat = notBlank(legacy.getDefaultPatToken());
            if (githubApp) {
                ScmCredential cred = ScmCredential.builder()
                        .label(notBlank(legacy.getAppSlug()) ? legacy.getAppSlug() : "GitHub App")
                        .provider(PROVIDER_GITHUB)
                        .hostUrl("https://github.com")
                        .authMode(MODE_APP)
                        .appId(legacy.getAppId())
                        .clientId(legacy.getClientId())
                        .clientSecret(legacy.getClientSecret())
                        .privateKeyPem(legacy.getPrivateKeyPem())
                        .installationId(legacy.getInstallationId())
                        .appSlug(legacy.getAppSlug())
                        .botLogin(legacy.getBotLogin())
                        .webhookSecret(legacy.getWebhookSecret())
                        .enabled(true)
                        .build();
                enrichAppMetadata(cred);
                credentialRepository.save(cred);
            } else if (githubPat) {
                credentialRepository.save(ScmCredential.builder()
                        .label("GitHub PAT")
                        .provider(PROVIDER_GITHUB)
                        .hostUrl("https://github.com")
                        .authMode(MODE_PAT)
                        .patToken(legacy.getDefaultPatToken())
                        .webhookSecret(legacy.getWebhookSecret())
                        .enabled(true)
                        .build());
            }
            boolean ghesApp = notBlank(legacy.getGhesAppId()) && notBlank(legacy.getGhesPrivateKeyPem());
            boolean ghesPat = notBlank(legacy.getGhesPatToken());
            String ghesHost = notBlank(legacy.getGhesHostUrl()) ? trimHost(legacy.getGhesHostUrl()) : null;
            if (ghesHost != null && (ghesApp || ghesPat)) {
                ScmCredential.ScmCredentialBuilder b = ScmCredential.builder()
                        .label("GHES " + hostOf(ghesHost))
                        .provider(PROVIDER_GHES)
                        .hostUrl(ghesHost)
                        .enabled(true)
                        .webhookSecret(legacy.getGhesWebhookSecret());
                if (ghesApp) {
                    b.authMode(MODE_APP)
                            .appId(legacy.getGhesAppId())
                            .clientId(legacy.getGhesClientId())
                            .clientSecret(legacy.getGhesClientSecret())
                            .privateKeyPem(legacy.getGhesPrivateKeyPem())
                            .installationId(legacy.getGhesInstallationId())
                            .appSlug(legacy.getGhesAppSlug())
                            .botLogin(legacy.getGhesBotLogin());
                } else {
                    b.authMode(MODE_PAT).patToken(legacy.getGhesPatToken());
                }
                ScmCredential ghes = b.build();
                enrichAppMetadata(ghes);
                credentialRepository.save(ghes);
            }
            autoBindUnboundPairs();
            log.info("Migrated legacy scm_provider_configs GitHub/GHES fields into {} scm_credentials row(s)",
                    credentialRepository.count());
        }
        backfillLegacySecrets(legacy);
        // Always re-run: pairs created before credential columns / picker bind stay unbound until this.
        autoBindUnboundPairs();
    }

    /** Copy client secret (and blank webhook secret) from the god-row onto matching App cards. */
    private void backfillLegacySecrets(GitHubAppConfig legacy) {
        int filled = 0;
        for (ScmCredential cred : credentialRepository.findByProviderOrderByIdAsc(PROVIDER_GITHUB)) {
            if (!cred.isGitHubApp()) {
                continue;
            }
            if (notBlank(legacy.getAppId()) && notBlank(cred.getAppId())
                    && !legacy.getAppId().trim().equals(cred.getAppId().trim())) {
                continue;
            }
            boolean changed = false;
            if (isBlank(cred.getClientSecret()) && notBlank(legacy.getClientSecret())) {
                cred.setClientSecret(legacy.getClientSecret());
                changed = true;
            }
            if (isBlank(cred.getClientId()) && notBlank(legacy.getClientId())) {
                cred.setClientId(legacy.getClientId());
                changed = true;
            }
            if (changed) {
                credentialRepository.save(cred);
                filled++;
            }
        }
        for (ScmCredential cred : credentialRepository.findByProviderOrderByIdAsc(PROVIDER_GHES)) {
            if (!cred.isGitHubApp()) {
                continue;
            }
            boolean changed = false;
            if (isBlank(cred.getClientSecret()) && notBlank(legacy.getGhesClientSecret())) {
                cred.setClientSecret(legacy.getGhesClientSecret());
                changed = true;
            }
            if (isBlank(cred.getClientId()) && notBlank(legacy.getGhesClientId())) {
                cred.setClientId(legacy.getGhesClientId());
                changed = true;
            }
            if (changed) {
                credentialRepository.save(cred);
                filled++;
            }
        }
        if (filled > 0) {
            log.info("Backfilled client id/secret from legacy provider config onto {} scm_credentials row(s)", filled);
        }
    }

    private void autoBindUnboundPairs() {
        List<ScmCredential> github = credentialRepository.findByProviderAndEnabledTrueOrderByIdAsc(PROVIDER_GITHUB);
        List<ScmCredential> ghes = credentialRepository.findByProviderAndEnabledTrueOrderByIdAsc(PROVIDER_GHES);
        int bound = 0;
        for (RepoMapping mapping : mappingRepository.findAll()) {
            boolean changed = false;
            if (mapping.getSourceCredentialId() == null) {
                Long id = uniqueMatch(mapping.getRepoAUrl(), github, ghes);
                if (id != null) {
                    mapping.setSourceCredentialId(id);
                    changed = true;
                }
            }
            if (mapping.getTargetCredentialId() == null) {
                Long id = uniqueMatch(mapping.getRepoBUrl(), github, ghes);
                if (id != null) {
                    mapping.setTargetCredentialId(id);
                    changed = true;
                }
            }
            if (changed) {
                mappingRepository.save(mapping);
                bound++;
                log.info("Auto-bound pair '{}' to GitHub/GHES credential(s) source={} target={}",
                        mapping.getName(), mapping.getSourceCredentialId(), mapping.getTargetCredentialId());
            }
        }
        if (bound > 0) {
            log.info("Auto-bound {} unbound pair(s) to the unique matching GitHub/GHES credential", bound);
        }
    }

    /**
     * Lazily bind a single pair when there is exactly one enabled credential for that side's host.
     * Used at sync time for pairs that still lack credential ids.
     */
    public boolean ensureBoundIfUnique(RepoMapping mapping) {
        if (mapping == null) {
            return false;
        }
        List<ScmCredential> github = credentialRepository.findByProviderAndEnabledTrueOrderByIdAsc(PROVIDER_GITHUB);
        List<ScmCredential> ghes = credentialRepository.findByProviderAndEnabledTrueOrderByIdAsc(PROVIDER_GHES);
        boolean changed = false;
        if (mapping.getSourceCredentialId() == null) {
            Long id = uniqueMatch(mapping.getRepoAUrl(), github, ghes);
            if (id != null) {
                mapping.setSourceCredentialId(id);
                changed = true;
            }
        }
        if (mapping.getTargetCredentialId() == null) {
            Long id = uniqueMatch(mapping.getRepoBUrl(), github, ghes);
            if (id != null) {
                mapping.setTargetCredentialId(id);
                changed = true;
            }
        }
        if (changed) {
            mappingRepository.save(mapping);
            log.info("Lazily bound pair '{}' to credentials source={} target={}",
                    mapping.getName(), mapping.getSourceCredentialId(), mapping.getTargetCredentialId());
        }
        return changed;
    }

    private Long uniqueMatch(String url, List<ScmCredential> github, List<ScmCredential> ghes) {
        if (url == null) {
            return null;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("github.com")) {
            return github.size() == 1 ? github.get(0).getId() : null;
        }
        List<ScmCredential> hits = new ArrayList<>();
        for (ScmCredential cred : ghes) {
            String host = hostOf(cred.getHostUrl());
            if (host != null && lower.contains(host.toLowerCase(Locale.ROOT))) {
                hits.add(cred);
            }
        }
        return hits.size() == 1 ? hits.get(0).getId() : null;
    }

    public static boolean requiresGitHubOrGhesCredential(String repoUrl) {
        if (repoUrl == null) {
            return false;
        }
        String lower = repoUrl.toLowerCase(Locale.ROOT);
        if (lower.contains("gitlab") || lower.contains("bitbucket") || lower.contains("origin.cursor")) {
            return false;
        }
        return lower.contains("github.com") || lower.contains("github.");
    }

    public void requireBoundIfGithub(RepoMapping mapping) {
        if (mapping == null) {
            return;
        }
        if (isGithubOrGhesUrl(mapping.getRepoAUrl()) && mapping.getSourceCredentialId() == null) {
            throw new IllegalArgumentException(
                    "Source is GitHub/GHES — pick a repository from a credential in the picker so the pair stores sourceCredentialId.");
        }
        if (isGithubOrGhesUrl(mapping.getRepoBUrl()) && mapping.getTargetCredentialId() == null) {
            throw new IllegalArgumentException(
                    "Destination is GitHub/GHES — pick a repository from a credential in the picker so the pair stores targetCredentialId.");
        }
    }

    public static boolean isGithubOrGhesUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("gitlab") || lower.contains("bitbucket") || lower.contains("origin.cursor")) {
            return false;
        }
        return lower.contains("github.com") || (lower.contains("github.") && !lower.contains("github.com"));
    }

    private void applyRequest(ScmCredential cred, ScmCredentialRequest req, boolean creating) {
        if (req.getLabel() != null) {
            cred.setLabel(req.getLabel().trim());
        } else if (creating) {
            cred.setLabel("Untitled credential");
        }
        String provider = normalizeProvider(req.getProvider() != null ? req.getProvider() : cred.getProvider());
        if (creating && isBlank(provider)) {
            throw new IllegalArgumentException("provider is required (GITHUB or GITHUB_ENTERPRISE)");
        }
        if (req.getProvider() != null) {
            cred.setProvider(provider);
        }
        if (PROVIDER_GITHUB.equals(cred.getProvider())) {
            cred.setHostUrl("https://github.com");
        } else if (req.getHostUrl() != null) {
            cred.setHostUrl(trimHost(req.getHostUrl()));
        }
        if (creating && PROVIDER_GHES.equals(cred.getProvider()) && isBlank(cred.getHostUrl())) {
            throw new IllegalArgumentException("GHES hostUrl is required on each credential.");
        }
        String mode = req.getAuthMode() != null ? req.getAuthMode().trim().toUpperCase(Locale.ROOT) : cred.getAuthMode();
        if (creating && isBlank(mode)) {
            throw new IllegalArgumentException("authMode is required (GITHUB_APP or PERSONAL_ACCESS_TOKEN)");
        }
        if (req.getAuthMode() != null) {
            cred.setAuthMode(mode);
        }
        if (req.getAppId() != null) {
            cred.setAppId(req.getAppId().trim());
        }
        if (req.getClientId() != null) {
            cred.setClientId(req.getClientId().trim());
        }
        if (notBlank(req.getClientSecret())) {
            cred.setClientSecret(req.getClientSecret());
        }
        if (notBlank(req.getPrivateKeyPem())) {
            cred.setPrivateKeyPem(req.getPrivateKeyPem());
        }
        if (req.getInstallationId() != null) {
            cred.setInstallationId(req.getInstallationId().trim());
        }
        if (req.getWebhookSecret() != null) {
            cred.setWebhookSecret(req.getWebhookSecret());
        }
        if (notBlank(req.getPatToken())) {
            cred.setPatToken(req.getPatToken().trim());
        }
        if (req.getEnabled() != null) {
            cred.setEnabled(req.getEnabled());
        }
        if (cred.isGitHubApp()) {
            cred.setPatToken(null);
            if (isBlank(cred.getAppId()) || isBlank(cred.getPrivateKeyPem())) {
                throw new IllegalArgumentException("GitHub App credentials require App ID and private key.");
            }
            if (isBlank(cred.getInstallationId())) {
                throw new IllegalArgumentException(
                        "Pick an installation (org) for this GitHub App. The Hub will not use installations[0].");
            }
        } else if (cred.isPat()) {
            cred.setAppId(null);
            cred.setPrivateKeyPem(null);
            cred.setInstallationId(null);
            if (creating && isBlank(cred.getPatToken())) {
                throw new IllegalArgumentException("PAT credentials require a token.");
            }
        }
    }

    private void enrichAppMetadata(ScmCredential cred) {
        if (!cred.isGitHubApp() || isBlank(cred.getAppId()) || isBlank(cred.getPrivateKeyPem())) {
            return;
        }
        try {
            String jwt = GitHubAppJwt.generate(cred.getAppId(), cred.getPrivateKeyPem());
            HttpHeaders headers = appJwtHeaders(jwt);
            ResponseEntity<String> appResp = restTemplate.exchange(
                    URI.create(apiRoot(cred) + "/app"), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode app = objectMapper.readTree(appResp.getBody());
            String slug = app.path("slug").asText(null);
            if (notBlank(slug)) {
                cred.setAppSlug(slug);
                cred.setBotLogin(slug.endsWith("[bot]") ? slug : slug + "[bot]");
            }
            if (notBlank(cred.getInstallationId())) {
                ResponseEntity<String> instResp = restTemplate.exchange(
                        URI.create(apiRoot(cred) + "/app/installations/" + cred.getInstallationId().trim()),
                        HttpMethod.GET, new HttpEntity<>(headers), String.class);
                JsonNode inst = objectMapper.readTree(instResp.getBody());
                cred.setAccountLogin(inst.path("account").path("login").asText(cred.getAccountLogin()));
                cred.setAccountType(inst.path("account").path("type").asText(cred.getAccountType()));
                cred.setRepositorySelection(inst.path("repository_selection").asText(cred.getRepositorySelection()));
            }
        } catch (Exception e) {
            log.warn("Could not refresh App metadata for '{}': {}", cred.getLabel(), e.getMessage());
        }
    }

    private String mintInstallationToken(ScmCredential cred) {
        if (isBlank(cred.getAppId()) || isBlank(cred.getPrivateKeyPem())) {
            throw new IllegalStateException("GitHub App credential '" + cred.getLabel() + "' is missing App ID or private key.");
        }
        if (isBlank(cred.getInstallationId())) {
            throw new IllegalStateException(
                    "GitHub App credential '" + cred.getLabel() + "' has no installation selected. Pick the org in Settings.");
        }
        String cacheKey = cred.getProvider() + "|" + cred.getHostUrl() + "|" + cred.getInstallationId();
        CachedToken cached = tokenCache.get(cacheKey);
        if (cached != null && Instant.now().isBefore(cached.expiresAt().minusSeconds(120))) {
            return cached.token();
        }
        try {
            String jwt = GitHubAppJwt.generate(cred.getAppId(), cred.getPrivateKeyPem());
            HttpHeaders headers = appJwtHeaders(jwt);
            String url = apiRoot(cred) + "/app/installations/" + cred.getInstallationId().trim() + "/access_tokens";
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(headers), String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());
            String token = root.path("token").asText(null);
            String expiresAt = root.path("expires_at").asText(null);
            if (isBlank(token)) {
                throw new IllegalStateException("GitHub did not return an installation token for " + cred.getLabel());
            }
            Instant exp = notBlank(expiresAt) ? Instant.parse(expiresAt) : Instant.now().plusSeconds(3500);
            tokenCache.put(cacheKey, new CachedToken(token, exp));
            log.info("Acquired installation token for credential '{}' install {} (expires {})",
                    cred.getLabel(), cred.getInstallationId(), exp);
            return token;
        } catch (AuthInstallationMismatchException e) {
            throw e;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new AuthInstallationMismatchException(
                        "Installation " + cred.getInstallationId() + " was not found for App " + cred.getAppId()
                                + ". Rebind this credential.");
            }
            throw new IllegalStateException("Failed to mint installation token: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to mint installation token: " + e.getMessage(), e);
        }
    }

    private List<GitHubRepoOption> listReposWithToken(ScmCredential cred, String token) {
        List<GitHubRepoOption> items = new ArrayList<>();
        HttpHeaders headers = bearerHeaders(token);
        try {
            if (cred.isGitHubApp()) {
                String url = apiRoot(cred) + "/installation/repositories?per_page=100";
                Integer githubTotal = null;
                while (url != null) {
                    ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
                    JsonNode root = objectMapper.readTree(resp.getBody());
                    if (githubTotal == null) {
                        githubTotal = root.path("total_count").asInt(-1);
                    }
                    JsonNode repos = root.path("repositories");
                    if (repos.isArray()) {
                        for (JsonNode repo : repos) {
                            items.add(toOption(repo, cred));
                        }
                    } else if (root.has("message")) {
                        throw new IllegalStateException("GitHub repo list failed: " + root.path("message").asText());
                    }
                    url = nextLink(resp.getHeaders().getFirst("Link"));
                }
                if (items.isEmpty()) {
                    log.warn("GitHub App credential '{}' (install {}) returned 0 repositories (total_count={}). Grant repos to this App installation on GitHub.",
                            cred.getLabel(), cred.getInstallationId(), githubTotal);
                }
            } else {
                String url = apiRoot(cred) + "/user/repos?per_page=100&affiliation=owner,collaborator,organization_member";
                while (url != null) {
                    ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);
                    JsonNode repos = objectMapper.readTree(resp.getBody());
                    if (repos.isArray()) {
                        for (JsonNode repo : repos) {
                            items.add(toOption(repo, cred));
                        }
                    }
                    url = nextLink(resp.getHeaders().getFirst("Link"));
                }
            }
        } catch (Exception e) {
            log.warn("Repo list failed for credential {}: {}", cred.getId(), e.getMessage());
            throw new IllegalStateException("Failed to list repositories for '" + cred.getLabel() + "': " + e.getMessage(), e);
        }
        return items;
    }

    private GitHubRepoOption toOption(JsonNode repo, ScmCredential cred) {
        JsonNode perms = repo.path("permissions");
        boolean app = cred.isGitHubApp();
        // App installation tokens often report pull/push as false even for granted repos.
        boolean push = app || perms.path("push").asBoolean(false);
        boolean pull = app || perms.path("pull").asBoolean(true);
        boolean admin = perms.path("admin").asBoolean(false);
        return GitHubRepoOption.builder()
                .id(repo.path("id").asText())
                .name(repo.path("name").asText())
                .fullName(repo.path("full_name").asText())
                .cloneUrl(repo.path("clone_url").asText())
                .htmlUrl(repo.path("html_url").asText())
                .defaultBranch(repo.path("default_branch").asText("main"))
                .isPrivate(repo.path("private").asBoolean(true))
                .canPush(push)
                .canPull(pull)
                .isAdmin(admin)
                .hasWriteAccess(push)
                .hasAdminAccess(admin)
                .owner(repo.path("owner").path("login").asText())
                .provider(cred.isEnterprise() ? "GHES" : "GITHUB")
                .credentialId(cred.getId())
                .description(repo.path("description").asText(null))
                .build();
    }

    private String nextLink(String linkHeader) {
        if (linkHeader == null) {
            return null;
        }
        for (String part : linkHeader.split(",")) {
            if (part.contains("rel=\"next\"")) {
                int lt = part.indexOf('<');
                int gt = part.indexOf('>');
                if (lt >= 0 && gt > lt) {
                    return part.substring(lt + 1, gt);
                }
            }
        }
        return null;
    }

    public void invalidateToken(ScmCredential cred) {
        if (cred == null) {
            return;
        }
        String prefix = cred.getProvider() + "|" + cred.getHostUrl() + "|";
        tokenCache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public void invalidateAllTokens() {
        tokenCache.clear();
    }

    public static String normalizeProvider(String provider) {
        if (provider == null) {
            return PROVIDER_GITHUB;
        }
        String p = provider.trim().toUpperCase(Locale.ROOT);
        if ("GHES".equals(p) || "GITHUB_ENTERPRISE".equals(p) || "GITHUB-ENTERPRISE".equals(p)) {
            return PROVIDER_GHES;
        }
        if ("GITHUB".equals(p) || "GITHUB_CLOUD".equals(p)) {
            return PROVIDER_GITHUB;
        }
        return p;
    }

    public static String apiRoot(ScmCredential cred) {
        if (cred == null || !cred.isEnterprise()) {
            return "https://api.github.com";
        }
        return trimHost(cred.getHostUrl()) + "/api/v3";
    }

    public static String parseOwnerRepo(String repoUrl) {
        if (repoUrl == null) {
            return null;
        }
        Matcher m = OWNER_REPO.matcher(repoUrl.trim());
        if (m.find()) {
            return m.group(1) + "/" + m.group(2);
        }
        if (repoUrl.contains("/") && !repoUrl.contains("://")) {
            return repoUrl.trim().replaceAll("\\.git$", "");
        }
        return null;
    }

    public static String ownerFromUrl(String repoUrl) {
        String full = parseOwnerRepo(repoUrl);
        if (full == null) {
            return null;
        }
        int slash = full.indexOf('/');
        return slash > 0 ? full.substring(0, slash) : full;
    }

    private static String hostFromUrl(String repoUrl) {
        if (repoUrl == null) {
            return null;
        }
        String s = repoUrl.trim().toLowerCase(Locale.ROOT)
                .replaceFirst("^https?://", "")
                .replaceFirst("^git@", "")
                .replaceFirst(":.*$", "");
        int slash = s.indexOf('/');
        if (slash > 0) {
            s = s.substring(0, slash);
        }
        return s;
    }

    private static boolean hostMatches(ScmCredential cred, String host) {
        String credHost = hostOf(cred.getHostUrl());
        if (credHost == null || host == null) {
            return false;
        }
        return host.equalsIgnoreCase(credHost) || host.endsWith("." + credHost) || credHost.equalsIgnoreCase("github.com") && host.contains("github.com");
    }

    private static String hostOf(String hostUrl) {
        if (hostUrl == null) {
            return null;
        }
        return hostUrl.trim().replaceFirst("^https?://", "").replaceAll("/+$", "").split("/")[0];
    }

    private static String trimHost(String host) {
        if (host == null) {
            return null;
        }
        String h = host.trim().replaceAll("/+$", "");
        if (!h.startsWith("http")) {
            h = "https://" + h;
        }
        return h;
    }

    private static HttpHeaders appJwtHeaders(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.setAccept(List.of(MediaType.valueOf("application/vnd.github+json")));
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        return headers;
    }

    private static HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setAccept(List.of(MediaType.valueOf("application/vnd.github+json")));
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        return headers;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
