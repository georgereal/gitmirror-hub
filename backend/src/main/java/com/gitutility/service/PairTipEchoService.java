package com.gitutility.service;

import com.gitutility.model.entity.RepoMapping;
import com.gitutility.provider.ScmProviderFacade;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * An inbound push or delete is an echo only when the other repository already has that result.
 * A ledger row or a pull-request flag is not that check.
 */
@Service
@Slf4j
public class PairTipEchoService {

    /** Lookup succeeded. {@code sha} is null when the peer does not advertise the ref. */
    public record PeerTip(boolean known, String sha) {
        public static PeerTip unknown() {
            return new PeerTip(false, null);
        }

        public boolean present() {
            return known && sha != null && !sha.isBlank();
        }
    }

    private final ScmProviderFacade scmProviderFacade;
    private final ScmCredentialService scmCredentialService;

    public PairTipEchoService(ScmProviderFacade scmProviderFacade, ScmCredentialService scmCredentialService) {
        this.scmProviderFacade = scmProviderFacade;
        this.scmCredentialService = scmCredentialService;
    }

    public boolean pushEcho(RepoMapping mapping, boolean inboundOnDest, String ref, String sha) {
        PeerTip peer = lookup(mapping, inboundOnDest, ref);
        return pushIsEcho(peer.known(), peer.sha(), sha);
    }

    public boolean deleteEcho(RepoMapping mapping, boolean inboundOnDest, String ref) {
        PeerTip peer = lookup(mapping, inboundOnDest, ref);
        return deleteIsEcho(peer.known(), peer.present());
    }

    /** True only when the peer advertises this exact tip. An unknown peer is not an echo. */
    public static boolean pushIsEcho(boolean known, String peerSha, String incomingSha) {
        if (!known || peerSha == null || peerSha.isBlank() || incomingSha == null || incomingSha.isBlank()) {
            return false;
        }
        if (RefOriginService.isDeletedSha(incomingSha)) {
            return false;
        }
        return peerSha.equalsIgnoreCase(incomingSha.trim());
    }

    /** True only when the lookup succeeded and the peer ref is already gone. */
    public static boolean deleteIsEcho(boolean known, boolean peerPresent) {
        return known && !peerPresent;
    }

    public PeerTip lookup(RepoMapping mapping, boolean inboundOnDest, String ref) {
        return lookupSide(mapping, !inboundOnDest, ref);
    }

    /** Tip advertised on the chosen side. {@code destination} selects repository B. */
    public PeerTip lookupSide(RepoMapping mapping, boolean destination, String ref) {
        if (mapping == null || ref == null || ref.isBlank()) {
            return PeerTip.unknown();
        }
        String repoUrl = destination ? mapping.getRepoBUrl() : mapping.getRepoAUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            return PeerTip.unknown();
        }
        String full = ref.startsWith("refs/") ? ref : "refs/heads/" + ref;
        try {
            CredentialsProvider creds = credentials(mapping, repoUrl);
            var remote = Git.lsRemoteRepository()
                    .setRemote(repoUrl)
                    .setCredentialsProvider(creds)
                    .setTimeout(20);
            if (full.startsWith("refs/tags/")) {
                remote.setTags(true);
            } else if (full.startsWith("refs/heads/")) {
                remote.setHeads(true);
            }
            Map<String, Ref> advertised = remote.callAsMap();
            Ref peer = advertised.get(full);
            if (peer == null || peer.getObjectId() == null) {
                return new PeerTip(true, null);
            }
            ObjectId id = peer.getObjectId();
            return new PeerTip(true, id.getName());
        } catch (Exception e) {
            log.warn("Could not read {} {} while deciding echo: {}", repoUrl, full, e.getMessage());
            return PeerTip.unknown();
        }
    }

    private CredentialsProvider credentials(RepoMapping mapping, String repoUrl) {
        boolean sideB = RepoMappingService.sameRepo(repoUrl, mapping.getRepoBUrl());
        String token = sideB ? mapping.getTokenB() : mapping.getTokenA();
        String credId = sideB ? mapping.getTargetCredentialId() : mapping.getSourceCredentialId();
        String installId = sideB ? mapping.getTargetInstallationId() : mapping.getSourceInstallationId();
        if ((token == null || token.isBlank()) && credId != null && scmCredentialService != null) {
            token = scmCredentialService.resolveAccessToken(credId, installId, repoUrl);
        }
        try (ScmCredentialContext.Scope ignored = ScmCredentialContext.open(credId, installId)) {
            if (scmProviderFacade == null) {
                return null;
            }
            return scmProviderFacade.getGitCredentials(repoUrl, token);
        }
    }
}
