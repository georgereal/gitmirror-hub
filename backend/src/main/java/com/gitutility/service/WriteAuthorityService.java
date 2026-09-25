package com.gitutility.service;

import com.gitutility.model.InstallationIds;
import com.gitutility.model.dto.RepositoryRulesetItem;
import com.gitutility.model.dto.ScmInstallationOption;
import com.gitutility.model.dto.WriteAuthorityRequest;
import com.gitutility.model.dto.WriteAuthorityView;
import com.gitutility.model.entity.RepoMapping;
import com.gitutility.model.entity.ScmCredential;
import com.gitutility.model.entity.WriteAuthorityRecord;
import com.gitutility.model.enums.PairSide;
import com.gitutility.model.enums.ScmProviderType;
import com.gitutility.provider.GitHubRulesetClient;
import com.gitutility.provider.ReadonlyRulesetSpec;
import com.gitutility.provider.RulesetPresence;
import com.gitutility.provider.ScmProviderAdapter;
import com.gitutility.provider.ScmProviderFacade;
import com.gitutility.repository.RepoMappingRepository;
import com.gitutility.repository.WriteAuthorityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sets write or read-only at repository, organization, or enterprise scope.
 * A linked pair locks the side that is becoming read-only before it opens the other side.
 */
@Service
@RequiredArgsConstructor
public class WriteAuthorityService {

    static final String CONFIRM_ALL_REPOS = "ALL REPOS";

    private final WriteAuthorityRepository repository;
    private final RepoMappingRepository mappingRepository;
    private final ScmCredentialService scmCredentialService;
    private final ScmProviderFacade scmProviderFacade;

    public WriteAuthorityView view() {
        return view(false);
    }

    /**
     * @param refreshPrivileges accepted from {@code ?refresh=true}. Tokens are dropped on every load.
     */
    public WriteAuthorityView view(boolean refreshPrivileges) {
        scmCredentialService.invalidateAllTokens();
        InstallCache cache = new InstallCache();
        List<WriteAuthorityRecord> stored = repository.findAll();
        List<WriteAuthorityView.Pair> pairs = new ArrayList<>();
        for (RepoMapping mapping : mappingRepository.findAll()) {
            pairs.add(pairView(mapping, stored, cache));
        }
        return WriteAuthorityView.builder()
                .pairs(pairs)
                .orgs(orgRows(cache, stored))
                .enterprises(enterpriseRows(cache, stored))
                .build();
    }

    public WriteAuthorityView.RepoRow repositoryRuleset(String credentialId, String fullName, String installationId) {
        if (credentialId == null || credentialId.isBlank() || fullName == null || fullName.isBlank() || !fullName.contains("/")) {
            throw new IllegalArgumentException("A repository ruleset needs a credential and an owner/name.");
        }
        ScmCredential cred = scmCredentialService.require(credentialId.trim());
        return lookupRepositoryRuleset(cred, fullName.trim(), installationId);
    }

    public List<RepositoryRulesetItem> listRepositoryRulesets(String credentialId, String fullName, String installationId) {
        Resolved resolved = openRepository(credentialId, fullName, installationId);
        ScmProviderAdapter adapter = adapterFor(resolved.credential, resolved.repoUrl);
        if (adapter == null) {
            throw new IllegalStateException("No GitHub provider matches this credential.");
        }
        try (var ignored = ScmCredentialContext.open(resolved.credential.getId(), resolved.installationId)) {
            return adapter.listRepositoryRulesets(resolved.repoFullName).stream()
                    .map(row -> RepositoryRulesetItem.builder()
                            .id(row.id())
                            .name(row.name())
                            .target(row.target())
                            .enforcement(row.enforcement())
                            .hubReplica(GitHubRulesetClient.RULESET_NAME.equals(row.name()))
                            .build())
                    .toList();
        }
    }

    public void setRepositoryRulesetEnforcement(WriteAuthorityRequest.RulesetEnforcement body) {
        if (body == null || body.getRulesetId() <= 0) {
            throw new IllegalArgumentException("Choose a ruleset to update.");
        }
        String mode = body.getEnforcement() == null ? "" : body.getEnforcement().trim().toLowerCase();
        if (!"active".equals(mode) && !"disabled".equals(mode)) {
            throw new IllegalArgumentException("Enforcement must be active or disabled.");
        }
        Resolved resolved = openRepository(body.getCredentialId(), body.getRepoFullName(), body.getInstallationId());
        ScmProviderAdapter adapter = adapterFor(resolved.credential, resolved.repoUrl);
        if (adapter == null) {
            throw new IllegalStateException("No GitHub provider matches this credential.");
        }
        try (var ignored = ScmCredentialContext.open(resolved.credential.getId(), resolved.installationId)) {
            adapter.setRepositoryRulesetEnforcement(resolved.repoFullName, body.getRulesetId(), mode);
        }
    }

    private Resolved openRepository(String credentialId, String fullName, String installationId) {
        if (credentialId == null || credentialId.isBlank() || fullName == null || fullName.isBlank() || !fullName.contains("/")) {
            throw new IllegalArgumentException("A repository ruleset needs a credential and an owner/name.");
        }
        ScmCredential cred = scmCredentialService.require(credentialId.trim());
        String name = fullName.trim();
        String owner = name.substring(0, name.indexOf('/'));
        InstallCache cache = new InstallCache();
        String installId = resolveInstallId(cred, installationId, owner, cache);
        String repoUrl = repoUrlFor(cred, name);
        WriteAuthorityView.Chip chip = repoChip(cred, repoUrl);
        if (!chip.isEnabled()) {
            throw new IllegalArgumentException(chip.getReason());
        }
        return new Resolved(cred, installId, repoUrl, name, owner, appIdOrZero(cred.getAppId()));
    }

