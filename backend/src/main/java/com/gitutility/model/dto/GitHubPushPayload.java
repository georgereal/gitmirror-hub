package com.gitutility.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubPushPayload {

    private String ref;
    private String before;
    private String after;
    private boolean forced;
    private boolean deleted;

    private Repository repository;
    private Pusher pusher;
    private Sender sender;
    @JsonProperty("head_commit")
    private Commit headCommit;
    private List<Commit> commits;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Repository {
        private Long id;
        private String name;
        @JsonProperty("full_name")
        private String fullName;
        @JsonProperty("html_url")
        private String htmlUrl;
        @JsonProperty("clone_url")
        private String cloneUrl;
        @JsonProperty("git_url")
        private String gitUrl;
        @JsonProperty("ssh_url")
        private String sshUrl;
        @JsonProperty("default_branch")
        private String defaultBranch;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Pusher {
        private String name;
        private String email;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Sender {
        private String login;
        private Long id;
        @JsonProperty("avatar_url")
        private String avatarUrl;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Commit {
        private String id;
        private String message;
        private String timestamp;
        private String url;
        private Author author;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Author {
        private String name;
        private String email;
        private String username;
    }
}
