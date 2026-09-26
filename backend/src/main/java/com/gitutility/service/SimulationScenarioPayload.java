package com.gitutility.service;

import com.gitutility.model.dto.SimulationScenarioRequest;
import com.gitutility.model.entity.RepoMapping;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the GitHub webhook body for a simulation probe. The repository clone URL
 * is the side the event is arriving on, so the mirror treats it as that side's change.
 */
public final class SimulationScenarioPayload {

    static final String ZERO_SHA = "0000000000000000000000000000000000000000";
    static final String SENDER = "simulation-lab";

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private SimulationScenarioPayload() {
    }

    public record Built(String eventType, String json, String summary) {
    }

    public static Built build(RepoMapping mapping, SimulationScenarioRequest request) {
        if (mapping == null || request == null) {
            throw new IllegalArgumentException("A pair and a scenario are required");
        }
        String side = normalizeSide(request.getSide());
        String repoUrl = "DESTINATION".equals(side) ? mapping.getRepoBUrl() : mapping.getRepoAUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("The selected side has no repository URL");
        }
        String kind = lower(request.getKind());
        String operation = lower(request.getOperation());
        String fullName = fullName(repoUrl);
        return switch (kind) {
            case "branch", "tag", "note" -> refEvent(side, repoUrl, fullName, kind, operation, request);
            case "pull_request" -> pullRequest(side, repoUrl, fullName, operation, request);
            case "release" -> release(side, repoUrl, fullName, operation, request);
            case "status" -> status(side, repoUrl, fullName, operation, request);
            case "check_run" -> checkRun(side, repoUrl, fullName, operation, request);
            default -> throw new IllegalArgumentException("Unknown scenario kind: " + request.getKind());
        };
    }

    static String normalizeSide(String side) {
        if (side == null || side.isBlank()
                || "source".equalsIgnoreCase(side) || "a".equalsIgnoreCase(side)) {
            return "SOURCE";
        }
        if ("destination".equalsIgnoreCase(side) || "dest".equalsIgnoreCase(side)
                || "target".equalsIgnoreCase(side) || "b".equalsIgnoreCase(side)) {
            return "DESTINATION";
        }
        throw new IllegalArgumentException("Side must be source or destination");
    }

    static String fullName(String repoUrl) {
        String trimmed = repoUrl.trim().replaceAll("/+$", "").replaceAll("(?i)\\.git$", "");
        int scheme = trimmed.indexOf("://");
        String path = scheme >= 0 ? trimmed.substring(trimmed.indexOf('/', scheme + 3)) : trimmed;
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    private static Built refEvent(String side, String repoUrl, String fullName, String kind,
                                  String operation, SimulationScenarioRequest request) {
        String ref = refFor(kind, request.getRefName());
        boolean delete = "delete".equals(operation);
        String after = delete ? ZERO_SHA : sha(request.getCommitSha());
        String before = delete ? shaOr(request.getBeforeSha(), request.getCommitSha()) : shaOr(request.getBeforeSha(), ZERO_SHA);
        ObjectNode root = repoNode(repoUrl, fullName);
        root.put("ref", ref);
        root.put("before", before);
        root.put("after", after);
        root.put("deleted", delete);
        root.put("forced", false);
        ObjectNode pusher = root.putObject("pusher");
        pusher.put("name", text(request.getAuthorName(), SENDER));
        if (!delete) {
            ObjectNode head = root.putObject("head_commit");
            head.put("id", after);
            head.put("message", text(request.getCommitMessage(), "simulation " + kind + " " + ref));
        }
        String event = delete ? "delete" : "push";
        if (delete) {
            root.put("ref_type", kind);
        }
        String verb = delete ? "Delete" : "Push";
        return new Built(event, JSON.writeValueAsString(root),
                verb + " " + ref + " on the " + sideLabel(side));
    }

    private static Built pullRequest(String side, String repoUrl, String fullName, String operation,
                                     SimulationScenarioRequest request) {
        String action = switch (operation) {
            case "edit", "edited" -> "edited";
            case "close", "closed" -> "closed";
            case "merge", "merged" -> "closed";
            default -> "opened";
        };
        boolean merged = "merge".equals(operation) || "merged".equals(operation);
        long number = request.getPullRequestNumber() != null && request.getPullRequestNumber() > 0
                ? request.getPullRequestNumber() : 9001L;
        String head = text(request.getRefName(), "test/sim-probe");
        String base = text(request.getBaseBranch(), "main");
        String sha = sha(request.getCommitSha());
        ObjectNode root = repoNode(repoUrl, fullName);
        root.put("action", action);
        ObjectNode pr = root.putObject("pull_request");
        pr.put("number", number);
        pr.put("title", text(request.getTitle(), "Simulation probe"));
        pr.put("body", text(request.getBody(), "Opened from the simulation lab"));
        pr.put("state", "opened".equals(action) || "edited".equals(action) ? "open" : "closed");
        pr.put("merged", merged);
        if (merged) {
            pr.put("merge_commit_sha", sha);
        }
        ObjectNode headNode = pr.putObject("head");
        headNode.put("ref", head);
        headNode.put("sha", sha);
        ObjectNode headRepo = headNode.putObject("repo");
        headRepo.put("full_name", fullName);
        ObjectNode baseNode = pr.putObject("base");
        baseNode.put("ref", base);
        String label = switch (operation) {
            case "edit", "edited" -> "Edit";
            case "close", "closed" -> "Close";
            case "merge", "merged" -> "Merge";
            default -> "Open";
        };
        return new Built("pull_request", JSON.writeValueAsString(root),
                label + " pull request #" + number + " on the " + sideLabel(side));
    }

    private static Built release(String side, String repoUrl, String fullName, String operation,
                                 SimulationScenarioRequest request) {
        String action = switch (operation) {
            case "unpublish", "unpublished" -> "unpublished";
            case "delete", "deleted" -> "deleted";
            default -> "published";
        };
        String tag = text(request.getReleaseTag(), text(request.getRefName(), "sim-release-v1"));
        ObjectNode root = repoNode(repoUrl, fullName);
        root.put("action", action);
        ObjectNode release = root.putObject("release");
        release.put("tag_name", tag);
        release.put("name", text(request.getReleaseName(), text(request.getTitle(), "Simulation release")));
        release.put("body", text(request.getBody(), "Published from the simulation lab"));
        release.put("draft", "unpublished".equals(action));
        release.put("prerelease", false);
        String label = switch (action) {
            case "unpublished" -> "Unpublish";
            case "deleted" -> "Delete";
            default -> "Publish";
        };
        return new Built("release", JSON.writeValueAsString(root),
                label + " release " + tag + " on the " + sideLabel(side));
    }

    private static Built status(String side, String repoUrl, String fullName, String operation,
                                SimulationScenarioRequest request) {
        String state = switch (operation) {
            case "failure", "error" -> "failure";
            case "pending" -> "pending";
            default -> "success";
        };
        ObjectNode root = repoNode(repoUrl, fullName);
        root.put("sha", sha(request.getCommitSha()));
        root.put("state", state);
        root.put("context", text(request.getContext(), "simulation/status"));
        root.put("description", text(request.getCommitMessage(), "Simulation commit status"));
        root.put("target_url", "");
        return new Built("status", JSON.writeValueAsString(root),
                "Commit status " + state + " on the " + sideLabel(side));
    }

    private static Built checkRun(String side, String repoUrl, String fullName, String operation,
                                  SimulationScenarioRequest request) {
        String conclusion = "failure".equals(operation) || "error".equals(operation) ? "failure" : "success";
        ObjectNode root = repoNode(repoUrl, fullName);
        root.put("action", "completed");
        ObjectNode run = root.putObject("check_run");
        run.put("name", text(request.getContext(), "simulation/ping"));
        run.put("head_sha", sha(request.getCommitSha()));
        run.put("status", "completed");
        run.put("conclusion", conclusion);
        run.put("details_url", "");
        run.putObject("output").put("summary", text(request.getCommitMessage(), "Simulation check run"));
        return new Built("check_run", JSON.writeValueAsString(root),
                "Check run " + conclusion + " on the " + sideLabel(side));
    }

    private static ObjectNode repoNode(String repoUrl, String fullName) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode repository = root.putObject("repository");
        repository.put("full_name", fullName);
        repository.put("clone_url", repoUrl);
        ObjectNode sender = root.putObject("sender");
        sender.put("login", SENDER);
        return root;
    }

    static String refFor(String kind, String name) {
        String normalized = lower(kind);
        String safe = text(name, "branch".equals(normalized) || normalized.isBlank() ? "test/sim-probe" : "sim-probe");
        return switch (normalized) {
            case "tag" -> "refs/tags/" + safe;
            case "note" -> "refs/notes/" + safe;
            default -> "refs/heads/" + safe;
        };
    }

    private static String sideLabel(String side) {
        return "DESTINATION".equals(side) ? "destination" : "source";
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String sha(String value) {
        if (value == null || value.isBlank()) {
            String hex = Long.toHexString(System.nanoTime());
            return (hex + ZERO_SHA).substring(0, 40);
        }
        return value.trim();
    }

    private static String shaOr(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return sha(fallback);
    }
}