    public WriteAuthorityView apply(WriteAuthorityRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (request.getPairId() != null && !request.getPairId().isBlank()) {
            applyPair(request);
        } else if (request.getOrg() != null) {
            applyOrg(request.getOrg(), request.getConfirmAllRepos());
        } else if (request.getRepository() != null) {
            applyRepository(request.getRepository());
        } else if (request.getEnterprise() != null) {
            applyEnterprise(request.getEnterprise(), request.getConfirmAllRepos());
        } else {
            throw new IllegalArgumentException("Choose a pair, an organization, a repository, or an enterprise.");
        }
        return view();
    }

    public Map<String, String> notesFor(List<RepoMapping> mappings) {
        List<WriteAuthorityRecord> stored = repository.findAll();
        Map<String, String> notes = new HashMap<>();
        for (RepoMapping mapping : mappings) {
            String note = mappingNote(mapping, stored);
            if (note != null) {
                notes.put(mapping.getId(), note);
            }
        }
        return notes;
    }

    private void applyPair(WriteAuthorityRequest request) {
        RepoMapping mapping = mappingRepository.findById(request.getPairId())
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found: " + request.getPairId()));
        String link = "independent".equalsIgnoreCase(request.getLink()) ? "independent" : "linked";
        Map<String, WriteAuthorityRequest.Side> bySide = indexSides(request.getSides());
        WriteAuthorityRequest.Side sideA = bySide.get("A");
        WriteAuthorityRequest.Side sideB = bySide.get("B");
        if ("linked".equals(link) && readonly(sideA) && readonly(sideB)) {
            throw new IllegalArgumentException("Unlink the pair to make both sides read-only.");
        }
        boolean needsConfirm = (readonly(sideA) && allRepos(sideA)) || (readonly(sideB) && allRepos(sideB));
        requireConfirm(needsConfirm, request.getConfirmAllRepos());

        InstallCache cache = new InstallCache();
        Resolved resolvedA = resolve(mapping, "A", cache);
        Resolved resolvedB = resolve(mapping, "B", cache);
        assertAllowed(resolvedA, sideA);
        assertAllowed(resolvedB, sideB);

        List<WriteAuthorityRequest.Side> order = new ArrayList<>(List.of(sideA, sideB));
        order.sort(Comparator.comparingInt(side -> readonly(side) ? 0 : 1));
        for (WriteAuthorityRequest.Side side : order) {
            applySide(mapping, link, side, "A".equals(side.getSide()) ? resolvedA : resolvedB);
        }
        syncLegacyPairFields(mapping);
        mappingRepository.save(mapping);
    }

    private void applySide(RepoMapping mapping, String link, WriteAuthorityRequest.Side side, Resolved resolved) {
        boolean lock = readonly(side);
        String scope = normalizeScope(side.getScope());
        String target = targetFor(scope, side.getTarget());
        String key = WriteAuthorityRecord.pairKey(mapping.getId(), side.getSide());
        WriteAuthorityRecord previous = repository.findByRecordKey(key).orElse(null);
        ReadonlyRulesetSpec next = spec(scope, target, resolved, lock ? "active" : "disabled");
        long id;
        if (lock) {
            id = call(resolved, next);
            retireIfDifferent(resolved, previous, next);
        } else {
            ReadonlyRulesetSpec off = previous == null ? next : specFromRecord(previous, resolved, "disabled");
            id = call(resolved, off);
            next = off;
        }
        WriteAuthorityRecord row = previous != null ? previous : WriteAuthorityRecord.builder().recordKey(key).build();
        row.setSubject("PAIR");
        row.setMappingId(mapping.getId());
        row.setSide(side.getSide());
        row.setCredentialId(resolved.credential.getId());
        row.setOrgLogin(resolved.orgLogin);
        row.setEnterpriseSlug(resolved.credential.getEnterpriseSlug());
        row.setLinkMode(link);
        row.setScope(next.kind());
        row.setTarget(next.target());
        row.setAccess(lock ? "readonly" : "write");
        row.setEnforcement(lock ? "active" : "disabled");
        row.setRulesetName(next.rulesetName());
        row.setRepoFullName(resolved.repoFullName);
        if (id > 0) {
            row.setRulesetId(id);
        }
        repository.save(row);
    }

    private void applyOrg(WriteAuthorityRequest.OrgRow org, String confirm) {
        if (org.getCredentialId() == null || org.getOrgLogin() == null || org.getOrgLogin().isBlank()) {
            throw new IllegalArgumentException("An organization row needs a credential and an org login.");
        }
        boolean lock = "readonly".equalsIgnoreCase(org.getAccess());
        requireConfirm(lock, confirm);
        ScmCredential cred = scmCredentialService.require(org.getCredentialId());
        InstallCache cache = new InstallCache();
        ScmInstallationOption inst = installationFor(cred, null, org.getOrgLogin().trim(), cache);
        WriteAuthorityView.Chip chip = orgChip(cred, inst, cache.error(cred.getId()));
        if (!chip.isEnabled()) {
            throw new IllegalArgumentException(chip.getReason());
        }
        long appId = parseAppId(cred.getAppId());
        ReadonlyRulesetSpec spec = new ReadonlyRulesetSpec(
                ReadonlyRulesetSpec.KIND_ORG, ReadonlyRulesetSpec.TARGET_ALL_REPOS,
                null, inst.getAccountLogin(), null, appId, lock ? "active" : "disabled");
        Resolved resolved = new Resolved(cred, inst.getInstallationId(), null, null, inst.getAccountLogin(), appId);
        long id = call(resolved, spec);
        saveStandalone("ORG", WriteAuthorityRecord.orgKey(cred.getId(), inst.getAccountLogin()),
                cred, inst.getAccountLogin(), null, spec, lock, id, null);
    }

