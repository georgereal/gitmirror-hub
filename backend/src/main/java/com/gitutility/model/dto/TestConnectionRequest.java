package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestConnectionRequest {
    private String repoUrl;
    private String token;
    private String requiredAccess; // "READ" or "WRITE" or "BOTH"
    /**
     * When true, skip anonymous public HTTPS probes and authenticate immediately.
     * Set from the pair's stored visibility (PRIVATE dest/source).
     */
    private Boolean knownPrivate;
    /** GitHub/GHES credential to authenticate with (no global fallback). */
    private String credentialId;
    /**
     * App installation that owns the repository. When set, the check mints that installation's
     * token. When absent on a multi-install App, the backend resolves the owning installation
     * from the repository and returns it on the report.
     */
    private String installationId;
}
