package com.gitutility.service;

import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.ScmCredential;
import com.gitutility.model.enums.PairSide;
import com.gitutility.provider.ScmProviderAdapter;
import com.gitutility.provider.ScmProviderFacade;
import com.gitutility.repository.RepoMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Locks the replica of a GitHub or GHES pair with a repository ruleset, or swaps
 * which side is writable. The new replica is locked before the old one is unlocked.
 */
@Service
@RequiredArgsConstructor
public class ReplicaRulesetService {

    private final RepoMappingRepository mappingRepository;
    private final ScmProviderFacade scmProviderFacade;
    private final ScmCredentialService scmCredentialService;

    public RepoMapping apply(String mappingId, String action, String primarySide) {
        RepoMapping mapping = mappingRepository.findById(mappingId)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found: " + mappingId));
        PairSide requested = PairSide.fromString(primarySide);
        if (requested != null) {
            mapping.setPrimarySide(requested);
        }
        PairSide primary = mapping.getPrimarySide() != null ? mapping.getPrimarySide() : PairSide.A;
        String act = action == null ? "lock" : action.trim().toLowerCase();
        switch (act) {
            case "unlock" -> {
                long id = enforce(mapping, primary.opposite(), "disabled");
                mapping.setPrimarySide(primary);
                if (id > 0) {
                    mapping.setReplicaRulesetId(id);
                }
                mapping.setReplicaRulesetEnforcement("disabled");
            }
            case "swap" -> {
                long id = enforce(mapping, primary, "active");
                enforce(mapping, primary.opposite(), "disabled");
                mapping.setPrimarySide(primary.opposite());
                if (id > 0) {
                    mapping.setReplicaRulesetId(id);
                }
                mapping.setReplicaRulesetEnforcement("active");
            }
            case "lock" -> {
                long id = enforce(mapping, primary.opposite(), "active");
                mapping.setPrimarySide(primary);
                mapping.setReplicaRulesetId(id);
                mapping.setReplicaRulesetEnforcement("active");
            }
            default -> throw new IllegalArgumentException("Unknown ruleset action: " + action);
        }
        return mappingRepository.save(mapping);
    }

    /**
     * Applies a ruleset enforcement on one side. Returns the ruleset id, or 0 when the host is unreachable.
     */
    public long tryEnforce(RepoMapping mapping, PairSide side, String enforcement) {
        try {
            return enforce(mapping, side, enforcement);
        } catch (Exception e) {
            if (FailoverPeerErrors.looksUnreachable(e)) {
                return 0L;
            }
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e.getMessage(), e);
        }
    }

    private long enforce(RepoMapping mapping, PairSide side, String enforcement) {
        String url = side == PairSide.B ? mapping.getRepoBUrl() : mapping.getRepoAUrl();
        String credId = side == PairSide.B ? mapping.getTargetCredentialId() : mapping.getSourceCredentialId();
        String installationId = side == PairSide.B
                ? mapping.getTargetInstallationId() : mapping.getSourceInstallationId();
        if (credId == null || credId.isBlank()) {
            throw new IllegalStateException("Side " + side
                    + " has no SCM credential. Bind a GitHub App credential before changing the replica ruleset.");
        }
        ScmCredential cred = scmCredentialService.require(credId);
        long appId = parseAppId(cred.getAppId());
        String fullName = scmProviderFacade.parseRepoFullName(url);
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalStateException("Cannot parse repository name from " + url);
        }
        ScmProviderAdapter adapter = scmProviderFacade.getAdapterForUrl(url);
        if (adapter == null) {
            throw new IllegalStateException("No SCM provider matches " + url);
        }
        try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(credId, installationId)) {
            return adapter.ensureReplicaReadonlyRuleset(fullName, appId, enforcement);
        }
    }

    private static long parseAppId(String appId) {
        if (appId == null || appId.isBlank()) {
            throw new IllegalStateException(
                    "This credential has no GitHub App id. Ruleset bypass uses the App id, not a PAT or installation id.");
        }
        try {
            return Long.parseLong(appId.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("GitHub App id is not numeric: " + appId);
        }
    }
}