    private void applyRepository(WriteAuthorityRequest.RepositoryRow repository) {
        if (repository.getCredentialId() == null || repository.getRepoFullName() == null
                || repository.getRepoFullName().isBlank() || !repository.getRepoFullName().contains("/")) {
            throw new IllegalArgumentException("A repository ruleset needs a credential and an owner/name.");
        }
        boolean lock = "readonly".equalsIgnoreCase(repository.getAccess());
        ScmCredential cred = scmCredentialService.require(repository.getCredentialId());
        String fullName = repository.getRepoFullName().trim();
        String owner = fullName.substring(0, fullName.indexOf('/'));
        InstallCache cache = new InstallCache();
        String installId = resolveInstallId(cred, repository.getInstallationId(), owner, cache);
        String repoUrl = repoUrlFor(cred, fullName);
        long appId = parseAppId(cred.getAppId());
        WriteAuthorityView.Chip chip = repoChip(cred, repoUrl);
        if (!chip.isEnabled()) {
            throw new IllegalArgumentException(chip.getReason());
        }
        ReadonlyRulesetSpec spec = new ReadonlyRulesetSpec(
                ReadonlyRulesetSpec.KIND_REPO, ReadonlyRulesetSpec.TARGET_THIS_REPO,
                fullName, owner, null, appId, lock ? "active" : "disabled");
        Resolved resolved = new Resolved(cred, installId, repoUrl, fullName, owner, appId);
        long id = call(resolved, spec);
        saveStandalone("REPO", WriteAuthorityRecord.repoKey(cred.getId(), fullName),
                cred, owner, null, spec, lock, id, fullName);
    }

    private WriteAuthorityView.RepoRow lookupRepositoryRuleset(ScmCredential cred, String fullName, String installationId) {
        String owner = fullName.substring(0, fullName.indexOf('/'));
        InstallCache cache = new InstallCache();
        String installId = resolveInstallId(cred, installationId, owner, cache);
        String repoUrl = repoUrlFor(cred, fullName);
        WriteAuthorityView.Chip chip = repoChip(cred, repoUrl);
        ReadonlyRulesetSpec spec = new ReadonlyRulesetSpec(
                ReadonlyRulesetSpec.KIND_REPO, ReadonlyRulesetSpec.TARGET_THIS_REPO,
                fullName, owner, null, appIdOrZero(cred.getAppId()), "active");
        RulesetPresence live = chip.isEnabled()
                ? cache.lookup(cred, installId, repoUrl, spec)
                : RulesetPresence.unknown(spec.rulesetName(), chip.getReason());
        return WriteAuthorityView.RepoRow.builder()
                .credentialId(cred.getId())
                .credentialLabel(cred.getLabel())
                .appId(cred.getAppId())
                .fullName(fullName)
                .canManage(chip.isEnabled())
                .disabledReason(chip.getReason())
                .rulesetId(live.id())
                .rulesetName(live.name() != null ? live.name() : GitHubRulesetClient.RULESET_NAME)
                .rulesetState(live.state())
                .rulesetDetail(live.detail())
                .build();
    }

    private String resolveInstallId(ScmCredential cred, String requested, String owner, InstallCache cache) {
        ScmInstallationOption inst = installationFor(cred, requested, owner, cache);
        if (inst != null && inst.getInstallationId() != null && !inst.getInstallationId().isBlank()) {
            return inst.getInstallationId();
        }
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        throw new IllegalArgumentException("This credential has no installation for " + owner + ".");
    }

    private static String repoUrlFor(ScmCredential cred, String fullName) {
        String host = cred.getHostUrl();
        if (host == null || host.isBlank() || host.contains("github.com")) {
            return "https://github.com/" + fullName + ".git";
        }
        String trimmed = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        return trimmed + "/" + fullName + ".git";
    }

    private void applyEnterprise(WriteAuthorityRequest.EnterpriseRow enterprise, String confirm) {
        if (enterprise.getCredentialId() == null) {
            throw new IllegalArgumentException("An enterprise row needs a credential.");
        }
        boolean lock = "readonly".equalsIgnoreCase(enterprise.getAccess());
        requireConfirm(lock, confirm);
        ScmCredential cred = scmCredentialService.require(enterprise.getCredentialId());
        String reason = enterpriseDenyReason(cred, true);
        if (reason != null) {
            throw new IllegalArgumentException(reason);
        }
        long appId = parseAppId(cred.getAppId());
        ReadonlyRulesetSpec spec = new ReadonlyRulesetSpec(
                ReadonlyRulesetSpec.KIND_ENTERPRISE, ReadonlyRulesetSpec.TARGET_ALL_REPOS,
                null, null, cred.getEnterpriseSlug(), appId, lock ? "active" : "disabled");
        Resolved resolved = new Resolved(cred, cred.getInstallationId(), null, null, null, appId);
        long id = call(resolved, spec);
        saveStandalone("ENTERPRISE", WriteAuthorityRecord.enterpriseKey(cred.getId(), cred.getEnterpriseSlug()),
                cred, null, cred.getEnterpriseSlug(), spec, lock, id, null);
    }

    private void saveStandalone(String subject, String key, ScmCredential cred, String orgLogin, String slug,
                                ReadonlyRulesetSpec spec, boolean lock, long id, String repoFullName) {
        WriteAuthorityRecord row = repository.findByRecordKey(key)
                .orElseGet(() -> WriteAuthorityRecord.builder().recordKey(key).build());
        row.setSubject(subject);
        row.setCredentialId(cred.getId());
        row.setOrgLogin(orgLogin);
        row.setEnterpriseSlug(slug);
        row.setScope(spec.kind());
        row.setTarget(spec.target());
        row.setAccess(lock ? "readonly" : "write");
        row.setEnforcement(lock ? "active" : "disabled");
        row.setRulesetName(spec.rulesetName());
        row.setRepoFullName(repoFullName);
        if (id > 0) {
            row.setRulesetId(id);
        }
        repository.save(row);
    }

