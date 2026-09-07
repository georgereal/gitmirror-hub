package com.gitutility.provider;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.junit.jupiter.api.Assertions.*;

class GithubRestPaginationTest {

    @Test
    void nextPageUrl_parsesGitHubLinkHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LINK,
                "<https://api.github.com/repos/microsoft/vscode/pulls?page=2>; rel=\"next\", "
                        + "<https://api.github.com/repos/microsoft/vscode/pulls?page=25>; rel=\"last\"");

        assertEquals("https://api.github.com/repos/microsoft/vscode/pulls?page=2",
                GithubRestPagination.nextPageUrl(headers));
    }

    @Test
    void nextPageUrl_returnsNullWhenNoNextLink() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LINK, "<https://api.github.com/repos/x/pulls?page=1>; rel=\"last\"");
        assertNull(GithubRestPagination.nextPageUrl(headers));
    }
}
