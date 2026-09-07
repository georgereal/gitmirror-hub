package com.gitutility.provider;

import org.springframework.http.HttpHeaders;

import java.util.List;

/** Parses GitHub / GHES REST {@code Link} response headers for cursor pagination. */
public final class GithubRestPagination {

    private GithubRestPagination() {
    }

    public static String nextPageUrl(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        List<String> linkHeaders = headers.get(HttpHeaders.LINK);
        if (linkHeaders == null) {
            return null;
        }
        for (String header : linkHeaders) {
            if (header == null || header.isBlank()) {
                continue;
            }
            for (String part : header.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.contains("rel=\"next\"")) {
                    continue;
                }
                int start = trimmed.indexOf('<');
                int end = trimmed.indexOf('>');
                if (start >= 0 && end > start) {
                    return trimmed.substring(start + 1, end);
                }
            }
        }
        return null;
    }
}