    private void retireIfDifferent(Resolved resolved, WriteAuthorityRecord previous, ReadonlyRulesetSpec next) {
        if (previous == null || previous.getScope() == null) {
            return;
        }
        boolean same = next.kind().equals(previous.getScope())
                && next.target().equals(previous.getTarget())
                && next.rulesetName().equals(previous.getRulesetName());
        if (same) {
            return;
        }
        if (!"active".equalsIgnoreCase(previous.getEnforcement())) {
            return;
        }
        call(resolved, specFromRecord(previous, resolved, "disabled"));
    }

    private long call(Resolved resolved, ReadonlyRulesetSpec spec) {
        ScmProviderAdapter adapter = adapterFor(resolved.credential, resolved.repoUrl);
        if (adapter == null) {
            throw new IllegalStateException("No GitHub provider matches this credential.");
        }
        try (var ignored = ScmCredentialContext.open(resolved.credential.getId(), resolved.installationId)) {
            return adapter.ensureReadonlyRuleset(spec);
        }
    }

    private void assertAllowed(Resolved resolved, WriteAuthorityRequest.Side side) {
        String scope = normalizeScope(side.getScope());
        WriteAuthorityView.Chip chip = switch (scope) {
            case ReadonlyRulesetSpec.KIND_ORG -> resolved.orgChip;
            case ReadonlyRulesetSpec.KIND_ENTERPRISE -> resolved.enterpriseChip;
            default -> resolved.repoChip;
        };
        if (chip == null || !chip.isEnabled()) {
            String reason = chip != null && chip.getReason() != null
                    ? chip.getReason()
                    : "That scope is not available on side " + side.getSide() + ".";
            throw new IllegalArgumentException(reason);
        }
        if (ReadonlyRulesetSpec.KIND_ORG.equals(scope) && allRepos(side) == false
                && (resolved.repoFullName == null || resolved.orgLogin == null)) {
            throw new IllegalArgumentException("An org ruleset for this repo needs the repository name and org login.");
        }
        if (ReadonlyRulesetSpec.KIND_ENTERPRISE.equals(scope)
                && !allRepos(side)
                && (resolved.orgLogin == null || resolved.repoFullName == null)) {
            throw new IllegalArgumentException(
                    "An enterprise ruleset for this repo needs the organization and repository name.");
        }
    }

    private void syncLegacyPairFields(RepoMapping mapping) {
        List<WriteAuthorityRecord> rows = repository.findByMappingId(mapping.getId());
        WriteAuthorityRecord a = rowForSide(rows, "A");
        WriteAuthorityRecord b = rowForSide(rows, "B");
        boolean aLocked = locked(a);
        boolean bLocked = locked(b);
        if (aLocked && !bLocked) {
            mapping.setPrimarySide(PairSide.B);
            copyRepoRuleset(mapping, a);
        } else if (bLocked && !aLocked) {
            mapping.setPrimarySide(PairSide.A);
            copyRepoRuleset(mapping, b);
        } else if (!aLocked && !bLocked) {
            mapping.setReplicaRulesetEnforcement("disabled");
        }
    }

    private static void copyRepoRuleset(RepoMapping mapping, WriteAuthorityRecord lockedSide) {
        if (lockedSide != null && ReadonlyRulesetSpec.KIND_REPO.equals(lockedSide.getScope())) {
            mapping.setReplicaRulesetId(lockedSide.getRulesetId());
            mapping.setReplicaRulesetEnforcement(lockedSide.getEnforcement());
        }
    }

    private WriteAuthorityView.Pair pairView(RepoMapping mapping, List<WriteAuthorityRecord> stored, InstallCache cache) {
        List<WriteAuthorityRecord> rows = stored.stream()
                .filter(row -> mapping.getId().equals(row.getMappingId()))
                .toList();
        String link = rows.stream().map(WriteAuthorityRecord::getLinkMode).filter(v -> v != null && !v.isBlank())
                .findFirst().orElse("linked");
        Resolved a = resolve(mapping, "A", cache);
        Resolved b = resolve(mapping, "B", cache);
        return WriteAuthorityView.Pair.builder()
                .id(mapping.getId())
                .name(mapping.getName())
                .linkMode(link)
                .sides(List.of(
                        sideView(mapping, "A", a, rowForSide(rows, "A"), stored, cache),
                        sideView(mapping, "B", b, rowForSide(rows, "B"), stored, cache)))
                .build();
    }

    private WriteAuthorityView.Side sideView(RepoMapping mapping, String side, Resolved resolved,
                                             WriteAuthorityRecord row, List<WriteAuthorityRecord> stored,
                                             InstallCache cache) {
        String access = row != null && row.getAccess() != null ? row.getAccess() : "write";
        String scope = row != null && row.getScope() != null ? row.getScope() : ReadonlyRulesetSpec.KIND_REPO;
        String target = row != null && row.getTarget() != null ? row.getTarget() : ReadonlyRulesetSpec.TARGET_THIS_REPO;
        RulesetPresence repoLive = sideRuleset(resolved, ReadonlyRulesetSpec.KIND_REPO, ReadonlyRulesetSpec.TARGET_THIS_REPO, cache);
        boolean savedIsRepo = ReadonlyRulesetSpec.KIND_REPO.equals(scope);
        RulesetPresence live = savedIsRepo ? repoLive : sideRuleset(resolved, scope, target, cache);
        return WriteAuthorityView.Side.builder()
                .side(side)
                .repoFullName(resolved.repoFullName)
                .repoUrl(resolved.repoUrl)
                .credentialId(resolved.credential != null ? resolved.credential.getId() : null)
                .credentialLabel(resolved.credential != null ? resolved.credential.getLabel() : null)
                .access(access)
                .scope(scope)
                .target(target)
                .enforcement(live.state())
                .rulesetId(live.id())
                .rulesetName(live.name())
                .rulesetState(live.state())
                .rulesetDetail(live.detail())
                .repoRulesetName(repoLive.name())
                .repoRulesetState(repoLive.state())
                .repoRulesetDetail(repoLive.detail())
                .repo(resolved.repoChip)
                .org(resolved.orgChip)
                .enterprise(resolved.enterpriseChip)
                .coverageNote(sideNote(side, mapping, row, stored))
                .build();
    }

