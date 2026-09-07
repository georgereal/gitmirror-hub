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
    private Long credentialId;
}
