package com.gitutility.service;

import com.gitutility.model.dto.IncrementalGitEvent;
import com.gitutility.model.dto.PeerStatusResponse;
import com.gitutility.model.dto.WriteAuthorityRequest;
import com.gitutility.model.entity.FailoverParkedEvent;
import com.gitutility.model.entity.PairFailoverState;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.WriteAuthorityRecord;
import com.gitutility.model.enums.PairSide;
import com.gitutility.model.enums.SyncDirection;
import com.gitutility.repository.FailoverParkedEventRepository;
import com.gitutility.repository.PairFailoverStateRepository;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.WriteAuthorityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class FailoverService {

    private final PairFailoverStateRepository stateRepository;
    private final FailoverParkedEventRepository parkedRepository;
    private final RepoMappingRepository mappingRepository;
    private final WriteAuthorityRepository writeAuthorityRepository;
    private final ReplicaRulesetService replicaRulesetService;
    private WriteAuthorityService writeAuthorityService;

    @Autowired(required = false)
    public void setWriteAuthorityService(@Lazy WriteAuthorityService writeAuthorityService) {
        this.writeAuthorityService = writeAuthorityService;
    }

    private WebhookIngestionService webhookIngestionService;
    private com.gitutility.messaging.webhook.WebhookIncrementalService webhookIncrementalService;

    @Autowired(required = false)
    public void setWebhookIngestionService(@Lazy WebhookIngestionService webhookIngestionService) {
        this.webhookIngestionService = webhookIngestionService;
    }

    @Autowired(required = false)
    public void setWebhookIncrementalService(
            @Lazy com.gitutility.messaging.webhook.WebhookIncrementalService webhookIncrementalService) {
        this.webhookIncrementalService = webhookIncrementalService;
    }

    @Value("${git-utility.peer-heartbeat-seconds:120}")
    private int heartbeatSeconds;

    private final ConcurrentHashMap<String, Probe> probeCache = new ConcurrentHashMap<>();

    public PairFailoverState getOrCreate(String mappingId) {
        return stateRepository.findByMappingId(mappingId).orElseGet(() ->
                stateRepository.save(PairFailoverState.builder().mappingId(mappingId).phase("STEADY").peerLock("NONE").build()));
    }

    public boolean shouldPark(RepoMapping mapping) {
        if (mapping == null) {
            return false;
        }
        PairFailoverState state = getOrCreate(mapping.getId());
        return !processingAllowed(state);
    }

    public boolean processingAllowed(PairFailoverState state) {
        if (state == null) {
            return true;
        }
        boolean bothUp = "UP".equals(state.getSideA()) && "UP".equals(state.getSideB());
        if ("FAILOVER".equals(state.getPhase()) || "FAILBACK".equals(state.getPhase())) {
            return bothUp && "APPLIED".equals(state.getPeerLock());
        }
        if ("DOWN".equals(state.getSideA()) || "DOWN".equals(state.getSideB())) {
            return false;
        }
        return true;
    }

    public void park(RepoMapping mapping, IncrementalGitEvent event, String reason) {
        if (mapping == null || event == null) {
            return;
        }
        String delivery = event.getDeliveryId() == null || event.getDeliveryId().isBlank()
                ? IdsSafe.random() : event.getDeliveryId();
        parkedRepository.findByMappingIdAndDeliveryId(mapping.getId(), delivery).ifPresentOrElse(
                existing -> {
                    existing.setStatus("PARKED");
                    existing.setReason(reason);
                    parkedRepository.save(existing);
                },
                () -> parkedRepository.save(FailoverParkedEvent.builder()
                        .mappingId(mapping.getId())
                        .deliveryId(delivery)
                        .eventType(event.getEventType())
                        .repoUrl(event.getRepoUrl())
                        .ref(event.getRef())
                        .beforeSha(event.getBeforeSha())
                        .afterSha(event.getAfterSha())
                        .rawPayload(event.getRawPayload())
                        .reason(reason == null ? "PEER_UNREACHABLE" : reason)
                        .status("PARKED")
                        .receivedAt(Instant.now())
                        .build()));
    }

    public void markUnreachable(RepoMapping mapping, PairSide side, String error) {
        if (mapping == null) {
            return;
        }
        PairFailoverState state = getOrCreate(mapping.getId());
        if (side == PairSide.B) {
            state.setSideB("DOWN");
            state.setSideBCheckedAt(Instant.now());
            state.setSideBError(clip(error));
        } else {
            state.setSideA("DOWN");
            state.setSideACheckedAt(Instant.now());
            state.setSideAError(clip(error));
        }
        stateRepository.save(state);
    }

    public PeerStatusResponse status(String mappingId) {
        RepoMapping mapping = mappingRepository.findById(mappingId)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found: " + mappingId));
        PairFailoverState state = getOrCreate(mappingId);
        long parked = parkedRepository.countByMappingIdAndStatus(mappingId, "PARKED");
        boolean allowed = processingAllowed(state);
        String pausedReason = null;
        if (!allowed) {
            if ("DOWN".equals(state.getSideA()) && "DOWN".equals(state.getSideB())) {
                pausedReason = "both_down";
            } else if ("PENDING".equals(state.getPeerLock()) && "FAILOVER".equals(state.getPhase())) {
                pausedReason = "lock_pending";
            } else {
                pausedReason = "peer_down";
            }
        }
        boolean aUp = "UP".equals(state.getSideA());
        boolean bUp = "UP".equals(state.getSideB());
        PairSide primary = mapping.getPrimarySide() != null ? mapping.getPrimarySide() : PairSide.A;
        return PeerStatusResponse.builder()
                .mappingId(mappingId)
                .phase(state.getPhase())
                .processing(allowed ? "LIVE" : "PAUSED")
                .pausedReason(pausedReason)
                .parkedCount(parked)
                .link(aUp && bUp ? "ok" : ("UNKNOWN".equals(state.getSideA()) && "UNKNOWN".equals(state.getSideB()) ? "ok" : "broken"))
                .sides(List.of(
                        sideView(mapping, state, PairSide.A, primary),
                        sideView(mapping, state, PairSide.B, primary)))
                .build();
    }

    public PeerStatusResponse activateDr(String mappingId) {
        return activateDr(mappingId, "REPO", null, null, false);
    }

    public PeerStatusResponse activateDr(String mappingId, String lockScope, String lockCredentialId, String lockTarget, boolean lockApplied) {
        RepoMapping mapping = mappingRepository.findById(mappingId)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found: " + mappingId));
        PairFailoverState state = getOrCreate(mappingId);
        PairSide currentPrimary = mapping.getPrimarySide() != null ? mapping.getPrimarySide() : PairSide.A;
        PairSide replica = currentPrimary.opposite();
        state.setPriorDirection(mapping.getSyncDirection() == null ? null : mapping.getSyncDirection().name());
        state.setLockScope(lockScope == null ? "REPO" : lockScope);
        state.setLockCredentialId(lockCredentialId);
        state.setLockTarget(lockTarget);
        boolean higherScope = "ORG".equals(state.getLockScope()) || "ENTERPRISE".equals(state.getLockScope());
        if (!higherScope) {
            replicaRulesetService.tryEnforce(mapping, replica, "disabled");
        }
        mapping.setPrimarySide(replica);
        mapping.setSyncDirection(replica == PairSide.B
                ? SyncDirection.UNIDIRECTIONAL_B_TO_A
                : SyncDirection.UNIDIRECTIONAL_A_TO_B);
        long locked = higherScope
                ? (lockApplied ? 1L : 0L)
                : replicaRulesetService.tryEnforce(mapping, currentPrimary, "active");
        if (!higherScope && locked > 0) {
            mapping.setReplicaRulesetId(locked);
            mapping.setReplicaRulesetEnforcement("active");
        }
        mappingRepository.save(mapping);
        state.setPhase("FAILOVER");
        state.setActivePrimary(replica.name());
        state.setPeerLock(locked > 0 ? "APPLIED" : "PENDING");
        stateRepository.save(state);
        return status(mappingId);
    }

    public PeerStatusResponse failBack(String mappingId) {
        return failBack(mappingId, false);
    }

    public PeerStatusResponse failBack(String mappingId, boolean higherScopeLocked) {
        RepoMapping mapping = mappingRepository.findById(mappingId)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found: " + mappingId));
        PairFailoverState state = getOrCreate(mappingId);
        probe(mappingId);
        state = getOrCreate(mappingId);
        if (!"UP".equals(state.getSideA()) || !"UP".equals(state.getSideB())) {
            throw new IllegalStateException("Fail back needs both remotes up.");
        }
        PairSide drPrimary = mapping.getPrimarySide() != null ? mapping.getPrimarySide() : PairSide.B;
        PairSide original = drPrimary.opposite();
        state.setPhase("FAILBACK");
        stateRepository.save(state);
        long locked = higherScopeLocked ? 1L : replicaRulesetService.tryEnforce(mapping, drPrimary, "active");
        if (locked <= 0) {
            throw new IllegalStateException("Could not lock the DR side. Fail back stopped.");
        }
        if (!higherScopeLocked) {
            replicaRulesetService.tryEnforce(mapping, original, "disabled");
            mapping.setReplicaRulesetId(locked);
            mapping.setReplicaRulesetEnforcement("active");
        }
        mapping.setPrimarySide(original);
        mapping.setSyncDirection(restoreDirection(state.getPriorDirection(), original));
        mappingRepository.save(mapping);
        state.setPhase("STEADY");
        state.setActivePrimary(original.name());
        state.setPeerLock("APPLIED");
        stateRepository.save(state);
        drain(mappingId);
        return status(mappingId);
    }

    public PeerStatusResponse probe(String mappingId) {
        RepoMapping mapping = mappingRepository.findById(mappingId)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found: " + mappingId));
        PairFailoverState state = getOrCreate(mappingId);
        applyProbe(state, PairSide.A, mapping.getRepoAUrl(), mapping.getSourceCredentialId());
        applyProbe(state, PairSide.B, mapping.getRepoBUrl(), mapping.getTargetCredentialId());
        stateRepository.save(state);
        if (("FAILOVER".equals(state.getPhase()) || "FAILBACK".equals(state.getPhase()))
                && "PENDING".equals(state.getPeerLock())
                && "UP".equals(state.getSideA()) && "UP".equals(state.getSideB())) {
            boolean applied = retryPendingLock(mapping, state);
            if (applied) {
                state.setPeerLock("APPLIED");
                stateRepository.save(state);
            }
        }
        if (processingAllowed(state)) {
            drain(mappingId);
        }
        return status(mappingId);
    }

    @Scheduled(fixedDelayString = "${git-utility.peer-heartbeat-ms:120000}")
    public void heartbeatTick() {
        for (RepoMapping mapping : mappingRepository.findAll()) {
            if (mapping == null || !mapping.isActive()) {
                continue;
            }
            try {
                probe(mapping.getId());
            } catch (Exception e) {
                log.debug("Peer heartbeat for {} failed: {}", mapping.getId(), e.getMessage());
            }
        }
    }

    private boolean retryPendingLock(RepoMapping mapping, PairFailoverState state) {
        if (("ORG".equals(state.getLockScope()) || "ENTERPRISE".equals(state.getLockScope()))
                && writeAuthorityService != null
                && state.getLockCredentialId() != null
                && state.getLockTarget() != null) {
            try {
                WriteAuthorityRequest request = new WriteAuthorityRequest();
                request.setConfirmAllRepos("ALL REPOS");
                if ("ENTERPRISE".equals(state.getLockScope())) {
                    WriteAuthorityRequest.EnterpriseRow enterprise = new WriteAuthorityRequest.EnterpriseRow();
                    enterprise.setCredentialId(state.getLockCredentialId());
                    enterprise.setAccess("readonly");
                    request.setEnterprise(enterprise);
                } else {
                    WriteAuthorityRequest.OrgRow org = new WriteAuthorityRequest.OrgRow();
                    org.setCredentialId(state.getLockCredentialId());
                    org.setOrgLogin(state.getLockTarget());
                    org.setAccess("readonly");
                    request.setOrg(org);
                }
                writeAuthorityService.apply(request);
                return true;
            } catch (Exception e) {
                if (FailoverPeerErrors.looksUnreachable(e)) {
                    return false;
                }
                log.info("Pending {} lock still blocked for {}: {}", state.getLockScope(), mapping.getId(), e.getMessage());
                return false;
            }
        }
        PairSide demoted = mapping.getPrimarySide() == PairSide.B ? PairSide.A : PairSide.B;
        long id = replicaRulesetService.tryEnforce(mapping, demoted, "active");
        if (id > 0) {
            mapping.setReplicaRulesetId(id);
            mapping.setReplicaRulesetEnforcement("active");
            mappingRepository.save(mapping);
            return true;
        }
        return false;
    }

    public void drain(String mappingId) {
        PairFailoverState state = getOrCreate(mappingId);
        if (!processingAllowed(state)) {
            return;
        }
        List<FailoverParkedEvent> rows = parkedRepository.findByMappingIdAndStatusOrderByReceivedAtAsc(mappingId, "PARKED");
        for (FailoverParkedEvent row : rows) {
            IncrementalGitEvent event = IncrementalGitEvent.builder()
                    .provider("github")
                    .repoUrl(row.getRepoUrl())
                    .ref(row.getRef())
                    .beforeSha(row.getBeforeSha())
                    .afterSha(row.getAfterSha())
                    .deliveryId(row.getDeliveryId())
                    .eventType(row.getEventType())
                    .rawPayload(row.getRawPayload())
                    .receivedAt(row.getReceivedAt() == null ? Instant.now().toString() : row.getReceivedAt().toString())
                    .build();
            try {
                if (WebhookIngestionService.isMetadataEvent(row.getEventType())
                        && webhookIngestionService != null
                        && row.getRawPayload() != null) {
                    webhookIngestionService.ingestMetadataPayload(row.getRawPayload());
                } else if (webhookIncrementalService != null) {
                    webhookIncrementalService.handle(event);
                }
                row.setStatus("APPLIED");
                parkedRepository.save(row);
            } catch (Exception e) {
                if (FailoverPeerErrors.looksUnreachable(e)) {
                    log.info("Drain paused for {}: {}", mappingId, e.getMessage());
                    return;
                }
                log.warn("Parked event {} failed: {}", row.getDeliveryId(), e.getMessage());
            }
        }
    }

    private void applyProbe(PairFailoverState state, PairSide side, String repoUrl, String credentialId) {
        String key = hostOf(repoUrl) + "|" + (credentialId == null ? "" : credentialId);
        Probe cached = probeCache.get(key);
        if (cached != null && Instant.now().toEpochMilli() - cached.at.toEpochMilli() < 5_000) {
            writeProbe(state, side, cached);
            return;
        }
        Probe probe = ping(repoUrl);
        probeCache.put(key, probe);
        writeProbe(state, side, probe);
    }

    private void writeProbe(PairFailoverState state, PairSide side, Probe probe) {
        if (side == PairSide.B) {
            state.setSideB(probe.up ? "UP" : "DOWN");
            state.setSideBCheckedAt(probe.at);
            state.setSideBError(probe.up ? null : probe.error);
        } else {
            state.setSideA(probe.up ? "UP" : "DOWN");
            state.setSideACheckedAt(probe.at);
            state.setSideAError(probe.up ? null : probe.error);
        }
    }

    private Probe ping(String repoUrl) {
        Instant at = Instant.now();
        if (repoUrl == null || repoUrl.isBlank()) {
            return new Probe(false, at, "Missing repository URL");
        }
        try {
            String href = repoUrl.replaceFirst("\\.git$", "");
            HttpURLConnection connection = (HttpURLConnection) URI.create(href).toURL().openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("GET");
            int code = connection.getResponseCode();
            connection.disconnect();
            if (code >= 500) {
                return new Probe(false, at, "HTTP " + code);
            }
            return new Probe(true, at, null);
        } catch (Exception e) {
            return new Probe(false, at, clip(e.getMessage()));
        }
    }

    private PeerStatusResponse.Side sideView(RepoMapping mapping, PairFailoverState state,
                                             PairSide side, PairSide primary) {
        boolean isB = side == PairSide.B;
        String reachable = isB ? state.getSideB() : state.getSideA();
        boolean replica = side != primary;
        List<PeerStatusResponse.RulesetTarget> targets = rulesetTargets(mapping, side, replica, reachable);
        return PeerStatusResponse.Side.builder()
                .id(side.name())
                .provider(isB ? mapping.getTargetProvider() : mapping.getSourceProvider())
                .host(hostOf(isB ? mapping.getRepoBUrl() : mapping.getRepoAUrl()))
                .role(replica ? "replica" : "primary")
                .reachable(reachable)
                .checkedAt(isB ? state.getSideBCheckedAt() : state.getSideACheckedAt())
                .error(isB ? state.getSideBError() : state.getSideAError())
                .access(replica ? "readonly" : "write")
                .lock(replica && "PENDING".equals(state.getPeerLock()) && "FAILOVER".equals(state.getPhase())
                        ? "pending" : (replica ? "applied" : "none"))
                .rulesetRollup(rollup(targets, reachable, replica && "PENDING".equals(state.getPeerLock())))
                .rulesetTargets(targets)
                .build();
    }

    private List<PeerStatusResponse.RulesetTarget> rulesetTargets(RepoMapping mapping, PairSide side,
                                                                 boolean replica, String reachable) {
        List<PeerStatusResponse.RulesetTarget> targets = new ArrayList<>();
        String repoName = hostOf(side == PairSide.B ? mapping.getRepoBUrl() : mapping.getRepoAUrl());
        String enforcement = mapping.getReplicaRulesetEnforcement();
        boolean down = "DOWN".equals(reachable);
        if (replica) {
            String state = down ? "pending" : ("active".equalsIgnoreCase(enforcement) ? "applied" : "pending");
            targets.add(PeerStatusResponse.RulesetTarget.builder()
                    .scope("repo")
                    .name(repoName)
                    .state(state)
                    .error(down ? "Host is down" : null)
                    .build());
        } else {
            targets.add(PeerStatusResponse.RulesetTarget.builder()
                    .scope("repo")
                    .name(repoName)
                    .state(down ? "pending" : "applied")
                    .error(down ? "Host is down" : null)
                    .build());
        }
        for (WriteAuthorityRecord row : writeAuthorityRepository.findByMappingId(mapping.getId())) {
            if (row == null || (row.getSide() != null && !row.getSide().equalsIgnoreCase(side.name()))) {
                continue;
            }
            if ("PAIR".equalsIgnoreCase(row.getSubject()) && row.getScope() != null
                    && !"repo".equalsIgnoreCase(row.getScope())) {
                boolean wantActive = replica && "readonly".equalsIgnoreCase(row.getAccess());
                boolean applied = wantActive
                        ? "active".equalsIgnoreCase(row.getEnforcement())
                        : !"active".equalsIgnoreCase(row.getEnforcement());
                targets.add(PeerStatusResponse.RulesetTarget.builder()
                        .scope(row.getScope())
                        .name(row.getOrgLogin() != null ? row.getOrgLogin() : row.getEnterpriseSlug())
                        .state(down ? "pending" : (applied ? "applied" : "pending"))
                        .error(down ? "Host is down" : null)
                        .build());
            }
        }
        return targets;
    }

    private static String rollup(List<PeerStatusResponse.RulesetTarget> targets, String reachable, boolean lockPending) {
        if (targets.isEmpty()) {
            return "red";
        }
        long applied = targets.stream().filter(t -> "applied".equals(t.getState())).count();
        if ("DOWN".equals(reachable)) {
            return applied > 0 ? "yellow" : "red";
        }
        if (lockPending) {
            return applied > 0 ? "yellow" : "red";
        }
        if (applied == targets.size()) {
            return "green";
        }
        if (applied > 0) {
            return "yellow";
        }
        return "red";
    }

    private static SyncDirection restoreDirection(String prior, PairSide original) {
        if (prior != null) {
            try {
                return SyncDirection.valueOf(prior);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return original == PairSide.A
                ? SyncDirection.UNIDIRECTIONAL_A_TO_B
                : SyncDirection.UNIDIRECTIONAL_B_TO_A;
    }

    public static String hostOf(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return "";
        }
        String raw = repoUrl.trim();
        try {
            if (raw.startsWith("git@")) {
                String rest = raw.substring(4);
                int colon = rest.indexOf(':');
                return colon > 0 ? rest.substring(0, colon) : rest;
            }
            String href = raw.contains("://") ? raw : "https://" + raw;
            String host = URI.create(href).getHost();
            return host == null || host.isBlank() ? raw : host;
        } catch (Exception e) {
            return raw;
        }
    }

    private static String clip(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 480 ? text.substring(0, 477) + "..." : text;
    }

    private record Probe(boolean up, Instant at, String error) {
    }

    private static final class IdsSafe {
        static String random() {
            return java.util.UUID.randomUUID().toString();
        }
    }
}