    private RulesetPresence sideRuleset(Resolved resolved, String scope, String target, InstallCache cache) {
        if (resolved.credential == null) {
            return RulesetPresence.unknown(null, "This side has no SCM credential.");
        }
        ReadonlyRulesetSpec spec = spec(scope, target, resolved, "active");
        return cache.lookup(resolved.credential, resolved.installationId, resolved.repoUrl, spec);
    }

    private RulesetPresence orgRuleset(ScmCredential cred, ScmInstallationOption inst, InstallCache cache) {
        ReadonlyRulesetSpec spec = new ReadonlyRulesetSpec(
                ReadonlyRulesetSpec.KIND_ORG, ReadonlyRulesetSpec.TARGET_ALL_REPOS,
                null, inst.getAccountLogin(), null, appIdOrZero(cred.getAppId()), "active");
        return cache.lookup(cred, inst.getInstallationId(), null, spec);
    }

    private RulesetPresence enterpriseRuleset(ScmCredential cred, String deny, InstallCache cache) {
        ReadonlyRulesetSpec spec = new ReadonlyRulesetSpec(
                ReadonlyRulesetSpec.KIND_ENTERPRISE, ReadonlyRulesetSpec.TARGET_ALL_REPOS,
                null, null, cred.getEnterpriseSlug(), appIdOrZero(cred.getAppId()), "active");
        if (deny != null) {
            return RulesetPresence.unknown(spec.rulesetName(), deny);
        }
        return cache.lookup(cred, cred.getInstallationId(), null, spec);
    }

    private List<WriteAuthorityView.OrgRow> orgRows(InstallCache cache, List<WriteAuthorityRecord> stored) {
        List<WriteAuthorityView.OrgRow> rows = new ArrayList<>();
        for (ScmCredential cred : scmCredentialService.list("ALL")) {
            if (!cred.isEnabled() || !cred.isGitHubApp()) {
                continue;
            }
            for (ScmInstallationOption inst : cache.get(cred)) {
                if (inst.getAccountType() == null || !"Organization".equalsIgnoreCase(inst.getAccountType())) {
                    continue;
                }
                WriteAuthorityView.Chip chip = orgChip(cred, inst, cache.error(cred.getId()));
                String orgKey = WriteAuthorityRecord.orgKey(cred.getId(), inst.getAccountLogin());
                WriteAuthorityRecord storedRow = stored.stream()
                        .filter(row -> orgKey.equals(row.getRecordKey()))
                        .findFirst()
                        .orElse(null);
                RulesetPresence live = orgRuleset(cred, inst, cache);
                rows.add(WriteAuthorityView.OrgRow.builder()
                        .credentialId(cred.getId())
                        .credentialLabel(cred.getLabel())
                        .orgLogin(inst.getAccountLogin())
                        .provider(cred.getProvider())
                        .canManage(chip.isEnabled())
                        .disabledReason(chip.getReason())
                        .access(storedRow != null && storedRow.getAccess() != null ? storedRow.getAccess() : "write")
                        .enforcement(live.state())
                        .rulesetId(live.id())
                        .rulesetName(live.name())
                        .rulesetState(live.state())
                        .rulesetDetail(live.detail())
                        .build());
            }
        }
        return rows;
    }

    private List<WriteAuthorityView.EnterpriseRow> enterpriseRows(InstallCache cache, List<WriteAuthorityRecord> stored) {
        List<WriteAuthorityView.EnterpriseRow> rows = new ArrayList<>();
        for (ScmCredential cred : scmCredentialService.list("ALL")) {
            if (!cred.isEnabled() || !cred.isGitHubApp() || cred.isEnterprise()) {
                continue;
            }
            if (cred.getEnterpriseSlug() == null || cred.getEnterpriseSlug().isBlank()) {
                continue;
            }
            String deny = cache.enterpriseProbe(cred);
            WriteAuthorityRecord storedRow = stored.stream()
                    .filter(row -> WriteAuthorityRecord.enterpriseKey(cred.getId(), cred.getEnterpriseSlug()).equals(row.getRecordKey()))
                    .findFirst().orElse(null);
            RulesetPresence live = enterpriseRuleset(cred, deny, cache);
            rows.add(WriteAuthorityView.EnterpriseRow.builder()
                    .credentialId(cred.getId())
                    .credentialLabel(cred.getLabel())
                    .slug(cred.getEnterpriseSlug())
                    .probeOk(deny == null)
                    .disabledReason(deny)
                    .access(storedRow != null && storedRow.getAccess() != null ? storedRow.getAccess() : "write")
                    .enforcement(live.state())
                    .rulesetId(live.id())
                    .rulesetName(live.name())
                    .rulesetState(live.state())
                    .rulesetDetail(live.detail())
                    .build());
        }
        return rows;
    }

