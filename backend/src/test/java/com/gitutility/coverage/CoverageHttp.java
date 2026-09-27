package com.gitutility.coverage;

import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

import static org.mockito.Mockito.mock;

/**
 * Stand-in HTTP for coverage tests. The body is chosen from the URL so parsers can run.
 */
final class CoverageHttp {

    private CoverageHttp() {
    }

    static RestTemplate ok() {
        return mock(RestTemplate.class, invocation -> respond(invocation.getArguments(), false));
    }

    static RestTemplate notFound() {
        return mock(RestTemplate.class, invocation -> respond(invocation.getArguments(), true));
    }

    private static Object respond(Object[] args, boolean missing) {
        if (missing) {
            throw HttpClientErrorException.create(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "missing",
                    org.springframework.http.HttpHeaders.EMPTY,
                    new byte[0],
                    null);
        }
        String body = bodyFor(uriOf(args));
        return ResponseEntity.ok()
                .header("Content-Type", "application/json")
                .body(body);
    }

    private static String uriOf(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof URI uri) {
                return uri.toString();
            }
            if (arg instanceof String text && (text.startsWith("http") || text.startsWith("file"))) {
                return text;
            }
            if (arg instanceof HttpMethod) {
                continue;
            }
        }
        return "";
    }

    static String bodyFor(String uri) {
        if (uri.contains("rulesets")) {
            return "[{\"id\":9,\"name\":\"gitmirror-replica-readonly\",\"enforcement\":\"active\"}]";
        }
        if (uri.contains("/pulls") || uri.contains("merge_requests") || uri.contains("/releases")
                || uri.contains("/commits") || uri.contains("/statuses") || uri.contains("check-runs")) {
            return """
                    [{"id":1,"number":1,"iid":1,"title":"Change","state":"open","tag_name":"v1","name":"v1",
                      "head":{"ref":"feature","sha":"abc"},"base":{"ref":"main"},
                      "source_branch":"feature","target_branch":"main","draft":false,"prerelease":false}]
                    """;
        }
        if (uri.contains("/search/")) {
            return """
                    {"items":[{"id":1,"full_name":"acme/widget","name":"widget","private":false,
                      "clone_url":"https://github.com/acme/widget.git","default_branch":"main"}]}
                    """;
        }
        if (uri.contains("graphql")) {
            return "{\"data\":{\"repository\":{}}}";
        }
        return """
                {"id":1,"full_name":"acme/widget","name":"widget","default_branch":"main","private":false,
                 "visibility":"public","permissions":{"admin":true,"push":true,"pull":true}}
                """;
    }
}
