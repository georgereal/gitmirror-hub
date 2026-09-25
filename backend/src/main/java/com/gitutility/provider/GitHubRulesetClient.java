package com.gitutility.provider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Creates or updates a GitMirror read-only ruleset on GitHub.com or GHES.
 * {@code actor_id} is the GitHub App id, not an installation id.
 */
public final class GitHubRulesetClient {

    public static final String RULESET_NAME = "gitmirror-replica-readonly";
    public static final String ORG_ALL_NAME = "gitmirror-org-readonly";
    public static final String ENTERPRISE_ALL_NAME = "gitmirror-enterprise-readonly";

    private GitHubRulesetClient() {
    }

    public static long ensure(RestTemplate restTemplate, ObjectMapper objectMapper, String apiRoot,
                              String token, String repoFullName, long appId, String enforcement) {
        String root = trimSlash(apiRoot);
        String collection = root + "/repos/" + repoFullName + "/rulesets";
        ReadonlyRulesetSpec spec = new ReadonlyRulesetSpec(
                ReadonlyRulesetSpec.KIND_REPO, ReadonlyRulesetSpec.TARGET_THIS_REPO,
                repoFullName, null, null, appId, enforcement);
        return ensureNamed(restTemplate, objectMapper, collection, token, spec, repoFullName);
    }