    private Resolved resolve(RepoMapping mapping, String side, InstallCache cache) {
        boolean b = "B".equals(side);
        String url = b ? mapping.getRepoBUrl() : mapping.getRepoAUrl();
        String credId = b ? mapping.getTargetCredentialId() : mapping.getSourceCredentialId();
        String installationId = b ? mapping.getTargetInstallationId() : mapping.getSourceInstallationId();
        String fullName = scmProviderFacade.parseRepoFullName(url);
        ScmCredential cred = null;
        String listError = null;
        if (credId != null && !credId.isBlank()) {
            try {
                cred = scmCredentialService.require(credId);
                cache.get(cred);
                listError = cache.error(cred.getId());
            } catch (RuntimeException e) {
                listError = e.getMessage();
            }
        }
        ScmInstallationOption inst = cred == null ? null : installationFor(cred, installationId, ownerOf(fullName), cache);
        String orgLogin = inst != null && "Organization".equalsIgnoreCase(inst.getAccountType())
                ? inst.getAccountLogin() : null;
        long appId = cred != null ? appIdOrZero(cred.getAppId()) : 0;
        String boundInstall = inst != null ? inst.getInstallationId() : installationId;
        WriteAuthorityView.Chip repo = repoChip(cred, url);
        WriteAuthorityView.Chip org = orgChip(cred, inst, listError);
        WriteAuthorityView.Chip enterprise = enterpriseChip(cred, cache);
        return new Resolved(cred, boundInstall, url, fullName, orgLogin, appId, repo, org, enterprise);
    }

    private ScmInstallationOption installationFor(ScmCredential cred, String installationId, String owner, InstallCache cache) {
        List<ScmInstallationOption> installs = cache.get(cred);
        if (installationId != null && !installationId.isBlank()) {
            for (ScmInstallationOption inst : installs) {
                if (installationId.equals(inst.getInstallationId())) {
                    return inst;
                }
            }
        }
        if (owner != null) {
            for (ScmInstallationOption inst : installs) {
                if (owner.equalsIgnoreCase(inst.getAccountLogin())) {
                    return inst;
                }
            }
        }
        return null;
    }

    private WriteAuthorityView.Chip repoChip(ScmCredential cred, String repoUrl) {
        if (cred == null) {
            return disabled("This side has no SCM credential.");
        }
        if (!cred.isGitHubApp()) {
            return disabled("Ruleset bypass needs the GitHub App id on this credential, not a PAT.");
        }
        if (appIdOrZero(cred.getAppId()) <= 0) {
            return disabled("This credential has no numeric GitHub App id.");
        }
        ScmProviderAdapter adapter = adapterFor(cred, repoUrl);
        if (adapter == null
                || (adapter.getProviderType() != ScmProviderType.GITHUB
                && adapter.getProviderType() != ScmProviderType.GITHUB_ENTERPRISE)) {
            return disabled("Rulesets are available on GitHub and GHES only.");
        }
        return WriteAuthorityView.Chip.builder().enabled(true).build();
    }

    private WriteAuthorityView.Chip orgChip(ScmCredential cred, ScmInstallationOption inst, String listError) {
        if (cred == null || !cred.isGitHubApp()) {
            return disabled("Org rulesets need a GitHub App credential.");
        }
        if (listError != null) {
            return disabled(listError);
        }
        if (inst == null || inst.getAccountType() == null || !"Organization".equalsIgnoreCase(inst.getAccountType())) {
            return disabled("Org rulesets need an organization installation. This side is not an organization.");
        }
        Map<String, String> permissions = inst.getPermissions();
        String admin = permissions == null ? null : permissions.get("organization_administration");
        if (!"write".equalsIgnoreCase(admin)) {
            String reported = admin == null || admin.isBlank() ? "not granted" : admin.trim();
            return WriteAuthorityView.Chip.builder()
                    .enabled(false)
                    .orgLogin(inst.getAccountLogin())
                    .reason("GitHub reports organization Administration as " + reported
                            + " on this installation. A permission change on the App applies here only after this installation accepts it.")
                    .build();
        }
        return WriteAuthorityView.Chip.builder().enabled(true).orgLogin(inst.getAccountLogin()).build();
    }

    private WriteAuthorityView.Chip enterpriseChip(ScmCredential cred, InstallCache cache) {
        String reason = enterpriseDenyReason(cred, false);
        if (reason != null) {
            return disabled(reason);
        }
        String probed = cache.enterpriseProbe(cred);
        if (probed != null) {
            return disabled(probed);
        }
        return WriteAuthorityView.Chip.builder().enabled(true).build();
    }

    /**
     * @param probe when true, call the enterprise rulesets API. The screen probes; a missing slug does not.
     */
    private String enterpriseDenyReason(ScmCredential cred, boolean probe) {
        if (cred == null || !cred.isGitHubApp()) {
            return "Enterprise rulesets need a GitHub App credential.";
        }
        if (cred.isEnterprise()) {
            return "GHES has no enterprise ruleset API. Use an organization ruleset.";
        }
        if (cred.getEnterpriseSlug() == null || cred.getEnterpriseSlug().isBlank()) {
            return "Save an enterprise slug on this GitHub App to enable enterprise rulesets.";
        }
        if (!probe) {
            return null;
        }
        ScmProviderAdapter adapter = scmProviderFacade.getAdapter(ScmProviderType.GITHUB);
        if (adapter == null) {
            return "GitHub adapter is not available.";
        }
        try (var ignored = ScmCredentialContext.open(cred.getId(), cred.getInstallationId())) {
            return adapter.probeEnterpriseRulesets(cred.getEnterpriseSlug());
        }
    }

