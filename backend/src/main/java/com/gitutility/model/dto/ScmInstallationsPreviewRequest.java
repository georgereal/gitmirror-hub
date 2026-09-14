package com.gitutility.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * List GitHub App installations from form values (unsaved create or edit with blank PEM field).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScmInstallationsPreviewRequest {
    /** When set and {@link #privateKeyPem} is blank, reuse the stored PEM from this card. */
    private Long credentialId;
    private String provider;
    private String hostUrl;
    private String appId;
    private String privateKeyPem;
}