    public static long ensureNamed(RestTemplate restTemplate, ObjectMapper objectMapper, String collection,
                                   String token, ReadonlyRulesetSpec spec, String label) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("No GitHub token is available to manage rulesets.");
        }
        if (spec.appId() <= 0) {
            throw new IllegalStateException(
                    "Ruleset bypass needs the GitHub App id on this side's credential, not an installation id.");
        }
        String mode = "disabled".equalsIgnoreCase(spec.enforcement()) ? "disabled" : "active";
        String name = spec.rulesetName();
        HttpHeaders headers = headers(token);
        try {
            long existingId = findByName(restTemplate, objectMapper, headers, collection, name);
            Map<String, Object> body = rulesetBody(name, spec.appId(), mode, conditionsFor(spec));
            if (existingId <= 0) {
                if ("disabled".equals(mode)) {
                    return 0L;
                }
                String created = restTemplate.exchange(
                        URI.create(collection), HttpMethod.POST, new HttpEntity<>(body, headers), String.class).getBody();
                return objectMapper.readTree(created == null ? "{}" : created).path("id").asLong(0);
            }
            String updated = restTemplate.exchange(
                    URI.create(collection + "/" + existingId), HttpMethod.PUT,
                    new HttpEntity<>(body, headers), String.class).getBody();
            long id = objectMapper.readTree(updated == null ? "{}" : updated).path("id").asLong(existingId);
            return id > 0 ? id : existingId;
        } catch (HttpStatusCodeException e) {
            throw new IllegalStateException("GitHub ruleset " + mode + " failed for " + label
                    + " (" + e.getStatusCode().value() + "): " + abbreviate(e.getResponseBodyAsString()), e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("GitHub ruleset " + mode + " failed for " + label
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * @return null when the token can list the collection; otherwise a reason the caller can show.
     */
    public static String probe(RestTemplate restTemplate, String collection, String token) {
        if (token == null || token.isBlank()) {
            return "No GitHub token is available to check enterprise rulesets.";
        }
        try {
            restTemplate.exchange(
                    URI.create(collection + "?per_page=1"), HttpMethod.GET,
                    new HttpEntity<>(headers(token)), String.class);
            return null;
        } catch (HttpStatusCodeException e) {
            return "This token cannot manage enterprise rulesets (" + e.getStatusCode().value() + "): "
                    + abbreviate(e.getResponseBodyAsString());
        } catch (Exception e) {
            return "Enterprise rulesets could not be checked: " + e.getMessage();
        }
    }

    /**
     * Looks up one ruleset by name. Does not create it.
     */
    public static RulesetPresence lookup(RestTemplate restTemplate, ObjectMapper objectMapper,
                                         String collection, String token, String name) {
        if (token == null || token.isBlank()) {
            return RulesetPresence.unknown(name, "No GitHub token is available to check rulesets.");
        }
        if (name == null || name.isBlank()) {
            return RulesetPresence.unknown(name, "Ruleset name is missing.");
        }
        try {
            Set<String> seen = new LinkedHashSet<>();
            String url = collection + "?per_page=100";
            for (int page = 0; page < 5 && url != null; page++) {
                ResponseEntity<String> resp = restTemplate.exchange(
                        URI.create(url), HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
                String raw = resp.getBody();
                if (raw == null || raw.isBlank() || "null".equals(raw.trim())) {
                    url = nextLink(resp.getHeaders().getFirst("Link"));
                    continue;
                }
                JsonNode root = objectMapper.readTree(raw);
                if (root.isObject() && root.hasNonNull("message")) {
                    return RulesetPresence.unknown(name, "Could not list rulesets: " + root.path("message").asText());
                }
                JsonNode list = root.isArray() ? root : root.path("rulesets");
                if (list.isArray()) {
                    for (JsonNode node : list) {
                        String foundName = node.path("name").asText("");
                        if (!foundName.isBlank()) {
                            seen.add(foundName);
                        }
                        if (name.equals(foundName)) {
                            return RulesetPresence.found(name, node.path("id").asLong(0), node.path("enforcement").asText(null));
                        }
                    }
                }
                url = nextLink(resp.getHeaders().getFirst("Link"));
            }
            if (seen.isEmpty()) {
                return RulesetPresence.missing(name);
            }
            String listed = seen.stream().limit(8).collect(Collectors.joining(", "));
            String extra = seen.size() > 8 ? " and " + (seen.size() - 8) + " more" : "";
            return RulesetPresence.missing(name, "GitHub has " + listed + extra + ". This row looks for " + name + ".");
        } catch (HttpStatusCodeException e) {
            return RulesetPresence.unknown(name, "Could not list rulesets (" + e.getStatusCode().value() + "): "
                    + abbreviate(e.getResponseBodyAsString()));
        } catch (Exception e) {
            return RulesetPresence.unknown(name, "Could not list rulesets: " + e.getMessage());
        }
    }

    public record ListedRuleset(long id, String name, String target, String enforcement) {
    }

    public static List<ListedRuleset> listAll(RestTemplate restTemplate, ObjectMapper objectMapper,
                                              String collection, String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("No GitHub token is available to list rulesets.");
        }
        try {
            List<ListedRuleset> found = new ArrayList<>();
            String url = collection + "?per_page=100";
            for (int page = 0; page < 5 && url != null; page++) {
                ResponseEntity<String> resp = restTemplate.exchange(
                        URI.create(url), HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
                String raw = resp.getBody();
                if (raw == null || raw.isBlank() || "null".equals(raw.trim())) {
                    url = nextLink(resp.getHeaders().getFirst("Link"));
                    continue;
                }
                JsonNode root = objectMapper.readTree(raw);
                if (root.isObject() && root.hasNonNull("message")) {
                    throw new IllegalStateException(root.path("message").asText());
                }
                JsonNode list = root.isArray() ? root : root.path("rulesets");
                if (list.isArray()) {
                    for (JsonNode node : list) {
                        String name = node.path("name").asText("");
                        if (name.isBlank()) {
                            continue;
                        }
                        found.add(new ListedRuleset(
                                node.path("id").asLong(0),
                                name,
                                node.path("target").asText(""),
                                node.path("enforcement").asText("")));
                    }
                }
                url = nextLink(resp.getHeaders().getFirst("Link"));
            }
            return found;
        } catch (HttpStatusCodeException e) {
            throw new IllegalStateException("Could not list rulesets (" + e.getStatusCode().value() + "): "
                    + abbreviate(e.getResponseBodyAsString()), e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not list rulesets: " + e.getMessage(), e);
        }
    }

    public static void setEnforcement(RestTemplate restTemplate, ObjectMapper objectMapper,
                                      String collection, String token, long rulesetId, String enforcement) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("No GitHub token is available to update a ruleset.");
        }
        String mode = "active".equalsIgnoreCase(enforcement) ? "active" : "disabled";
        String url = collection + "/" + rulesetId;
        HttpHeaders httpHeaders = headers(token);
        try {
            String raw = restTemplate.exchange(
                    URI.create(url), HttpMethod.GET, new HttpEntity<>(httpHeaders), String.class).getBody();
            JsonNode current = objectMapper.readTree(raw == null ? "{}" : raw);
            if (!current.isObject()) {
                throw new IllegalStateException("GitHub did not return a ruleset object.");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            current.properties().forEach(entry -> body.put(entry.getKey(), objectMapper.convertValue(entry.getValue(), Object.class)));
            for (String key : List.of("id", "node_id", "source", "source_type", "created_at", "updated_at",
                    "_links", "current_user_can_bypass", "links")) {
                body.remove(key);
            }
            body.put("enforcement", mode);
            restTemplate.exchange(URI.create(url), HttpMethod.PUT, new HttpEntity<>(body, httpHeaders), String.class);
        } catch (HttpStatusCodeException e) {
            throw new IllegalStateException("GitHub ruleset update failed (" + e.getStatusCode().value() + "): "
                    + abbreviate(e.getResponseBodyAsString()), e);
        } catch (Exception e) {
            throw new IllegalStateException("GitHub ruleset update failed: " + e.getMessage(), e);
        }
    }

    public static String rulesetName(String kind, String target, String orgLogin, String repoShort) {
        if (ReadonlyRulesetSpec.KIND_ORG.equals(kind)) {
            if (ReadonlyRulesetSpec.TARGET_ALL_REPOS.equals(target)) {
                return ORG_ALL_NAME;
            }
            return cap("gitmirror-readonly-" + safe(repoShort));
        }
        if (ReadonlyRulesetSpec.KIND_ENTERPRISE.equals(kind)) {
            if (ReadonlyRulesetSpec.TARGET_ALL_REPOS.equals(target)) {
                return ENTERPRISE_ALL_NAME;
            }
            return cap("gitmirror-readonly-" + safe(orgLogin) + "-" + safe(repoShort));
        }
        return RULESET_NAME;
    }

    public static String collectionPath(ReadonlyRulesetSpec spec) {
        if (ReadonlyRulesetSpec.KIND_ORG.equals(spec.kind())) {
            return "/orgs/" + spec.orgLogin() + "/rulesets";
        }
        if (ReadonlyRulesetSpec.KIND_ENTERPRISE.equals(spec.kind())) {
            return "/enterprises/" + spec.enterpriseSlug() + "/rulesets";
        }
        return "/repos/" + spec.repoFullName() + "/rulesets";
    }

    static Map<String, Object> conditionsFor(ReadonlyRulesetSpec spec) {
        Map<String, Object> refName = include("~ALL");
        Map<String, Object> conditions = new LinkedHashMap<>();
        conditions.put("ref_name", refName);
        if (ReadonlyRulesetSpec.KIND_REPO.equals(spec.kind())) {
            return conditions;
        }
        boolean allRepos = ReadonlyRulesetSpec.TARGET_ALL_REPOS.equals(spec.target());
        Map<String, Object> repositoryName = include(allRepos ? "~ALL" : spec.repoShortName());
        if (!allRepos) {
            repositoryName.put("protected", true);
        }
        if (ReadonlyRulesetSpec.KIND_ENTERPRISE.equals(spec.kind())) {
            String org = allRepos ? "~ALL" : spec.orgLogin();
            conditions.put("organization_name", include(org));
        }
        conditions.put("repository_name", repositoryName);
        return conditions;
    }

    private static long findByName(RestTemplate restTemplate, ObjectMapper objectMapper, HttpHeaders headers,
                                   String collection, String name) throws Exception {
        String body = restTemplate.exchange(
                URI.create(collection + "?per_page=100"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getBody();
        JsonNode root = objectMapper.readTree(body == null ? "[]" : body);
        if (!root.isArray()) {
            return -1L;
        }
        for (JsonNode node : root) {
            if (name.equals(node.path("name").asText())) {
                return node.path("id").asLong(-1);
            }
        }
        return -1L;
    }

    private static Map<String, Object> rulesetBody(String name, long appId, String enforcement,
                                                   Map<String, Object> conditions) {
        Map<String, Object> bypass = new LinkedHashMap<>();
        bypass.put("actor_id", appId);
        bypass.put("actor_type", "Integration");
        bypass.put("bypass_mode", "always");

        List<Map<String, Object>> rules = new ArrayList<>();
        Map<String, Object> creation = new LinkedHashMap<>();
        creation.put("type", "creation");
        rules.add(creation);
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("type", "update");
        update.put("parameters", Map.of("update_allows_fetch_and_merge", false));
        rules.add(update);
        Map<String, Object> deletion = new LinkedHashMap<>();
        deletion.put("type", "deletion");
        rules.add(deletion);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("target", "branch");
        body.put("enforcement", enforcement);
        body.put("bypass_actors", List.of(bypass));
        body.put("conditions", conditions);
        body.put("rules", rules);
        return body;
    }

    private static Map<String, Object> include(String pattern) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("include", List.of(pattern));
        node.put("exclude", List.of());
        return node;
    }

    private static String nextLink(String linkHeader) {
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

    private static HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.valueOf("application/vnd.github+json")));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        headers.setBearerAuth(token.trim());
        return headers;
    }

    private static String trimSlash(String apiRoot) {
        if (apiRoot == null) {
            return "";
        }
        return apiRoot.endsWith("/") ? apiRoot.substring(0, apiRoot.length() - 1) : apiRoot;
    }

    private static String safe(String raw) {
        if (raw == null || raw.isBlank()) {
            return "repo";
        }
        return raw.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private static String cap(String name) {
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    private static String abbreviate(String body) {
        if (body == null || body.isBlank()) {
            return "no response body";
        }
        String oneLine = body.replace('\n', ' ').trim();
        return oneLine.length() > 400 ? oneLine.substring(0, 400) + "..." : oneLine;
    }
}