    private String mappingNote(RepoMapping mapping, List<WriteAuthorityRecord> stored) {
        String a = sideNote("A", mapping, rowForSide(stored.stream()
                .filter(row -> mapping.getId().equals(row.getMappingId())).toList(), "A"), stored);
        String b = sideNote("B", mapping, rowForSide(stored.stream()
                .filter(row -> mapping.getId().equals(row.getMappingId())).toList(), "B"), stored);
        if (a == null && b == null) {
            return null;
        }
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a + " " + b;
    }

    private String sideNote(String side, RepoMapping mapping, WriteAuthorityRecord row, List<WriteAuthorityRecord> stored) {
        if (locked(row) && (ReadonlyRulesetSpec.KIND_ORG.equals(row.getScope())
                || ReadonlyRulesetSpec.KIND_ENTERPRISE.equals(row.getScope()))) {
            return sentence(side, row.getScope(), row.getOrgLogin(), row.getEnterpriseSlug(), row.getTarget());
        }
        String credId = "B".equals(side) ? mapping.getTargetCredentialId() : mapping.getSourceCredentialId();
        String owner = ownerOf(scmProviderFacade.parseRepoFullName(
                "B".equals(side) ? mapping.getRepoBUrl() : mapping.getRepoAUrl()));
        if (credId != null && owner != null) {
            for (WriteAuthorityRecord candidate : stored) {
                if ("ORG".equals(candidate.getSubject())
                        && credId.equals(candidate.getCredentialId())
                        && owner.equalsIgnoreCase(candidate.getOrgLogin())
                        && locked(candidate)) {
                    return sentence(side, ReadonlyRulesetSpec.KIND_ORG, owner, null, candidate.getTarget());
                }
            }
        }
        if (credId != null) {
            for (WriteAuthorityRecord candidate : stored) {
                if ("ENTERPRISE".equals(candidate.getSubject())
                        && credId.equals(candidate.getCredentialId())
                        && locked(candidate)) {
                    return sentence(side, ReadonlyRulesetSpec.KIND_ENTERPRISE, null,
                            candidate.getEnterpriseSlug(), candidate.getTarget());
                }
            }
        }
        return null;
    }

    private static String sentence(String side, String scope, String org, String slug, String target) {
        String where = ReadonlyRulesetSpec.KIND_ENTERPRISE.equals(scope)
                ? "the enterprise ruleset on " + slug
                : "the org ruleset on " + org;
        String fleet = ReadonlyRulesetSpec.TARGET_ALL_REPOS.equals(target) ? " (all repositories)" : "";
        return "Side " + side + " is read-only because of " + where + fleet + ".";
    }

    private ReadonlyRulesetSpec spec(String scope, String target, Resolved resolved, String enforcement) {
        return new ReadonlyRulesetSpec(
                scope, target, resolved.repoFullName, resolved.orgLogin,
                resolved.credential.getEnterpriseSlug(), resolved.appId, enforcement);
    }

    private ReadonlyRulesetSpec specFromRecord(WriteAuthorityRecord previous, Resolved resolved, String enforcement) {
        String repo = previous.getRepoFullName() != null ? previous.getRepoFullName() : resolved.repoFullName;
        String org = previous.getOrgLogin() != null ? previous.getOrgLogin() : resolved.orgLogin;
        String slug = previous.getEnterpriseSlug() != null
                ? previous.getEnterpriseSlug() : resolved.credential.getEnterpriseSlug();
        return new ReadonlyRulesetSpec(
                previous.getScope(), previous.getTarget(), repo, org, slug, resolved.appId, enforcement);
    }

    private ScmProviderAdapter adapterFor(ScmCredential cred, String repoUrl) {
        if (repoUrl != null && !repoUrl.isBlank()) {
            ScmProviderAdapter adapter = scmProviderFacade.getAdapterForUrl(repoUrl);
            if (adapter != null && (adapter.getProviderType() == ScmProviderType.GITHUB
                    || adapter.getProviderType() == ScmProviderType.GITHUB_ENTERPRISE)) {
                return adapter;
            }
        }
        if (cred != null && cred.isEnterprise()) {
            return scmProviderFacade.getAdapter(ScmProviderType.GITHUB_ENTERPRISE);
        }
        return scmProviderFacade.getAdapter(ScmProviderType.GITHUB);
    }

    private static Map<String, WriteAuthorityRequest.Side> indexSides(List<WriteAuthorityRequest.Side> sides) {
        if (sides == null || sides.size() != 2) {
            throw new IllegalArgumentException("A pair update needs both sides.");
        }
        Map<String, WriteAuthorityRequest.Side> bySide = new HashMap<>();
        for (WriteAuthorityRequest.Side side : sides) {
            if (side.getSide() == null) {
                throw new IllegalArgumentException("Each side needs a side letter.");
            }
            String letter = side.getSide().trim().toUpperCase();
            if (!"A".equals(letter) && !"B".equals(letter)) {
                throw new IllegalArgumentException("Side must be A or B.");
            }
            side.setSide(letter);
            bySide.put(letter, side);
        }
        if (!bySide.containsKey("A") || !bySide.containsKey("B")) {
            throw new IllegalArgumentException("A pair update needs side A and side B.");
        }
        return bySide;
    }

    private static void requireConfirm(boolean needed, String confirm) {
        if (needed && !CONFIRM_ALL_REPOS.equals(confirm)) {
            throw new IllegalArgumentException("Type ALL REPOS to cover every repository in this org or enterprise.");
        }
    }

    private static boolean readonly(WriteAuthorityRequest.Side side) {
        return side != null && "readonly".equalsIgnoreCase(side.getAccess());
    }

    private static boolean allRepos(WriteAuthorityRequest.Side side) {
        return side != null && ReadonlyRulesetSpec.TARGET_ALL_REPOS.equalsIgnoreCase(
                side.getTarget() == null ? "" : side.getTarget().trim());
    }

