package com.gitutility.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrMirrorSupportTest {

    @Test
    void buildMirroredBody_includesAuthorLinkAndOriginalText() {
        String body = PrMirrorSupport.buildMirroredBody(
                "microsoft/vscode",
                12345,
                "octocat",
                "https://github.com/microsoft/vscode/pull/12345",
                "Fix the widget");

        assertTrue(body.contains("Fix the widget"));
        assertTrue(body.contains("**@octocat**"));
        assertTrue(body.contains("microsoft/vscode#12345"));
        assertTrue(body.contains(PrMirrorSupport.FOOTER_MARKER));
    }

    @Test
    void buildMirroredBody_doesNotDuplicateFooter() {
        String first = PrMirrorSupport.buildMirroredBody("org/repo", 1, "alice", null, "Hello");
        String second = PrMirrorSupport.buildMirroredBody("org/repo", 1, "alice", null, first);
        assertEquals(first, second);
    }

    @Test
    void discussionSummary_formatsCounts() {
        assertEquals("No discussion on source yet", PrMirrorSupport.discussionSummary(0, 0));
        assertTrue(PrMirrorSupport.discussionSummary(3, 2).contains("not replicated"));
    }
}
