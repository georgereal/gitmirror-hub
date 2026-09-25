package com.gitutility.service;

import com.gitutility.model.dto.DrLaneResponse;
import com.gitutility.model.dto.PeerStatusResponse;
import com.gitutility.model.dto.WriteAuthorityRequest;
import com.gitutility.model.entity.PairFailoverState;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.ScmCredential;
import com.gitutility.model.entity.WriteAuthorityRecord;
import com.gitutility.model.enums.PairSide;
import com.gitutility.model.enums.SyncDirection;
import com.gitutility.provider.ScmProviderFacade;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.WriteAuthorityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Disaster recovery is a provider-to-provider lane. Org and enterprise rulesets
 * are applied first. A repository ruleset is used only when those scopes cannot.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DrLaneService {

    private final RepoMappingRepository mappingRepository;
    private final FailoverService failoverService;
    private final WriteAuthorityService writeAuthorityService;
    private final ScmCredentialService scmCredentialService;
    private final ScmProviderFacade scmProviderFacade;
    private final WriteAuthorityRepository writeAuthorityRepository;

    public List<DrLaneResponse> list() {
        Map<String, List<RepoMapping>> grouped = new LinkedHashMap<>();
        for (RepoMapping mapping : mappingRepository.findAll()) {
            if (mapping == null) {
                continue;
            }
            grouped.computeIfAbsent(laneKey(mapping), key -> new ArrayList<>()).add(mapping);
        }
        List<DrLaneResponse> lanes = new ArrayList<>();
        for (Map.Entry<String, List<RepoMapping>> entry : grouped.entrySet()) {
            lanes.add(summarize(entry.getKey(), entry.getValue()));
        }
        return lanes;
    }

    public DrLaneResponse activate(String laneKey) {
        List<RepoMapping> pairs = pairsIn(laneKey);
        if (pairs.isEmpty()) {
            throw new IllegalArgumentException("No pairs in this provider lane.");
        }
        PairSide primary = majorityPrimary(pairs);
        PairSide replica = primary.opposite();
        Map<String, LockResult> orgLocks = new LinkedHashMap<>();
        Map<String, LockResult> enterpriseLocks = new LinkedHashMap<>();
        for (RepoMapping mapping : pairs) {
            String phase = failoverService.getOrCreate(mapping.getId()).getPhase();
            if ("FAILOVER".equals(phase) || "FAILBACK".equals(phase)) {
                continue;
            }
            LockChoice choice = lockOne(mapping, primary, orgLocks, enterpriseLocks);
            failoverService.activateDr(mapping.getId(), choice.scope, lockCredential(mapping, primary),
                    lockTarget(mapping, primary, choice.scope), choice.applied);
        }
        unlockPromotedSide(pairs, replica);
        return summarize(laneKey, pairsIn(laneKey));
    }

    public DrLaneResponse failBack(String laneKey) {
        List<RepoMapping> pairs = pairsIn(laneKey);
        if (pairs.isEmpty()) {
            throw new IllegalArgumentException("No pairs in this provider lane.");
        }
        probe(laneKey);
        for (RepoMapping mapping : pairs) {
            PeerStatusResponse status = failoverService.status(mapping.getId());
            boolean down = status.getSides() != null
                    && status.getSides().stream().anyMatch(s -> "DOWN".equals(s.getReachable()));
            if (down) {
                throw new IllegalStateException("Fail back needs both providers up.");
            }
        }
        Map<String, LockResult> orgLocks = new LinkedHashMap<>();
        Map<String, LockResult> enterpriseLocks = new LinkedHashMap<>();
        for (RepoMapping mapping : pairs) {
            String phase = failoverService.getOrCreate(mapping.getId()).getPhase();
            if (!"FAILOVER".equals(phase) && !"FAILBACK".equals(phase)) {
                continue;
            }
            PairSide drPrimary = mapping.getPrimarySide() != null ? mapping.getPrimarySide() : PairSide.B;
            LockChoice choice = lockOne(mapping, drPrimary, orgLocks, enterpriseLocks);
            boolean higher = choice.applied && ("ORG".equals(choice.scope) || "ENTERPRISE".equals(choice.scope));
            if (higher) {
                unlockPromotedSide(List.of(mapping), drPrimary.opposite());
            }
            failoverService.failBack(mapping.getId(), higher);
        }
        return summarize(laneKey, pairsIn(laneKey));
    }

    public DrLaneResponse probe(String laneKey) {
        for (RepoMapping mapping : pairsIn(laneKey)) {
            failoverService.probe(mapping.getId());
        }
        return summarize(laneKey, pairsIn(laneKey));
    }

    private LockChoice lockOne(RepoMapping mapping, PairSide demoted,
                              Map<String, LockResult> orgLocks, Map<String, LockResult> enterpriseLocks) {
        String credentialId = lockCredential(mapping, demoted);
        String enterprise = firstEnterpriseSlug(List.of(mapping), demoted);
        if (enterprise != null && credentialId != null) {
            String key = credentialId + ":" + enterprise;
            LockResult result = enterpriseLocks.computeIfAbsent(key, ignored -> setEnterprise(credentialId, true));
            if (result != LockResult.REJECTED) {
                return new LockChoice("ENTERPRISE", result == LockResult.APPLIED);
            }
        }
        String org = owner(demoted == PairSide.B ? mapping.getRepoBUrl() : mapping.getRepoAUrl());
        if (org != null && credentialId != null) {
            String key = credentialId + ":" + org;
            LockResult result = orgLocks.computeIfAbsent(key, ignored -> setOrg(credentialId, org, true));
            if (result != LockResult.REJECTED) {
                return new LockChoice("ORG", result == LockResult.APPLIED);
            }
        }
        return new LockChoice("REPO", false);
    }

    private enum LockResult { APPLIED, UNREACHABLE, REJECTED }

    private record LockChoice(String scope, boolean applied) {}

    private void unlockPromotedSide(List<RepoMapping> pairs, PairSide promoted) {
        String enterprise = firstEnterpriseSlug(pairs, promoted);
        String credentialId = lockCredential(pairs.get(0), promoted);
        if (enterprise != null && credentialId != null && setEnterprise(credentialId, false) == LockResult.APPLIED) {
            return;
        }
        for (String org : orgsOn(pairs, promoted)) {
            String cred = credentialForOrg(pairs, promoted, org);
            if (cred != null) {
                setOrg(cred, org, false);
            }
        }
    }

    private LockResult setOrg(String credentialId, String orgLogin, boolean readonly) {
        try {
            WriteAuthorityRequest request = new WriteAuthorityRequest();
            WriteAuthorityRequest.OrgRow org = new WriteAuthorityRequest.OrgRow();
            org.setCredentialId(credentialId);
            org.setOrgLogin(orgLogin);
            org.setAccess(readonly ? "readonly" : "write");
            request.setOrg(org);
            if (readonly) {
                request.setConfirmAllRepos("ALL REPOS");
            }
            writeAuthorityService.apply(request);
            return LockResult.APPLIED;
        } catch (Exception e) {
            log.info("Org ruleset {} for {} skipped: {}", readonly ? "lock" : "unlock", orgLogin, e.getMessage());
            return FailoverPeerErrors.looksUnreachable(e) ? LockResult.UNREACHABLE : LockResult.REJECTED;
        }
    }

    private LockResult setEnterprise(String credentialId, boolean readonly) {
        try {
            WriteAuthorityRequest request = new WriteAuthorityRequest();
            WriteAuthorityRequest.EnterpriseRow enterprise = new WriteAuthorityRequest.EnterpriseRow();
            enterprise.setCredentialId(credentialId);
            enterprise.setAccess(readonly ? "readonly" : "write");
            request.setEnterprise(enterprise);
            if (readonly) {
                request.setConfirmAllRepos("ALL REPOS");
            }
            writeAuthorityService.apply(request);
            return LockResult.APPLIED;
        } catch (Exception e) {
            log.info("Enterprise ruleset skipped: {}", e.getMessage());
            return FailoverPeerErrors.looksUnreachable(e) ? LockResult.UNREACHABLE : LockResult.REJECTED;
        }
    }

    private DrLaneResponse summarize(String key, List<RepoMapping> pairs) {
        RepoMapping sample = pairs.get(0);
        String phase = "STEADY";
        String processing = "LIVE";
        String link = "ok";
        String lockScope = null;
        long parked = 0;
        String sourceReachable = null;
        String targetReachable = null;
        String sourceRole = "primary";
        String targetRole = "replica";
        String flow = flowOf(sample);
        List<WriteAuthorityRecord> authority = writeAuthorityRepository.findAll();
        Map<String, DrLaneResponse.RulesetRow> rulesets = new LinkedHashMap<>();
        List<DrLaneResponse.PairRow> rows = new ArrayList<>();
        for (RepoMapping mapping : pairs) {
            PeerStatusResponse status = failoverService.status(mapping.getId());
            PairFailoverState state = failoverService.getOrCreate(mapping.getId());
            if ("FAILOVER".equals(status.getPhase()) || "FAILBACK".equals(status.getPhase())) {
                phase = status.getPhase();
            }
            if ("PAUSED".equals(status.getProcessing())) {
                processing = "PAUSED";
            }
            if ("broken".equals(status.getLink())) {
                link = "broken";
            }
            if (state.getLockScope() != null) {
                lockScope = state.getLockScope();
            }
            if (!flow.equals(flowOf(mapping))) {
                flow = "BOTH";
            }
            parked += status.getParkedCount();
            PeerStatusResponse.Side a = side(status, "A");
            PeerStatusResponse.Side b = side(status, "B");
            sourceReachable = worse(sourceReachable, a == null ? "UNKNOWN" : a.getReachable());
            targetReachable = worse(targetReachable, b == null ? "UNKNOWN" : b.getReachable());
            if (a != null && a.getRole() != null) {
                sourceRole = a.getRole();
            }
            if (b != null && b.getRole() != null) {
                targetRole = b.getRole();
            }
            addScopeRuleset(rulesets, "org", "A", owner(mapping.getRepoAUrl()), mapping, state, authority);
            addScopeRuleset(rulesets, "org", "B", owner(mapping.getRepoBUrl()), mapping, state, authority);
            addScopeRuleset(rulesets, "repo", "A", fullName(mapping.getRepoAUrl()), mapping, state, authority);
            addScopeRuleset(rulesets, "repo", "B", fullName(mapping.getRepoBUrl()), mapping, state, authority);
            rows.add(DrLaneResponse.PairRow.builder()
                    .id(mapping.getId())
                    .name(mapping.getName())
                    .phase(status.getPhase())
                    .reachableA(a == null ? "UNKNOWN" : a.getReachable())
                    .reachableB(b == null ? "UNKNOWN" : b.getReachable())
                    .lock(a != null && "pending".equals(a.getLock()) || b != null && "pending".equals(b.getLock())
                            ? "pending" : "applied")
                    .rulesetRollupA(a == null ? "red" : a.getRulesetRollup())
                    .rulesetRollupB(b == null ? "red" : b.getRulesetRollup())
                    .build());
        }
        addEnterpriseRulesets(rulesets, pairs, authority);
        List<DrLaneResponse.RulesetRow> rulesetRows = new ArrayList<>(rulesets.values());
        return DrLaneResponse.builder()
                .laneKey(key)
                .sourceProvider(label(sample.getSourceProvider()))
                .sourceHost(FailoverService.hostOf(sample.getRepoAUrl()))
                .targetProvider(label(sample.getTargetProvider()))
                .targetHost(FailoverService.hostOf(sample.getRepoBUrl()))
                .phase(phase)
                .processing(processing)
                .link(link)
                .lockScope(lockScope)
                .sourceReachable(sourceReachable == null ? "UNKNOWN" : sourceReachable)
                .targetReachable(targetReachable == null ? "UNKNOWN" : targetReachable)
                .sourceRole(sourceRole)
                .targetRole(targetRole)
                .flow(flow)
                .rulesetRollup(rollup(rulesetRows, lockScope))
                .parkedCount(parked)
                .pairCount(pairs.size())
                .orgs(scopeRows(pairs))
                .enterprises(enterpriseRows(pairs))
                .pairs(rows)
                .rulesets(rulesetRows)
                .build();
    }

    private void addScopeRuleset(Map<String, DrLaneResponse.RulesetRow> rows, String scope, String side, String name,
                                 RepoMapping mapping, PairFailoverState state, List<WriteAuthorityRecord> authority) {
        if (name == null || name.isBlank()) {
            return;
        }
        String key = scope + ":" + side + ":" + name;
        WriteAuthorityRecord match = matchAuthority(authority, scope, side, name, mapping);
        String nextState = rulesetState(match, state, scope, name, side, mapping);
        rows.compute(key, (k, existing) -> {
            if (existing == null) {
                return DrLaneResponse.RulesetRow.builder()
                        .scope(scope)
                        .side(side)
                        .name(name)
                        .state(nextState)
                        .detail(rulesetDetail(scope, nextState))
                        .pairCount(1)
                        .build();
            }
            existing.setPairCount(existing.getPairCount() + 1);
            existing.setState(preferState(existing.getState(), nextState));
            existing.setDetail(rulesetDetail(scope, existing.getState()));
            return existing;
        });
    }

    private void addEnterpriseRulesets(Map<String, DrLaneResponse.RulesetRow> rows, List<RepoMapping> pairs,
                                       List<WriteAuthorityRecord> authority) {
        addEnterpriseRuleset(rows, pairs, authority, PairSide.A);
        addEnterpriseRuleset(rows, pairs, authority, PairSide.B);
    }

    private void addEnterpriseRuleset(Map<String, DrLaneResponse.RulesetRow> rows, List<RepoMapping> pairs,
                                      List<WriteAuthorityRecord> authority, PairSide side) {
        String slug = firstEnterpriseSlug(pairs, side);
        if (slug == null) {
            return;
        }
        RepoMapping sample = pairs.get(0);
        PairFailoverState state = failoverService.getOrCreate(sample.getId());
        WriteAuthorityRecord match = null;
        String credentialId = lockCredential(sample, side);
        for (WriteAuthorityRecord row : authority) {
            if (row == null || row.getEnterpriseSlug() == null) {
                continue;
            }
            if (!slug.equals(row.getEnterpriseSlug())) {
                continue;
            }
            if (credentialId != null && credentialId.equals(row.getCredentialId())
                    && ("ENTERPRISE".equalsIgnoreCase(row.getSubject()) || "enterprise".equalsIgnoreCase(row.getScope()))) {
                match = row;
                break;
            }
        }
        String nextState = rulesetState(match, state, "enterprise", slug, side.name(), sample);
        rows.put("enterprise:" + side.name() + ":" + slug, DrLaneResponse.RulesetRow.builder()
                .scope("enterprise")
                .side(side.name())
                .name(slug)
                .state(nextState)
                .detail(rulesetDetail("enterprise", nextState))
                .pairCount(pairs.size())
                .build());
    }

    private WriteAuthorityRecord matchAuthority(List<WriteAuthorityRecord> authority, String scope, String side,
                                                String name, RepoMapping mapping) {
        for (WriteAuthorityRecord row : authority) {
            if (row == null) {
                continue;
            }
            boolean sameSide = row.getSide() == null || side.equalsIgnoreCase(row.getSide());
            boolean sameMapping = mapping.getId().equals(row.getMappingId());
            if ("org".equals(scope) && name.equals(row.getOrgLogin())
                    && ("ORG".equalsIgnoreCase(row.getSubject()) || "org".equalsIgnoreCase(row.getScope()))
                    && (sameMapping || row.getMappingId() == null)
                    && sameSide) {
                return row;
            }
            if ("repo".equals(scope) && name.equalsIgnoreCase(row.getRepoFullName() == null ? "" : row.getRepoFullName())
                    && sameMapping && sameSide) {
                return row;
            }
        }
        return null;
    }

    private String rulesetState(WriteAuthorityRecord row, PairFailoverState state, String scope, String name,
                                String side, RepoMapping mapping) {
        if (row != null && "active".equalsIgnoreCase(row.getEnforcement()) && "readonly".equalsIgnoreCase(row.getAccess())) {
            return "applied";
        }
        if (row != null && "disabled".equalsIgnoreCase(row.getEnforcement())) {
            return "open";
        }
        boolean failover = state != null && ("FAILOVER".equals(state.getPhase()) || "FAILBACK".equals(state.getPhase()));
        boolean targeted = state != null && scope.equalsIgnoreCase(state.getLockScope()) && name.equals(state.getLockTarget());
        PairSide demoted = demotedSide(mapping, state);
        if (failover && targeted && demoted != null && demoted.name().equals(side)) {
            return "PENDING".equals(state.getPeerLock()) ? "pending" : "applied";
        }
        if (failover && "repo".equals(scope) && (state.getLockScope() == null || "REPO".equals(state.getLockScope()))
                && demoted != null && demoted.name().equals(side)) {
            return "PENDING".equals(state.getPeerLock()) ? "pending" : "applied";
        }
        if (mapping.getReplicaRulesetId() != null && mapping.getReplicaRulesetId() > 0
                && "active".equalsIgnoreCase(mapping.getReplicaRulesetEnforcement())
                && "repo".equals(scope) && demoted != null && demoted.name().equals(side)) {
            return "applied";
        }
        return "none";
    }

    private static PairSide demotedSide(RepoMapping mapping, PairFailoverState state) {
        if (state == null || state.getPhase() == null || "STEADY".equals(state.getPhase())) {
            PairSide primary = mapping.getPrimarySide() != null ? mapping.getPrimarySide() : PairSide.A;
            return primary.opposite();
        }
        PairSide active = mapping.getPrimarySide() != null ? mapping.getPrimarySide() : PairSide.B;
        return active.opposite();
    }

    private static String rulesetDetail(String scope, String state) {
        String place = "enterprise".equals(scope) ? "Enterprise" : "org".equals(scope) ? "Organization" : "Repository";
        return switch (state) {
            case "applied" -> place + " read-only ruleset is in place.";
            case "pending" -> place + " ruleset is waiting for this provider to answer.";
            case "open" -> place + " ruleset is present and write is open.";
            default -> "No " + place.toLowerCase() + " ruleset written yet.";
        };
    }

    private static String preferState(String current, String next) {
        if ("applied".equals(current) || "applied".equals(next)) {
            return "applied";
        }
        if ("pending".equals(current) || "pending".equals(next)) {
            return "pending";
        }
        if ("open".equals(current) || "open".equals(next)) {
            return "open";
        }
        return "none";
    }

    private static String rollup(List<DrLaneResponse.RulesetRow> rows, String lockScope) {
        String scope = lockScope == null ? "org" : lockScope.toLowerCase();
        List<DrLaneResponse.RulesetRow> relevant = new ArrayList<>();
        for (DrLaneResponse.RulesetRow row : rows) {
            if (scope.equals(row.getScope())) {
                relevant.add(row);
            }
        }
        if (relevant.isEmpty()) {
            relevant = rows;
        }
        if (relevant.isEmpty()) {
            return "none";
        }
        long applied = relevant.stream().filter(row -> "applied".equals(row.getState())).count();
        long pending = relevant.stream().filter(row -> "pending".equals(row.getState())).count();
        if (applied == 0 && pending == 0) {
            return "none";
        }
        if (applied == relevant.size()) {
            return "green";
        }
        if (applied > 0) {
            return "yellow";
        }
        return "red";
    }

    private static String worse(String current, String next) {
        String incoming = next == null ? "UNKNOWN" : next;
        if (current == null || "UNKNOWN".equals(current)) {
            return incoming;
        }
        if ("UNKNOWN".equals(incoming)) {
            return current;
        }
        if ("DOWN".equals(current) || "DOWN".equals(incoming)) {
            return "DOWN";
        }
        return "UP";
    }

    private static String flowOf(RepoMapping mapping) {
        if (mapping.getSyncDirection() == SyncDirection.UNIDIRECTIONAL_B_TO_A) {
            return "B_TO_A";
        }
        if (mapping.getSyncDirection() == SyncDirection.BIDIRECTIONAL) {
            return "BOTH";
        }
        return "A_TO_B";
    }

    private String fullName(String repoUrl) {
        String full = scmProviderFacade.parseRepoFullName(repoUrl);
        return full == null || full.isBlank() ? null : full;
    }

    private List<DrLaneResponse.ScopeRow> scopeRows(List<RepoMapping> pairs) {
        Map<String, DrLaneResponse.ScopeRow> rows = new LinkedHashMap<>();
        for (RepoMapping mapping : pairs) {
            addOrg(rows, "A", owner(mapping.getRepoAUrl()), mapping.getSourceCredentialId(), mapping);
            addOrg(rows, "B", owner(mapping.getRepoBUrl()), mapping.getTargetCredentialId(), mapping);
        }
        return new ArrayList<>(rows.values());
    }

    private void addOrg(Map<String, DrLaneResponse.ScopeRow> rows, String side, String org, String credentialId, RepoMapping mapping) {
        if (org == null) {
            return;
        }
        String key = side + ":" + org;
        PeerStatusResponse status = failoverService.status(mapping.getId());
        PeerStatusResponse.Side peer = side(status, side);
        rows.compute(key, (k, existing) -> {
            if (existing == null) {
                return DrLaneResponse.ScopeRow.builder()
                        .side(side)
                        .name(org)
                        .credentialId(credentialId)
                        .reachable(peer == null ? "UNKNOWN" : peer.getReachable())
                        .rulesetRollup(peer == null ? "red" : peer.getRulesetRollup())
                        .pairCount(1)
                        .build();
            }
            existing.setPairCount(existing.getPairCount() + 1);
            return existing;
        });
    }

    private List<DrLaneResponse.ScopeRow> enterpriseRows(List<RepoMapping> pairs) {
        Map<String, DrLaneResponse.ScopeRow> rows = new LinkedHashMap<>();
        addEnterprise(rows, pairs, PairSide.A);
        addEnterprise(rows, pairs, PairSide.B);
        return new ArrayList<>(rows.values());
    }

    private void addEnterprise(Map<String, DrLaneResponse.ScopeRow> rows, List<RepoMapping> pairs, PairSide side) {
        String slug = firstEnterpriseSlug(pairs, side);
        if (slug == null) {
            return;
        }
        rows.put(side.name() + ":" + slug, DrLaneResponse.ScopeRow.builder()
                .side(side.name())
                .name(slug)
                .credentialId(lockCredential(pairs.get(0), side))
                .pairCount(pairs.size())
                .build());
    }

    private List<RepoMapping> pairsIn(String laneKey) {
        List<RepoMapping> pairs = new ArrayList<>();
        for (RepoMapping mapping : mappingRepository.findAll()) {
            if (mapping != null && laneKey(mapping).equals(laneKey)) {
                pairs.add(mapping);
            }
        }
        return pairs;
    }

    private List<String> orgsOn(List<RepoMapping> pairs, PairSide side) {
        List<String> orgs = new ArrayList<>();
        for (RepoMapping mapping : pairs) {
            String org = owner(side == PairSide.B ? mapping.getRepoBUrl() : mapping.getRepoAUrl());
            if (org != null && !orgs.contains(org)) {
                orgs.add(org);
            }
        }
        return orgs;
    }

    private String credentialForOrg(List<RepoMapping> pairs, PairSide side, String org) {
        for (RepoMapping mapping : pairs) {
            String owner = owner(side == PairSide.B ? mapping.getRepoBUrl() : mapping.getRepoAUrl());
            if (org.equals(owner)) {
                return lockCredential(mapping, side);
            }
        }
        return null;
    }

    private String firstEnterpriseSlug(List<RepoMapping> pairs, PairSide side) {
        for (RepoMapping mapping : pairs) {
            String credentialId = lockCredential(mapping, side);
            if (credentialId == null) {
                continue;
            }
            try {
                ScmCredential cred = scmCredentialService.require(credentialId);
                if (cred.getEnterpriseSlug() != null && !cred.getEnterpriseSlug().isBlank()) {
                    return cred.getEnterpriseSlug();
                }
            } catch (Exception ignored) {
                // credential missing
            }
        }
        return null;
    }

    private static String lockCredential(RepoMapping mapping, PairSide side) {
        return side == PairSide.B ? mapping.getTargetCredentialId() : mapping.getSourceCredentialId();
    }

    private String lockTarget(RepoMapping mapping, PairSide side, String scope) {
        if ("ENTERPRISE".equals(scope)) {
            return firstEnterpriseSlug(List.of(mapping), side);
        }
        if ("ORG".equals(scope)) {
            return owner(side == PairSide.B ? mapping.getRepoBUrl() : mapping.getRepoAUrl());
        }
        return owner(side == PairSide.B ? mapping.getRepoBUrl() : mapping.getRepoAUrl());
    }

    private String owner(String repoUrl) {
        String full = scmProviderFacade.parseRepoFullName(repoUrl);
        if (full == null || !full.contains("/")) {
            return null;
        }
        return full.substring(0, full.indexOf('/'));
    }

    private static PairSide majorityPrimary(List<RepoMapping> pairs) {
        int b = 0;
        for (RepoMapping mapping : pairs) {
            if (mapping.getPrimarySide() == PairSide.B) {
                b++;
            }
        }
        return b * 2 > pairs.size() ? PairSide.B : PairSide.A;
    }

    public static String laneKey(RepoMapping mapping) {
        String source = (mapping.getSourceProvider() == null ? "GIT" : mapping.getSourceProvider())
                + "@" + FailoverService.hostOf(mapping.getRepoAUrl());
        String target = (mapping.getTargetProvider() == null ? "GIT" : mapping.getTargetProvider())
                + "@" + FailoverService.hostOf(mapping.getRepoBUrl());
        return source + "->" + target;
    }

    private static String label(String provider) {
        return provider == null || provider.isBlank() ? "Git" : provider;
    }

    private static PeerStatusResponse.Side side(PeerStatusResponse status, String id) {
        if (status.getSides() == null) {
            return null;
        }
        return status.getSides().stream().filter(s -> id.equals(s.getId())).findFirst().orElse(null);
    }
}