    private static boolean locked(WriteAuthorityRecord row) {
        return row != null && "readonly".equalsIgnoreCase(row.getAccess()) && "active".equalsIgnoreCase(row.getEnforcement());
    }

    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return ReadonlyRulesetSpec.KIND_REPO;
        }
        String value = scope.trim().toLowerCase();
        if (ReadonlyRulesetSpec.KIND_ORG.equals(value) || ReadonlyRulesetSpec.KIND_ENTERPRISE.equals(value)) {
            return value;
        }
        if (ReadonlyRulesetSpec.KIND_REPO.equals(value)) {
            return value;
        }
        throw new IllegalArgumentException("Unknown ruleset scope: " + scope);
    }

    private static String targetFor(String scope, String target) {
        if (ReadonlyRulesetSpec.KIND_REPO.equals(scope)) {
            return ReadonlyRulesetSpec.TARGET_THIS_REPO;
        }
        if (target != null && ReadonlyRulesetSpec.TARGET_ALL_REPOS.equalsIgnoreCase(target.trim())) {
            return ReadonlyRulesetSpec.TARGET_ALL_REPOS;
        }
        return ReadonlyRulesetSpec.TARGET_THIS_REPO;
    }

    private static WriteAuthorityRecord rowForSide(List<WriteAuthorityRecord> rows, String side) {
        for (WriteAuthorityRecord row : rows) {
            if (side.equals(row.getSide())) {
                return row;
            }
        }
        return null;
    }

    private static String ownerOf(String fullName) {
        if (fullName == null || !fullName.contains("/")) {
            return null;
        }
        return fullName.substring(0, fullName.indexOf('/'));
    }

    private static long parseAppId(String appId) {
        long parsed = appIdOrZero(appId);
        if (parsed <= 0) {
            throw new IllegalStateException(
                    "This credential has no GitHub App id. Ruleset bypass uses the App id, not a PAT or installation id.");
        }
        return parsed;
    }

    private static long appIdOrZero(String appId) {
        if (appId == null || appId.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(appId.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static WriteAuthorityView.Chip disabled(String reason) {
        return WriteAuthorityView.Chip.builder().enabled(false).reason(reason).build();
    }

    private record Resolved(
            ScmCredential credential,
            String installationId,
            String repoUrl,
            String repoFullName,
            String orgLogin,
            long appId,
            WriteAuthorityView.Chip repoChip,
            WriteAuthorityView.Chip orgChip,
            WriteAuthorityView.Chip enterpriseChip) {

        private Resolved(ScmCredential credential, String installationId, String repoUrl,
                         String repoFullName, String orgLogin, long appId) {
            this(credential, installationId, repoUrl, repoFullName, orgLogin, appId, null, null, null);
        }
    }

    private final class InstallCache {
        private final Map<String, List<ScmInstallationOption>> ok = new HashMap<>();
        private final Map<String, String> errors = new HashMap<>();
        private final Map<String, RulesetPresence> rulesets = new HashMap<>();

        RulesetPresence lookup(ScmCredential cred, String installationId, String repoUrl, ReadonlyRulesetSpec spec) {
            String key = (cred == null ? "" : cred.getId()) + "|"
                    + GitHubRulesetClient.collectionPath(spec) + "|" + spec.rulesetName();
            if (rulesets.containsKey(key)) {
                return rulesets.get(key);
            }
            RulesetPresence presence;
            try {
                if (cred == null) {
                    presence = RulesetPresence.unknown(spec.rulesetName(), "No credential.");
                } else {
                    ScmProviderAdapter adapter = adapterFor(cred, repoUrl);
                    if (adapter == null) {
                        presence = RulesetPresence.unknown(spec.rulesetName(), "No GitHub provider matches this credential.");
                    } else {
                        try (var ignored = ScmCredentialContext.open(cred.getId(), installationId)) {
                            presence = adapter.lookupReadonlyRuleset(spec);
                        }
                    }
                }
            } catch (RuntimeException e) {
                presence = RulesetPresence.unknown(spec.rulesetName(), e.getMessage());
            }
            rulesets.put(key, presence);
            return presence;
        }

        List<ScmInstallationOption> get(ScmCredential cred) {
            if (cred == null || errors.containsKey(cred.getId())) {
                return List.of();
            }
            if (ok.containsKey(cred.getId())) {
                return ok.get(cred.getId());
            }
            if (!cred.isGitHubApp()) {
                ok.put(cred.getId(), List.of());
                return List.of();
            }
            try {
                Set<String> selected = new HashSet<>(
                        InstallationIds.decode(cred.getInstallationIdsJson(), cred.getInstallationId()));
                List<ScmInstallationOption> filtered = scmCredentialService.listInstallations(cred.getId()).stream()
                        .filter(inst -> selected.isEmpty() || selected.contains(inst.getInstallationId()))
                        .toList();
                ok.put(cred.getId(), filtered);
                return filtered;
            } catch (RuntimeException e) {
                errors.put(cred.getId(), e.getMessage());
                return List.of();
            }
        }

        String error(String credentialId) {
            return errors.get(credentialId);
        }

        String enterpriseProbe(ScmCredential cred) {
            if (probed.contains(cred.getId())) {
                return probes.get(cred.getId());
            }
            probed.add(cred.getId());
            String reason;
            try {
                reason = enterpriseDenyReason(cred, true);
            } catch (RuntimeException e) {
                reason = e.getMessage() != null ? e.getMessage() : "Enterprise rulesets could not be checked.";
            }
            probes.put(cred.getId(), reason);
            return reason;
        }

        private final Map<String, String> probes = new HashMap<>();
        private final Set<String> probed = new HashSet<>();
    }
}
