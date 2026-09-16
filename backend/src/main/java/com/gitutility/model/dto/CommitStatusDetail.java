package com.gitutility.model.dto;

/** A commit status (legacy CI status) on a commit. */
public record CommitStatusDetail(
        String context,
        String state,
        String targetUrl,
        String description,
        String createdAt) {
}